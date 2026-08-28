/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.render.openGl.terrain;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.nativeReadiness.IrisDhShaderReadinessPatcher;
import com.seibel.distanthorizons.core.render.nativeReadiness.NativeChunkReadinessFadeState;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.ISodiumAccessor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

/**
 * Owns the camera-centered native chunk readiness mask consumed by DH terrain
 * shaders. The texture is deliberately small and is updated only on the render
 * thread.
 */
public final class GlNativeChunkReadinessTexture
{
	public static final GlNativeChunkReadinessTexture INSTANCE = new GlNativeChunkReadinessTexture();
	public static final String SAMPLER_NAME = IrisDhShaderReadinessPatcher.READINESS_SAMPLER_NAME;

	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	private static final int DEFAULT_SHADER_TEXTURE_UNIT = 15;
	private static final int MAX_MASK_RADIUS = 64;
	private static final long UNSUPPORTED_LOG_DELAY_NANOS = 5_000_000_000L;
	private static final long UNSUPPORTED_LOG_INTERVAL_NANOS = 30_000_000_000L;
	private static final long STATS_LOG_INTERVAL_NANOS = 10_000_000_000L;

	private final NativeChunkReadinessFadeState fadeState = new NativeChunkReadinessFadeState();

	private byte[] rawReadinessMask = new byte[0];
	private byte[] fadedReadinessMask = new byte[0];
	private ByteBuffer uploadBuffer = BufferUtils.createByteBuffer(1);

	private int textureId;
	private int textureWidth;
	private int textureHeight;
	private int allocatedTextureWidth;
	private int allocatedTextureHeight;
	private int maskMinOffsetX;
	private int maskMinOffsetZ;
	private float cameraSubChunkX;
	private float cameraSubChunkZ;

	private int readyChunkCount;
	private int fadingChunkCount;
	private int waitingChunkCount;

	private long configEnabledSinceNanos;
	private long lastUnsupportedLogNanos;
	private long lastStatsLogNanos;
	private boolean configWasEnabled;
	private boolean maskPrepared;
	private boolean consumerActivated;

	private IMinecraftRenderWrapper minecraftRender;
	private IIrisAccessor irisAccessor;

	private static volatile boolean irisSamplerRegistered;
	private static volatile boolean irisSamplerRegistrationFailed;



	private GlNativeChunkReadinessTexture() { }

	public boolean prepareForDefaultShader()
	{
		return this.prepare(false);
	}

	public boolean prepareForIrisShader()
	{
		return this.prepare(true);
	}

