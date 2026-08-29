package com.yucareux.tellus.worldgen;

import java.util.Objects;
import java.util.function.IntBinaryOperator;

/**
 * Shared per-chunk surface summaries used by underground generation.
 */
public final class UndergroundSurfaceGrid {
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;

   private UndergroundSurfaceGrid() {
   }

   /**
    * Samples the chunk plus {@code radius} blocks of padding once, then resolves the minimum
    * surface in each column's square neighborhood.
    */
   public static int[] minimumNearbySurfaceYByColumn(
      IntBinaryOperator surfaceHeightSampler, int chunkMinX, int chunkMinZ, int radius
   ) {
      Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      if (radius < 0) {
         throw new IllegalArgumentException("Surface grid radius must be non-negative");
      }

      int sampleSide = Math.addExact(CHUNK_SIDE, Math.multiplyExact(radius, 2));
      int[] sampledSurfaceY = new int[Math.multiplyExact(sampleSide, sampleSide)];
      for (int sampleZ = 0; sampleZ < sampleSide; sampleZ++) {
         for (int sampleX = 0; sampleX < sampleSide; sampleX++) {
            sampledSurfaceY[sampleZ * sampleSide + sampleX] = surfaceHeightSampler.applyAsInt(
               chunkMinX + sampleX - radius, chunkMinZ + sampleZ - radius
            );
         }
      }

      int[] minimumSurfaceYByColumn = new int[CHUNK_AREA];
      int diameter = radius * 2;
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int minimumSurfaceY = Integer.MAX_VALUE;
            for (int dz = 0; dz <= diameter; dz++) {
               int sampleRow = (localZ + dz) * sampleSide + localX;
               for (int dx = 0; dx <= diameter; dx++) {
                  minimumSurfaceY = Math.min(minimumSurfaceY, sampledSurfaceY[sampleRow + dx]);
               }
            }
            minimumSurfaceYByColumn[localZ * CHUNK_SIDE + localX] = minimumSurfaceY;
         }
      }
      return minimumSurfaceYByColumn;
   }

   /**
    * Computes the first usable Y above the highest bedrock block encountered by the placement
    * mixin's historical top-down scan. A null skin array represents a solid (non-shell) world.
    */
   public static int[] usableBottomYByColumn(
      int[] terrainSurfaces,
      int[] bedrockSkinTopYs,
      int undergroundDepth,
      int minimumY
   ) {
      if (terrainSurfaces.length != CHUNK_AREA) {
         throw new IllegalArgumentException("Terrain surface grid must contain 256 columns");
      }
      if (bedrockSkinTopYs != null && bedrockSkinTopYs.length != CHUNK_AREA) {
         throw new IllegalArgumentException("Bedrock skin grid must contain 256 columns");
      }

      int[] usableBottomYs = new int[CHUNK_AREA];
      for (int index = 0; index < CHUNK_AREA; index++) {
         int surfaceY = terrainSurfaces[index];
         int searchBottomY = UndergroundGenerationDepthPolicy.deepestGenerationY(
            surfaceY, undergroundDepth, minimumY
         );
         if (bedrockSkinTopYs == null) {
            usableBottomYs[index] = searchBottomY;
            continue;
         }

         int fillTopY = surfaceY - 1;
         int supportBottomY = TerrainShellBedrockProtection.supportBottomY(
            surfaceY, undergroundDepth, minimumY
         );
         int baseBedrockY = Math.min(supportBottomY, fillTopY);
         int highestBedrockY = Math.max(baseBedrockY, Math.min(fillTopY, bedrockSkinTopYs[index]));
         usableBottomYs[index] = highestBedrockY >= searchBottomY
            ? highestBedrockY + 1
            : searchBottomY;
      }
      return usableBottomYs;
   }

   public static int columnValue(int[] values, int worldX, int worldZ) {
      if (values == null || values.length != CHUNK_AREA) {
         return Integer.MIN_VALUE;
      }
      return values[(worldZ & 15) * CHUNK_SIDE + (worldX & 15)];
   }
}
