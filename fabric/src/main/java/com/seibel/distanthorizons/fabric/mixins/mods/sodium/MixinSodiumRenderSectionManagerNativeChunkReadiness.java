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
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class MixinSodiumRenderSectionManagerNativeChunkReadiness
{
	@Shadow @Final private ClientLevel level;

	@Unique private long dh$readinessTrackerGeneration;



	@Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
	private void dh$activateReadinessTracker(CallbackInfo callback)
	{
		this.dh$readinessTrackerGeneration =
			NativeChunkRenderReadinessTracker.INSTANCE.activate(this.level.getSectionsCount());
	}

	@Inject(method = "destroy", at = @At("RETURN"), remap = false, require = 0)
	private void dh$deactivateReadinessTracker(CallbackInfo callback)
	{
		NativeChunkRenderReadinessTracker.INSTANCE.deactivate(this.dh$readinessTrackerGeneration);
	}

	@Inject(method = "onSectionAdded", at = @At("HEAD"), remap = false, require = 0)
	private void dh$beginSectionCreation(
			int chunkX, int chunkY, int chunkZ,
			CallbackInfo callback)
	{
		NativeChunkRenderReadinessTracker.INSTANCE.beginSectionCreation(
			this.dh$readinessTrackerGeneration);
	}

	@Inject(method = "onSectionAdded", at = @At("RETURN"), remap = false, require = 0)
	private void dh$endSectionCreation(
			int chunkX, int chunkY, int chunkZ,
			CallbackInfo callback)
	{
		NativeChunkRenderReadinessTracker.INSTANCE.endSectionCreation(
			this.dh$readinessTrackerGeneration);
	}

}

#else

public abstract class MixinSodiumRenderSectionManagerNativeChunkReadiness { }

#endif
