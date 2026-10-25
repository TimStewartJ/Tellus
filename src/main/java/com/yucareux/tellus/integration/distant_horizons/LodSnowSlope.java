package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.TerrainSlopePolicy;

/**
 * The DEM slope the snow rule reads for a Distant Horizons LOD column, on the scale real chunks read it.
 *
 * <p>Real chunks thin mapped snow on faces steeper than 45 degrees, measured two blocks either side on
 * full-resolution elevation data. A coarse LOD cell can only read its slope on data as coarse as the
 * cell, which smooths those faces away: at 1:30, cells of 32 blocks and more read none of the 15% of
 * Alpine snow that lies on them, so distant snow stayed whiter than the chunks that replace it. Each
 * column therefore draws its slope from the slopes real chunks show for snow inside such a cell,
 * measured with {@code tools/lod-scan/run-snow-scan.sh} over the Alps, Everest, Geiranger, Denali, the
 * Patagonian ice field, Vatnajokull and the Greenland ice sheet.</p>
 *
 * <p>The tangent of the column's slope is log-logistic around a median that grows with the cell's own
 * slope, and with how far it stands above the local treeline where its surroundings have any relief:
 * ice caps sit low and smooth, the steep faces cells hide stand high among peaks, and an ice sheet,
 * however high, has no relief. The roll is the one {@link LodTerrainMetrics} draws the column's rock
 * slope with, so a column drawn steep both sheds its snow and shows rock. Cells as fine as the data
 * keep the slope they read.</p>
 */
public final class LodSnowSlope {
   // Coarsest native DEM spacing, below which finer cells add no smoothing.
   private static final double NATIVE_DEM_METERS = 30.0;
   private static final double EXACT_SCALE_LOG2 = 0.05;
   // Scale of the coarsest cells the fit covers; coarser cells reuse its constants.
   private static final double MAX_SCALE_LOG2 = 7.0;
   private static final double CELL_SLOPE_WEIGHT = 0.8198;
   // Hidden steepness per kilometre above the treeline, the first kilometre and then growing as cells coarsen.
   private static final double TREELINE_HEIGHT_BASE = 0.1104;
   private static final double TREELINE_HEIGHT_GROWTH = 0.0188;
   private static final double MAX_KILOMETRES_ABOVE_TREELINE = 4.0;
   private static final double RELIEF_HALF_SATURATION = 0.0303;
   // Further steepness per kilometre above the treeline on cells that slope at all.
   private static final double SLOPED_HEIGHT_WEIGHT = 0.1273;
   private static final double SLOPED_HEIGHT_SCALE_LOG2 = 5.124;
   private static final double SLOPE_HALF_SATURATION = 0.0414;
   private static final double SPREAD_BASE = 0.1220;
   private static final double SPREAD_GROWTH = 0.0586;
   private static final double SPREAD_MAX = 0.5323;
   private static final double MAX_CELL_SLOPE_DEGREES = 89.0;
   // -Dtellus.lodSnowSlope=false makes every LOD column read its own cell's slope again.
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.lodSnowSlope"));
   private static final LodSnowSlope.Level EXACT = new LodSnowSlope.Level(true, 0.0, 0.0, 0.0);

   private LodSnowSlope() {
   }

   /** Constants for LOD columns that read the DEM at {@code resolutionMeters} in a world of {@code worldScale} metres per block. */
   public static LodSnowSlope.Level level(double worldScale, double resolutionMeters) {
      if (!ENABLED || !(worldScale > 0.0) || !(resolutionMeters > worldScale)) {
         return EXACT;
      }

      double chunkBaseline = Math.max(TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS * worldScale, NATIVE_DEM_METERS);
      double cellBaseline = Math.max(TerrainSlopePolicy.SAMPLE_RADIUS_BLOCKS * worldScale, resolutionMeters);
      double scaleLog2 = Math.min(MAX_SCALE_LOG2, Math.log(cellBaseline / chunkBaseline) / Math.log(2.0));
      if (!(scaleLog2 > EXACT_SCALE_LOG2)) {
         return EXACT;
      }

      return new LodSnowSlope.Level(
         false,
         TREELINE_HEIGHT_GROWTH * scaleLog2,
         SLOPED_HEIGHT_WEIGHT * (1.0 - Math.exp(-scaleLog2 / SLOPED_HEIGHT_SCALE_LOG2)),
         Math.min(SPREAD_MAX, SPREAD_BASE + SPREAD_GROWTH * scaleLog2)
      );
   }

   /**
    * Slope in degrees for the column at {@code worldX, worldZ}, from the slope its cell reads, the
    * height range around the cell per metre ({@link TerrainSlopePolicy.Neighbourhood#relief}) and its
    * height above the local treeline. Exact levels and missing slopes return the cell's slope.
    */
   public static double drawSlopeDegrees(
      LodSnowSlope.Level level, double cellSlopeDegrees, double cellRelief, double metersAboveTreeline, int worldX, int worldZ
   ) {
      if (level.exact() || !Double.isFinite(cellSlopeDegrees)) {
         return cellSlopeDegrees;
      }

      double kilometres = Double.isFinite(metersAboveTreeline)
         ? Math.max(0.0, Math.min(MAX_KILOMETRES_ABOVE_TREELINE, metersAboveTreeline / 1000.0))
         : 0.0;
      double cellRise = Math.tan(Math.toRadians(Math.min(MAX_CELL_SLOPE_DEGREES, Math.max(0.0, cellSlopeDegrees))));
      double reliefShare = Double.isFinite(cellRelief) && cellRelief > 0.0 ? cellRelief / (cellRelief + RELIEF_HALF_SATURATION) : 0.0;
      double median = CELL_SLOPE_WEIGHT * cellRise
         + (TREELINE_HEIGHT_BASE * Math.min(1.0, kilometres) + level.treelineHeightGrowth() * kilometres) * reliefShare
         + level.slopedHeightWeight() * kilometres * cellRise / (cellRise + SLOPE_HALF_SATURATION);
      if (!(median > 0.0)) {
         return 0.0;
      }

      double roll = LodTerrainMetrics.slopeRoll(worldX, worldZ);
      return Math.toDegrees(Math.atan(median * Math.pow(roll / (1.0 - roll), level.spread())));
   }

   /** Constants of one resolution: the cell's own slope, or the fitted distribution. */
   public record Level(boolean exact, double treelineHeightGrowth, double slopedHeightWeight, double spread) {
   }
}
