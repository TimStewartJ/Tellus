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

package com.seibel.distanthorizons.fabric.mixins.mods.iris;

#if MC_VER == MC_26_2_0

import com.seibel.distanthorizons.common.render.openGl.terrain.GlNativeChunkReadinessTexture;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL33;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisLodRenderProgram", remap = false)
public abstract class MixinIrisLodRenderProgramNativeChunkReadiness
{
	private static final int UNINITIALIZED_UNIFORM = Integer.MIN_VALUE;

	@Shadow @Final private int id;

	@Unique private int dh$enabledUniform = UNINITIALIZED_UNIFORM;
	@Unique private int dh$maskMinOffsetUniform = -1;
	@Unique private int dh$maskSizeUniform = -1;
	@Unique private int dh$cameraSubChunkUniform = -1;
	@Unique private boolean dh$preparedForPass;



	@Inject(method = "fillUniformData", at = @At("HEAD"), remap = false, require = 0)
	private void dh$prepareReadinessMask(
			Matrix4fc projection, Matrix4fc modelView,
			int worldYOffset, float partialTicks,
			CallbackInfo callback)
	{
		this.dh$initializeUniformLocations();
		this.dh$preparedForPass = this.dh$enabledUniform != -1
			&& GlNativeChunkReadinessTexture.INSTANCE.prepareForIrisShader();
	}

	@Inject(method = "fillUniformData", at = @At("RETURN"), remap = false, require = 0)
	private void dh$applyReadinessUniforms(
			Matrix4fc projection, Matrix4fc modelView,
			int worldYOffset, float partialTicks,
			CallbackInfo callback)
	{
		if (this.dh$enabledUniform == -1)
		{
			return;
		}

		GlNativeChunkReadinessTexture.INSTANCE.applyIrisShaderUniforms(
			this.dh$preparedForPass,
			this.dh$enabledUniform,
			this.dh$maskMinOffsetUniform,
			this.dh$maskSizeUniform,
			this.dh$cameraSubChunkUniform);
	}

	@Unique
	private void dh$initializeUniformLocations()
	{
		if (this.dh$enabledUniform != UNINITIALIZED_UNIFORM)
		{
			return;
		}

		this.dh$enabledUniform = GL33.glGetUniformLocation(this.id, "dhNativeReadinessEnabled");
		this.dh$maskMinOffsetUniform = GL33.glGetUniformLocation(this.id, "dhNativeReadinessMaskMinOffset");
		this.dh$maskSizeUniform = GL33.glGetUniformLocation(this.id, "dhNativeReadinessMaskSize");
		this.dh$cameraSubChunkUniform = GL33.glGetUniformLocation(this.id, "dhNativeReadinessCameraSubChunk");
	}

}

#else

public abstract class MixinIrisLodRenderProgramNativeChunkReadiness { }

#endif
