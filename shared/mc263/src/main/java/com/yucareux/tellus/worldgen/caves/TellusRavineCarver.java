package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;

public final class TellusRavineCarver {
   private final WorldCarver carver;

   public TellusRavineCarver(
      float probability,
      HeightProvider y,
      FloatProvider verticalRotation,
      CanyonWorldCarver.Shape shape
   ) {
      this.carver = new CanyonWorldCarver(probability, y, verticalRotation, shape);
   }

   WorldCarver carver() {
      return this.carver;
   }
}
