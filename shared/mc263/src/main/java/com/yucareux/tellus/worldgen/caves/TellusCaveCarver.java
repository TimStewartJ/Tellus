package com.yucareux.tellus.worldgen.caves;

import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class TellusCaveCarver {
   private final CaveWorldCarver configured;

   public TellusCaveCarver(CaveWorldCarver configuration) {
      this.configured = configuration;
   }

   CaveWorldCarver configured() {
      return this.configured;
   }
}
