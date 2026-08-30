package com.yucareux.tellus.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class VegetationVersionCompat {
   private VegetationVersionCompat() {
   }

   public static Block shortGrass() {
      return Blocks.SHORT_GRASS;
   }

   public static Block leafLitterOr(Block fallback) {
      return fallback;
   }
}