	private boolean prepare(boolean irisShader)
	{
		if (!Config.Client.Advanced.Graphics.Culling.enableNativeChunkReadinessHandoff.get())
		{
			if (this.configWasEnabled)
			{
				this.fadeState.clear();
			}
			this.configWasEnabled = false;
			this.configEnabledSinceNanos = 0L;
			this.maskPrepared = false;
			return false;
		}

		if (this.irisAccessor == null)
		{
			this.irisAccessor = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
		}
		if (irisShader && this.irisAccessor != null && this.irisAccessor.isRenderingShadowPass())
		{
			return false;
		}

		this.configWasEnabled = true;
		long nowNanos = System.nanoTime();
		if (this.configEnabledSinceNanos == 0L)
		{
			this.configEnabledSinceNanos = nowNanos;
		}

		ISodiumAccessor sodiumAccessor = ModAccessorInjector.INSTANCE.get(ISodiumAccessor.class);
		if (this.minecraftRender == null)
		{
			this.minecraftRender = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
		}
		if (sodiumAccessor == null || this.minecraftRender == null)
		{
			this.logUnsupportedIfNeeded(nowNanos);
			this.maskPrepared = false;
			return false;
		}

		DhVec3d cameraPos = this.minecraftRender.getCameraExactPosition();
		int cameraChunkX = (int) Math.floor(cameraPos.x / 16.0);
		int cameraChunkZ = (int) Math.floor(cameraPos.z / 16.0);

		float clipDistance = Math.max(0.0f, RenderUtil.getNearClipPlaneInBlocks());
		int clipRadius = (int) Math.ceil(clipDistance * 1.5f / 16.0f) + 2;
		int radius = Math.max(this.minecraftRender.getRenderDistance() + 2, clipRadius);
		radius = Math.max(2, Math.min(MAX_MASK_RADIUS, radius));

		int maskSize = radius * 2 + 1;
		int maskLength = Math.multiplyExact(maskSize, maskSize);
		this.ensureMaskCapacity(maskLength);

		int minChunkX = cameraChunkX - radius;
		int minChunkZ = cameraChunkZ - radius;
		if (!sodiumAccessor.fillNativeChunkRenderReadinessMask(
			minChunkX, minChunkZ,
			maskSize, maskSize,
			this.rawReadinessMask))
		{
			this.logUnsupportedIfNeeded(nowNanos);
			this.maskPrepared = false;
			return false;
		}

		this.fadeState.update(
			minChunkX, minChunkZ,
			maskSize, maskSize,
			this.rawReadinessMask,
			this.fadedReadinessMask,
			nowNanos);

		this.textureWidth = maskSize;
		this.textureHeight = maskSize;
		this.maskMinOffsetX = -radius;
		this.maskMinOffsetZ = -radius;
		this.cameraSubChunkX = (float) (cameraPos.x - cameraChunkX * 16.0);
		this.cameraSubChunkZ = (float) (cameraPos.z - cameraChunkZ * 16.0);
		this.updateStats(maskLength);
		this.uploadMask(maskLength);
		this.maskPrepared = true;

		if (this.consumerActivated
			&& nowNanos - this.lastStatsLogNanos >= STATS_LOG_INTERVAL_NANOS
			&& (this.waitingChunkCount > 0 || this.fadingChunkCount > 0))
		{
			this.lastStatsLogNanos = nowNanos;
			LOGGER.info("Native readiness mask: size=[" + maskSize + "x" + maskSize
				+ "], waiting=[" + this.waitingChunkCount
				+ "], fading=[" + this.fadingChunkCount
				+ "], ready=[" + this.readyChunkCount + "].");
		}

		return true;
	}

	public void applyDefaultShaderUniforms(
			boolean preparedForPass,
			int samplerUniform,
			int enabledUniform,
			int maskMinOffsetUniform,
			int maskSizeUniform,
			int cameraSubChunkUniform)
	{
		boolean active = preparedForPass && this.maskPrepared && this.textureId != 0;
		GL33.glUniform1i(enabledUniform, active ? 1 : 0);
		if (!active)
		{
			return;
		}

		this.bindForDefaultShader();
		GL33.glUniform1i(samplerUniform, DEFAULT_SHADER_TEXTURE_UNIT);
		this.applyMaskUniforms(maskMinOffsetUniform, maskSizeUniform, cameraSubChunkUniform);
		this.logConsumerActivated("DH OpenGL");
	}

	public void applyIrisShaderUniforms(
			boolean preparedForPass,
			int enabledUniform,
			int maskMinOffsetUniform,
			int maskSizeUniform,
			int cameraSubChunkUniform)
	{
		boolean active = preparedForPass
			&& this.maskPrepared
			&& this.textureId != 0
			&& irisSamplerRegistered
			&& !irisSamplerRegistrationFailed;
		GL33.glUniform1i(enabledUniform, active ? 1 : 0);
		if (!active)
		{
			return;
		}

		this.applyMaskUniforms(maskMinOffsetUniform, maskSizeUniform, cameraSubChunkUniform);
		this.logConsumerActivated("Iris DH shader");
	}

	private void applyMaskUniforms(
			int maskMinOffsetUniform,
			int maskSizeUniform,
			int cameraSubChunkUniform)
	{
		GL33.glUniform2i(maskMinOffsetUniform, this.maskMinOffsetX, this.maskMinOffsetZ);
		GL33.glUniform2i(maskSizeUniform, this.textureWidth, this.textureHeight);
		GL33.glUniform2f(cameraSubChunkUniform, this.cameraSubChunkX, this.cameraSubChunkZ);
	}

