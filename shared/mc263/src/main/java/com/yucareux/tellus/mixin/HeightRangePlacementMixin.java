package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.TellusHeightRangePlacement;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeightRangePlacement.class)
public abstract class HeightRangePlacementMixin {
   @Shadow @Final private HeightProvider height;

   @Inject(method = "modify", at = @At("HEAD"), cancellable = true)
   private void tellus$surfaceRelativeUndergroundHeight(
      PlacementContext context, RandomSource random, BlockPos origin,
      Consumer<BlockPos> output, CallbackInfo callback
   ) {
      Stream<BlockPos> positions = TellusHeightRangePlacement.positions(this.height, context, random, origin);
      if (positions != null) {
         positions.forEach(output);
         callback.cancel();
      }
   }
}
