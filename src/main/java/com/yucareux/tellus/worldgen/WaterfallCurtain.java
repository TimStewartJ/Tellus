package com.yucareux.tellus.worldgen;

import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded, non-ticking full-detail presentation for planned waterfall drop columns. */
final class WaterfallCurtain {
   static final int NONE = Integer.MIN_VALUE;
   private static final BlockState FALLING_WATER = Blocks.WATER.defaultBlockState()
      .setValue(LiquidBlock.LEVEL, 8);

   private WaterfallCurtain() {
   }

   static BlockState blockState() {
      return FALLING_WATER;
   }

   static boolean contains(
      int blockY, WaterSurfaceResolver.WaterfallInfo waterfall
   ) {
      return waterfall.waterfall()
         && blockY > waterfall.terrainSurface()
         && blockY <= waterfall.upstreamSurface();
   }

   static int topY(
      WaterSurfaceResolver.WaterChunkData water,
      int localX,
      int localZ,
      int terrainSurface,
      int maximumY
   ) {
      if (!water.isWaterfallDrop(localX, localZ)) {
         return NONE;
      }
      if (terrainSurface != water.terrainSurface(localX, localZ)) {
         return NONE;
      }
      int top = Math.min(maximumY, water.waterSurface(localX, localZ));
      return top > terrainSurface ? top : NONE;
   }

   static int[] tops(
      WaterSurfaceResolver.WaterChunkData water,
      int[] terrainSurfaces,
      int maximumY
   ) {
      if (terrainSurfaces.length != 256) {
         throw new IllegalArgumentException(
            "Waterfall terrain snapshot must contain exactly 256 columns"
         );
      }
      int[] result = new int[terrainSurfaces.length];
      Arrays.fill(result, NONE);
      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localX = 0; localX < 16; localX++) {
            int index = localZ * 16 + localX;
            result[index] = topY(
               water,
               localX,
               localZ,
               terrainSurfaces[index],
               maximumY
            );
         }
      }
      return result;
   }
}
