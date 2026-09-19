package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.VeryBiasedToBottomInt;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;

public final class TellusCaveCarver {
   private final WorldCarver carver;

   public TellusCaveCarver(
      float probability,
      HeightProvider y,
      FloatProvider roomVerticalRadiusMultiplier,
      FloatProvider horizontalRadiusMultiplier,
      FloatProvider verticalRadiusMultiplier,
      FloatProvider floorLevel
   ) {
      // Count, thickness and its bias are vanilla's Overworld cave values, which 26.2 hard-coded in the carver.
      this.carver = new CaveWorldCarver(
         probability,
         y,
         VeryBiasedToBottomInt.of(0, 14),
         TrapezoidFloat.of(0.0F, 3.0F, 1.0F),
         true,
         roomVerticalRadiusMultiplier,
         horizontalRadiusMultiplier,
         verticalRadiusMultiplier,
         ConstantFloat.of(1.0F),
         floorLevel
      );
   }

   WorldCarver carver() {
      return this.carver;
   }
}