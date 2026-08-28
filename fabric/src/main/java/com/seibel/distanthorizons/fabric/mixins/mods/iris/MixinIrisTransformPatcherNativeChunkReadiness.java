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

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.nativeReadiness.IrisDhShaderReadinessPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class MixinIrisTransformPatcherNativeChunkReadiness
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static boolean loggedSuccessfulPatch;



	@ModifyArgs(
		method = "patchDHTerrain",
		at = @At(
			value = "INVOKE",
			target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;transform(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/pipeline/transform/parameter/Parameters;)Ljava/util/Map;",
			remap = false),
		remap = false,
		require = 0)
	private static void dh$patchReadinessAwareOverdraw(Args args)
	{
		String geometrySource = args.get(2);
		String tessControlSource = args.get(3);
		String tessEvalSource = args.get(4);
		if (geometrySource != null || tessControlSource != null || tessEvalSource != null)
		{
			return;
		}

		String vertexSource = args.get(1);
		String fragmentSource = args.get(5);
		IrisDhShaderReadinessPatcher.PatchedShaders patched =
			IrisDhShaderReadinessPatcher.tryPatch(vertexSource, fragmentSource);
		if (!patched.patched)
		{
			return;
		}

		args.set(1, patched.vertexSource);
		args.set(5, patched.fragmentSource);
		if (!loggedSuccessfulPatch)
		{
			loggedSuccessfulPatch = true;
			LOGGER.info("Enabled native chunk readiness handoff in a compatible Iris DH shader-pack program.");
		}
	}

}

#else

public abstract class MixinIrisTransformPatcherNativeChunkReadiness { }

#endif
