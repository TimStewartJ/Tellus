package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.SurfacePatchNoise;

/**
 * Slope and convexity of Distant Horizons LOD cells, on the scale real chunks use.
 *
 * <p>Real chunks measure the steepest rise over four blocks on full-resolution
 * heights. A coarse LOD cell can only compare its centre with its neighbours on
 * elevation data as coarse as the cell, which smooths steep ground away: at 1:30,
 * cells of 32 blocks see a tenth of the 27-degree ground real chunks have.
 * Scaling that rise back up by the cell width instead turns whole mountainsides
 * into cliffs. Each cell therefore draws its column's metrics from the
 * distribution real chunks show inside such a cell, measured with
 * {@code tools/lod-scan} over fjords, high mountains, hills and plains.</p>
 *
 * <p>The column slope follows {@code P(S >= t) = 1 / (1 + (t / mu)^(1 / spread))},
 * where {@code mu} grows with the cell's own slope and, through the relief of the
 * surrounding cells, with how rugged the area is, so flat land stays flat. The
 * convexity follows the real chunks' distribution for that slope, nudged by the
 * cell's own convexity. Both rolls are clustered, so steep ground forms patches.
 * Cells as fine as the elevation data keep the exact four-block metrics.</p>
 */
public final class LodTerrainMetrics {
   static final int BASELINE_BLOCKS = 4;
   // Coarsest native DEM spacing, below which finer cells add no smoothing.
   private static final double NATIVE_DEM_METERS = 30.0;
   private static final double EXACT_SCALE_LOG2 = 0.05;
   // Scale of the data the fit covers; coarser cells reuse its constants.
   private static final double MAX_SCALE_LOG2 = 6.5;
   private static final double RELIEF_MEAN_BASE = 0.758;
   private static final double RELIEF_MEAN_GROWTH = 1.266;
   private static final double RELIEF_HALF_SATURATION = 0.189;
   private static final double CELL_SLOPE_WEIGHT = 0.887;
   private static final double SPREAD_BASE = 0.127;
   private static final double SPREAD_GROWTH = 0.0289;
   private static final double SPREAD_MAX = 0.294;
   private static final double CONVEXITY_SHIFT = 1.9;
   private static final double CONVEXITY_SHIFT_DECAY = 0.6;
   private static final int MAX_SLOPE = 64;
   private static final int MIN_CONVEXITY = -4;
   private static final double MIN_ROLL = 1.0E-6;
   private static final long SLOPE_SALT = 7310598723401582113L;
   private static final long CONVEXITY_SALT = -2617389475620291457L;
   // Real chunks' P(convexity <= v | slope) for v = -4..2, slopes 0..8 (8 = steeper).
   private static final float[][] CONVEXITY_CDF = {
      {0.0000F, 0.0000F, 0.0000F, 0.0000F, 1.0000F, 1.0000F, 1.0000F},
      {0.0000F, 0.0000F, 0.0000F, 0.3988F, 0.9973F, 1.0000F, 1.0000F},
      {0.0000F, 0.0000F, 0.0095F, 0.3983F, 0.9650F, 1.0000F, 1.0000F},
      {0.0000F, 0.0009F, 0.0436F, 0.4282F, 0.9256F, 0.9989F, 1.0000F},
      {0.0002F, 0.0083F, 0.1011F, 0.4553F, 0.8712F, 0.9939F, 0.9999F},
      {0.0018F, 0.0238F, 0.1671F, 0.4732F, 0.8091F, 0.9852F, 0.9990F},
      {0.0061F, 0.0497F, 0.2321F, 0.4873F, 0.7396F, 0.9648F, 0.9966F},
      {0.0161F, 0.0883F, 0.2818F, 0.4876F, 0.6809F, 0.9257F, 0.9917F},
      {0.0730F, 0.1946F, 0.3699F, 0.4999F, 0.6208F, 0.8144F, 0.9441F}
   };
   private static final LodTerrainMetrics.Level EXACT = new LodTerrainMetrics.Level(true, 0.0, 0.0, 0.0);

   private LodTerrainMetrics() {
   }

   /** Constants for cells of {@code cellSize} blocks in a world of {@code worldScale} metres per block. */
   public static LodTerrainMetrics.Level level(double worldScale, int cellSize) {
      if (!(worldScale > 0.0) || cellSize <= 1) {
         return EXACT;
      }

      double chunkScale = Math.hypot(BASELINE_BLOCKS * worldScale, Math.max(worldScale, NATIVE_DEM_METERS));
      double cellScale = Math.hypot(Math.max(BASELINE_BLOCKS, cellSize) * worldScale, Math.max(cellSize * worldScale, NATIVE_DEM_METERS));
      double scaleLog2 = Math.min(MAX_SCALE_LOG2, Math.log(cellScale / chunkScale) / Math.log(2.0));
      if (!(scaleLog2 > EXACT_SCALE_LOG2)) {
         return EXACT;
      }

      return new LodTerrainMetrics.Level(
         false,
         RELIEF_MEAN_BASE * Math.pow(RELIEF_MEAN_GROWTH, scaleLog2),
         Math.min(SPREAD_MAX, SPREAD_BASE + SPREAD_GROWTH * scaleLog2),
         CONVEXITY_SHIFT * Math.exp(-CONVEXITY_SHIFT_DECAY * scaleLog2)
      );
   }

