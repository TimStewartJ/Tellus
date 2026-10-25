package com.yucareux.tellus.worldgen;

public final class SnowSlopePolicy {
   /**
    * Shaded faces hold snow this many degrees steeper, and sunny faces shed it this much sooner.
    * Mapped snow already favours shaded faces; this carries that onto the steep faces the bands thin.
    */
   static final double ASPECT_SHIFT_DEGREES = 8.0;
   // Near the tropics the summer sun stands almost overhead, so no face stays in shade: the
   // Himalayan snow map shows no north-south difference at 28 degrees, the Alps a clear one at 46.
   static final double ASPECT_START_LATITUDE = 25.0;
   static final double ASPECT_FULL_LATITUDE = 45.0;
   private static final long COVERAGE_SALT = 7640891576956012809L;

   private SnowSlopePolicy() {
   }

   /** Share of {@link #ASPECT_SHIFT_DEGREES} that applies at this latitude. */
   public static double aspectStrength(double latitude) {
      double ramp = (Math.abs(latitude) - ASPECT_START_LATITUDE) / (ASPECT_FULL_LATITUDE - ASPECT_START_LATITUDE);
      return Double.isFinite(ramp) ? Math.max(0.0, Math.min(1.0, ramp)) : 0.0;
   }

   /**
    * The slope the coverage bands read for ground facing this way: gentler on faces turned to the
    * pole, steeper on faces turned to the equator. {@code polewardFacing} runs from -1 to 1.
    */
   public static double effectiveSlopeDegrees(double slopeDegrees, double polewardFacing, double latitude) {
      if (!Double.isFinite(slopeDegrees)) {
         return slopeDegrees;
      }

      return slopeDegrees - ASPECT_SHIFT_DEGREES * polewardFacing * aspectStrength(latitude);
   }

   /** Effective slope of a sampled gradient at this latitude. */
   public static double effectiveSlopeDegrees(TerrainSlopePolicy.Gradient gradient, double latitude) {
      return effectiveSlopeDegrees(gradient.slopeDegrees(), gradient.polewardFacing(latitude), latitude);
   }

   public static int coveragePercent(double slopeDegrees) {
      if (Double.isNaN(slopeDegrees) || slopeDegrees < 45.0) {
         return 100;
      } else if (slopeDegrees < 50.0) {
         return 75;
      } else if (slopeDegrees < 55.0) {
         return 40;
      } else if (slopeDegrees < 60.0) {
         return 10;
      } else {
         return 0;
      }
   }

   public static boolean hasFullCoverage(double slopeDegrees) {
      return coveragePercent(slopeDegrees) == 100;
   }

   /** Partial coverage on steep faces keeps snow in connected patches rather than single blocks. */
   public static boolean shouldCover(int worldX, int worldZ, double slopeDegrees) {
      return SurfacePatchNoise.covers(worldX, worldZ, COVERAGE_SALT, coveragePercent(slopeDegrees));
   }

   public static double slopeDegrees(
      double eastElevationMeters,
      double westElevationMeters,
      double northElevationMeters,
      double southElevationMeters,
      double eastWestDistanceMeters,
      double northSouthDistanceMeters
   ) {
      if (!Double.isFinite(eastElevationMeters)
         || !Double.isFinite(westElevationMeters)
         || !Double.isFinite(northElevationMeters)
         || !Double.isFinite(southElevationMeters)
         || !(eastWestDistanceMeters > 0.0)
         || !(northSouthDistanceMeters > 0.0)) {
         return Double.NaN;
      }

      double gradientX = (eastElevationMeters - westElevationMeters) / eastWestDistanceMeters;
      double gradientZ = (southElevationMeters - northElevationMeters) / northSouthDistanceMeters;
      return Math.toDegrees(Math.atan(Math.hypot(gradientX, gradientZ)));
   }
}
