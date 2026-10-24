package com.yucareux.tellus.worldgen;

/**
 * Shared DEM slope sampling rules for terrain materials.
 */
public final class TerrainSlopePolicy {
   /**
    * The preview grid is sampled every two blocks. Keeping full terrain on the
    * same radius prevents the material decision from changing between the two.
    */
   public static final int SAMPLE_RADIUS_BLOCKS = 2;

   private TerrainSlopePolicy() {
   }

   public static double localSlopeDegrees(
      double centerElevationMeters,
      double eastElevationMeters,
      double westElevationMeters,
      double northElevationMeters,
      double southElevationMeters,
      double eastWestSampleDistanceMeters,
      double northSouthSampleDistanceMeters
   ) {
      if (!Double.isFinite(centerElevationMeters)
         || !(eastWestSampleDistanceMeters > 0.0)
         || !(northSouthSampleDistanceMeters > 0.0)) {
         return Double.NaN;
      }

      double gradientX = strongestOneSidedGradient(
         centerElevationMeters, eastElevationMeters, westElevationMeters, eastWestSampleDistanceMeters
      );
      double gradientZ = strongestOneSidedGradient(
         centerElevationMeters, southElevationMeters, northElevationMeters, northSouthSampleDistanceMeters
      );
      if (!Double.isFinite(gradientX) && !Double.isFinite(gradientZ)) {
         return Double.NaN;
      }

      double resolvedGradientX = Double.isFinite(gradientX) ? gradientX : 0.0;
      double resolvedGradientZ = Double.isFinite(gradientZ) ? gradientZ : 0.0;
      return Math.toDegrees(Math.atan(Math.hypot(resolvedGradientX, resolvedGradientZ)));
   }

   /** {@link #localSlopeDegrees} together with the way the ground falls, from the same samples. */
   public static TerrainSlopePolicy.Gradient localGradient(
      double centerElevationMeters,
      double eastElevationMeters,
      double westElevationMeters,
      double northElevationMeters,
      double southElevationMeters,
      double eastWestSampleDistanceMeters,
      double northSouthSampleDistanceMeters
   ) {
      double slopeDegrees = localSlopeDegrees(
         centerElevationMeters,
         eastElevationMeters,
         westElevationMeters,
         northElevationMeters,
         southElevationMeters,
         eastWestSampleDistanceMeters,
         northSouthSampleDistanceMeters
      );
      if (!Double.isFinite(slopeDegrees)) {
         return TerrainSlopePolicy.Gradient.MISSING;
      }

      return new TerrainSlopePolicy.Gradient(
         centerElevationMeters,
         slopeDegrees,
         signedGradient(centerElevationMeters, eastElevationMeters, westElevationMeters, eastWestSampleDistanceMeters),
         signedGradient(centerElevationMeters, southElevationMeters, northElevationMeters, northSouthSampleDistanceMeters)
      );
   }

   /** Rise per metre toward the positive axis: central where both neighbours exist, one-sided otherwise. */
   private static double signedGradient(
      double centerElevationMeters,
      double positiveElevationMeters,
      double negativeElevationMeters,
      double sampleDistanceMeters
   ) {
      boolean positive = Double.isFinite(positiveElevationMeters);
      boolean negative = Double.isFinite(negativeElevationMeters);
      if (positive && negative) {
         return (positiveElevationMeters - negativeElevationMeters) / (2.0 * sampleDistanceMeters);
      } else if (positive) {
         return (positiveElevationMeters - centerElevationMeters) / sampleDistanceMeters;
      } else {
         return negative ? (centerElevationMeters - negativeElevationMeters) / sampleDistanceMeters : 0.0;
      }
   }

   private static double strongestOneSidedGradient(
      double centerElevationMeters,
      double positiveElevationMeters,
      double negativeElevationMeters,
      double sampleDistanceMeters
   ) {
      double strongest = Double.NaN;
      if (Double.isFinite(positiveElevationMeters)) {
         strongest = Math.abs(positiveElevationMeters - centerElevationMeters) / sampleDistanceMeters;
      }
      if (Double.isFinite(negativeElevationMeters)) {
         double negativeGradient = Math.abs(centerElevationMeters - negativeElevationMeters) / sampleDistanceMeters;
         strongest = Double.isFinite(strongest) ? Math.max(strongest, negativeGradient) : negativeGradient;
      }
      return strongest;
   }

   /**
    * The centre sample, the slope in degrees, and the ground's rise per unit of distance toward the
    * east and toward the south (block +X and +Z), in the samples' units: metres for DEM samples. The
    * slope is {@link #localSlopeDegrees}; the rises give its direction.
    */
   public record Gradient(double centerElevation, double slopeDegrees, double riseEast, double riseSouth) {
      public static final TerrainSlopePolicy.Gradient MISSING = new TerrainSlopePolicy.Gradient(Double.NaN, Double.NaN, 0.0, 0.0);

      /**
       * How squarely the ground faces the nearer pole at {@code latitude}: 1 facing it,
       * -1 facing the equator, 0 facing east or west, flat, or on the equator.
       */
      public double polewardFacing(double latitude) {
         double magnitude = Math.hypot(this.riseEast, this.riseSouth);
         if (!(magnitude > 0.0) || !Double.isFinite(latitude)) {
            return 0.0;
         }

         // Ground that rises toward the equator falls toward, and so faces, the pole.
         return Math.signum(latitude) * this.riseSouth / magnitude;
      }
   }

   /** A point's gradient and the height range around it, per unit of the span between opposite neighbours. */
   public record Neighbourhood(TerrainSlopePolicy.Gradient gradient, double relief) {
      public static final TerrainSlopePolicy.Neighbourhood MISSING = new TerrainSlopePolicy.Neighbourhood(
         TerrainSlopePolicy.Gradient.MISSING, Double.NaN
      );
   }
}
