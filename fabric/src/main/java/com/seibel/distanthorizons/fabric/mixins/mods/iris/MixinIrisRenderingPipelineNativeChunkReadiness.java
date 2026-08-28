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

import com.google.common.collect.ImmutableSet;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlNativeChunkReadinessTexture;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class MixinIrisRenderingPipelineNativeChunkReadiness
{
	private static final Logger LOGGER = LogManager.getLogger(
		"DistantHorizons-NativeChunkReadiness-Iris");

	@Unique
	private boolean dh$nativeReadinessSamplerStateInitialized;



	@Inject(
		method = "addGbufferOrShadowSamplers",
		at = @At("RETURN"),
		remap = false,
		require = 0)
	private void dh$registerReadinessSampler(
			SamplerHolder samplers,
			ImageHolder images,
			Supplier<ImmutableSet<Integer>> flipped,
			boolean shadow,
			boolean hasTexture,
			boolean hasLightmap,
			boolean hasOverlay,
			CallbackInfo callback)
	{
		if (!this.dh$nativeReadinessSamplerStateInitialized)
		{
			this.dh$nativeReadinessSamplerStateInitialized = true;
			GlNativeChunkReadinessTexture.resetIrisSamplerRegistration();
		}

		if (!samplers.hasSampler(GlNativeChunkReadinessTexture.SAMPLER_NAME))
		{
			return;
		}

		try
		{
			boolean registered = samplers.addDynamicSampler(
				GlNativeChunkReadinessTexture.INSTANCE::getTextureId,
				(GlSampler) null,
				GlNativeChunkReadinessTexture.SAMPLER_NAME);
			if (registered)
			{
				GlNativeChunkReadinessTexture.markIrisSamplerRegistered();
			}
			else
			{
				GlNativeChunkReadinessTexture.markIrisSamplerRegistrationFailed();
				LOGGER.error("Iris found the native readiness sampler but did not register it; using normal DH overdraw prevention.");
			}
		}
		catch (IllegalStateException e)
		{
			GlNativeChunkReadinessTexture.markIrisSamplerRegistrationFailed();
			LOGGER.error("Iris has no texture unit available for the native readiness mask; using normal DH overdraw prevention.", e);
		}
	}

}

#else

public abstract class MixinIrisRenderingPipelineNativeChunkReadiness { }

#endif
