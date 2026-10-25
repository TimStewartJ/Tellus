package com.yucareux.tellus.worldgen.caves;

import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class TellusRavineCarver {
   private final CanyonWorldCarver configured;

   public TellusRavineCarver(CanyonWorldCarver configuration) {
      this.configured = configuration;
   }

   CanyonWorldCarver configured() {
      return this.configured;
   }
}