	public int getTextureId()
	{
		return this.textureId;
	}

	public static void markIrisSamplerRegistered()
	{
		irisSamplerRegistered = true;
	}

	public static void markIrisSamplerRegistrationFailed()
	{
		irisSamplerRegistrationFailed = true;
	}

	public static void resetIrisSamplerRegistration()
	{
		irisSamplerRegistered = false;
		irisSamplerRegistrationFailed = false;
	}

	private void ensureMaskCapacity(int maskLength)
	{
		if (this.rawReadinessMask.length != maskLength)
		{
			this.rawReadinessMask = new byte[maskLength];
			this.fadedReadinessMask = new byte[maskLength];
		}
		if (this.uploadBuffer.capacity() < maskLength)
		{
			this.uploadBuffer = BufferUtils.createByteBuffer(maskLength);
		}
	}

	private void uploadMask(int maskLength)
	{
		if (this.textureId == 0)
		{
			this.textureId = GL11.glGenTextures();
		}

		this.uploadBuffer.clear();
		this.uploadBuffer.put(this.fadedReadinessMask, 0, maskLength);
		this.uploadBuffer.flip();

		int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEFAULT_SHADER_TEXTURE_UNIT);
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int previousUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
		try
		{
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			if (this.allocatedTextureWidth != this.textureWidth
				|| this.allocatedTextureHeight != this.textureHeight)
			{
				GL11.glTexImage2D(
					GL11.GL_TEXTURE_2D, 0, GL30.GL_R8,
					this.textureWidth, this.textureHeight, 0,
					GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
					this.uploadBuffer);
				this.allocatedTextureWidth = this.textureWidth;
				this.allocatedTextureHeight = this.textureHeight;
			}
			else
			{
				GL11.glTexSubImage2D(
					GL11.GL_TEXTURE_2D, 0,
					0, 0,
					this.textureWidth, this.textureHeight,
					GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
					this.uploadBuffer);
			}
		}
		finally
		{
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
			GL13.glActiveTexture(previousActiveTexture);
		}
	}

	private void bindForDefaultShader()
	{
		int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEFAULT_SHADER_TEXTURE_UNIT);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
		GL13.glActiveTexture(previousActiveTexture);
	}

	private void updateStats(int maskLength)
	{
		this.readyChunkCount = 0;
		this.fadingChunkCount = 0;
		this.waitingChunkCount = 0;

		for (int index = 0; index < maskLength; index++)
		{
			int fadeValue = this.fadedReadinessMask[index] & 0xFF;
			if (fadeValue == 0xFF)
			{
				this.readyChunkCount++;
			}
			else if (fadeValue == 0)
			{
				this.waitingChunkCount++;
			}
			else
			{
				this.fadingChunkCount++;
			}
		}
	}

	private void logConsumerActivated(String consumerName)
	{
		if (this.consumerActivated)
		{
			return;
		}

		this.consumerActivated = true;
		this.lastStatsLogNanos = System.nanoTime();
		LOGGER.info("Native chunk readiness handoff active via [" + consumerName
			+ "], mask=[" + this.textureWidth + "x" + this.textureHeight
			+ "], fade=[" + (NativeChunkReadinessFadeState.DEFAULT_FADE_DURATION_NANOS / 1_000_000L)
			+ " ms].");
	}

	private void logUnsupportedIfNeeded(long nowNanos)
	{
		if (nowNanos - this.configEnabledSinceNanos < UNSUPPORTED_LOG_DELAY_NANOS
			|| nowNanos - this.lastUnsupportedLogNanos < UNSUPPORTED_LOG_INTERVAL_NANOS)
		{
			return;
		}

		this.lastUnsupportedLogNanos = nowNanos;
		LOGGER.warn("Native chunk readiness handoff is enabled, but compatible Minecraft 26.2 Sodium renderer hooks are not active; using normal DH overdraw prevention.");
	}

}
