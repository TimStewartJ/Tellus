package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps planned waterfall curtains bounded while preserving ordinary water.
 */
@Mixin(FlowingFluid.class)
public class WaterSourceConversionMixin {
   private static final int CURTAIN_LOOKUP_RETRY_TICKS = 20;

   @Inject(
      method = "getNewLiquid(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;",
      at = @At("RETURN"),
      cancellable = true
   )
   private void tellus$gateWaterSourceConversion(
      ServerLevel level,
      BlockPos pos,
      BlockState state,
      CallbackInfoReturnable<FluidState> cir
   ) {
      FluidState current = level.getFluidState(pos);
      FluidState resolved = cir.getReturnValue();
      if (current.getType().isSame(Fluids.WATER)
         && !current.isSource()
         && level.getChunkSource().getGenerator()
           instanceof EarthChunkGenerator generator) {
         boolean curtainCandidate = isCurtainCandidate(state, current);
         boolean sourceConversion = resolved.isSourceOfType(Fluids.WATER);
         if (!curtainCandidate && !sourceConversion) {
           return;
         }
         EarthChunkGenerator.WaterfallFluidPolicy policy =
           generator.waterfallFluidPolicy(
              pos.getX(), pos.getY(), pos.getZ()
           );
         if (!policy.resolved()) {
           if (!curtainCandidate) {
              level.scheduleTick(
                 pos, current.getType(), CURTAIN_LOOKUP_RETRY_TICKS
              );
           }
           cir.setReturnValue(current);
           return;
         }
         if (curtainCandidate && policy.preserveCurtain()
           || sourceConversion && policy.suppressSourceConversion()) {
           cir.setReturnValue(current);
         }
      }
   }

   @Inject(
      method = "spread(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void tellus$containWaterfallCurtain(
      ServerLevel level,
      BlockPos pos,
      BlockState state,
      FluidState fluid,
      CallbackInfo ci
   ) {
      if (isCurtainCandidate(state, fluid)
         && level.getChunkSource().getGenerator()
            instanceof EarthChunkGenerator generator) {
         EarthChunkGenerator.WaterfallFluidPolicy policy =
            generator.waterfallFluidPolicy(
               pos.getX(), pos.getY(), pos.getZ()
            );
         if (!policy.resolved()) {
            level.scheduleTick(
               pos, fluid.getType(), CURTAIN_LOOKUP_RETRY_TICKS
            );
         }
         if (!policy.resolved() || policy.preserveCurtain()) {
            ci.cancel();
         }
      }
   }

   private static boolean isCurtainCandidate(
      BlockState state, FluidState fluid
   ) {
      return !fluid.isSource()
         && fluid.getType().isSame(Fluids.WATER)
         && state.hasProperty(LiquidBlock.LEVEL)
         && state.getValue(LiquidBlock.LEVEL) == 8;
   }
}
