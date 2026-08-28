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

package com.seibel.distanthorizons.fabric.mixins.mods.sodium;

#if MC_VER == MC_26_2_0

import com.seibel.distanthorizons.core.render.nativeReadiness.NativeChunkRenderReadinessTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
public abstract class MixinSodiumRenderSectionNativeChunkReadiness
{
	@Shadow @Final private int chunkX;
	@Shadow @Final private int chunkZ;
	@Shadow public abstract boolean isBuilt();

	@Unique private boolean dh$wasBuiltBeforeStateChange;
	@Unique private long dh$readinessTrackerGeneration;



	@Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
	private void dh$captureReadinessTrackerGeneration(CallbackInfo callback)
	{
		this.dh$readinessTrackerGeneration =
			NativeChunkRenderReadinessTracker.INSTANCE.captureSectionCreationGeneration();
	}

	@Inject(method = "setInfo", at = @At("HEAD"), remap = false, require = 0)
	private void dh$captureBuiltState(CallbackInfoReturnable<Integer> callback)
	{
		this.dh$wasBuiltBeforeStateChange = this.isBuilt();
	}

	@Inject(method = "setInfo", at = @At("RETURN"), remap = false, require = 0)
	private void dh$publishBuiltState(CallbackInfoReturnable<Integer> callback)
	{
		this.dh$publishStateTransition();
	}

	@Inject(method = "delete", at = @At("HEAD"), remap = false, require = 0)
	private void dh$captureBuiltStateBeforeDelete(CallbackInfo callback)
	{
		this.dh$wasBuiltBeforeStateChange = this.isBuilt();
	}

	@Inject(method = "delete", at = @At("RETURN"), remap = false, require = 0)
	private void dh$publishBuiltStateAfterDelete(CallbackInfo callback)
	{
		this.dh$publishStateTransition();
	}

	@Unique
	private void dh$publishStateTransition()
	{
		NativeChunkRenderReadinessTracker.INSTANCE.onSectionStateChanged(
			this.dh$readinessTrackerGeneration,
			this.chunkX,
			this.chunkZ,
			this.dh$wasBuiltBeforeStateChange,
			this.isBuilt());
	}

}

#else

public abstract class MixinSodiumRenderSectionNativeChunkReadiness { }

#endif