   /** Neighbour distance in cells, so the metric spans at least the four-block baseline. */
   public static int reach(int cellSize) {
      return cellSize >= BASELINE_BLOCKS ? 1 : Math.max(1, BASELINE_BLOCKS / Math.max(1, cellSize));
   }

   /**
    * Fills the metrics of a square LOD grid of {@code size} cells from heights that extend
    * {@link #reach} cells past it on every side, so edge cells see their true neighbours.
    */
   public static void fill(
      int[] heights,
      int size,
      int cellSize,
      LodTerrainMetrics.Level level,
      int[] worldXs,
      int[] worldZs,
      int[] slopes,
      int[] convexities
   ) {
      int reach = reach(cellSize);
      int width = size + 2 * reach;
      int span = reach * Math.max(1, cellSize);
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            int center = (z + reach) * width + x + reach;
            int height = heights[center];
            int east = heights[center + reach];
            int west = heights[center - reach];
            int north = heights[center - reach * width];
            int south = heights[center + reach * width];
            int index = z * size + x;
            int maxDiff = Math.max(Math.max(Math.abs(east - height), Math.abs(west - height)), Math.max(Math.abs(north - height), Math.abs(south - height)));
            if (level.exact()) {
               slopes[index] = maxDiff * BASELINE_BLOCKS / span;
               convexities[index] = ((east + west + north + south) / 4 - height) * BASELINE_BLOCKS / span;
               continue;
            }

            int low = Math.min(height, Math.min(Math.min(east, west), Math.min(north, south)));
            int high = Math.max(height, Math.max(Math.max(east, west), Math.max(north, south)));
            for (int corner = 0; corner < 4; corner++) {
               int cornerHeight = heights[center + ((corner & 1) == 0 ? -reach : reach) + ((corner & 2) == 0 ? -reach : reach) * width];
               low = Math.min(low, cornerHeight);
               high = Math.max(high, cornerHeight);
            }

            double cellSlope = maxDiff * (double)BASELINE_BLOCKS / span;
            double cellConvexity = ((east + west + north + south) * 0.25 - height) * BASELINE_BLOCKS / span;
            double relief = (high - low) * 2.0 / span;
            int slope = drawSlope(level, cellSlope, relief, worldXs[x], worldZs[z]);
            slopes[index] = slope;
            convexities[index] = drawConvexity(level, slope, cellConvexity, worldXs[x], worldZs[z]);
         }
      }
   }

   static int drawSlope(LodTerrainMetrics.Level level, double cellSlope, double relief, int worldX, int worldZ) {
      double mean = level.reliefMean() * relief / (relief + RELIEF_HALF_SATURATION) + CELL_SLOPE_WEIGHT * cellSlope;
      if (!(mean > 0.0)) {
         return 0;
      }

      double roll = roll(worldX, worldZ, SLOPE_SALT);
      double slope = mean * Math.pow(roll / (1.0 - roll), level.spread());
      return (int)Math.min(MAX_SLOPE, Math.floor(slope));
   }

   static int drawConvexity(LodTerrainMetrics.Level level, int slope, double cellConvexity, int worldX, int worldZ) {
      double roll = roll(worldX, worldZ, CONVEXITY_SALT);
      if (level.convexityShift() != 0.0 && cellConvexity != 0.0) {
         // A concave cell holds more concave columns, a convex one more convex columns.
         roll = 1.0 / (1.0 + Math.exp(-(Math.log(roll / (1.0 - roll)) + level.convexityShift() * cellConvexity)));
      }

      float[] cumulative = CONVEXITY_CDF[Math.min(CONVEXITY_CDF.length - 1, Math.max(0, slope))];
      for (int i = 0; i < cumulative.length; i++) {
         if (roll <= cumulative[i]) {
            return MIN_CONVEXITY + i;
         }
      }
      return MIN_CONVEXITY + cumulative.length;
   }

   /** The roll a column's slope is drawn with, shared with {@link LodSnowSlope} so steep draws agree. */
   static double slopeRoll(int worldX, int worldZ) {
      return roll(worldX, worldZ, SLOPE_SALT);
   }

   private static double roll(int worldX, int worldZ, long salt) {
      return Math.max(MIN_ROLL, Math.min(1.0 - MIN_ROLL, SurfacePatchNoise.uniform(worldX, worldZ, salt)));
   }

   /** Constants of one cell size: exact four-block metrics, or the fitted distribution. */
   public record Level(boolean exact, double reliefMean, double spread, double convexityShift) {
   }
}
