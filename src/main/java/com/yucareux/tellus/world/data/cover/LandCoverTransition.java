package com.yucareux.tellus.world.data.cover;

import com.yucareux.tellus.world.data.CategoricalTransition;

/**
 * Turns categorical raster interpolation weights into stable, organic-looking
 * land-cover boundaries.
 *
 * <p>Land-cover classes cannot be numerically interpolated like elevation. The
 * four neighboring classes are therefore blended spatially: their bilinear
 * weights select which class owns each block. A continuous, absolute-coordinate
 * noise field keeps the result clustered instead of producing checkerboard
 * dithering, and makes the result independent of chunk generation order.</p>
 */
public final class LandCoverTransition {
   private static final int NO_DATA_CLASS = 0;
   private static final int WATER_CLASS = 80;
   private static final int MANGROVES_CLASS = 95;
   private static final CategoricalTransition.NoiseProfile TRANSITION_PROFILE = new CategoricalTransition.NoiseProfile(
      0.45,
      2.0,
      12.0,
      0.47,
      0.72,
      0.28,
      1.45,
      0.12,
      9154887495218319081L,
      5883890050026909207L,
      2611923443488327891L
   );

   private LandCoverTransition() {
   }

   /**
    * Smoothly enables transitions only when the generated result is finer than
    * the source raster. A 1 m output receives the full treatment for 10 m
    * WorldCover data, while output at the source resolution remains exact.
    */
   public static double strength(double sourceResolutionMeters, double effectiveResolutionMeters) {
      if (!(Double.isFinite(sourceResolutionMeters) && sourceResolutionMeters > 1.0)
         || !(Double.isFinite(effectiveResolutionMeters) && effectiveResolutionMeters > 0.0)
         || effectiveResolutionMeters >= sourceResolutionMeters) {
         return 0.0;
      }

      double linear = clamp(
         (sourceResolutionMeters - effectiveResolutionMeters) / (sourceResolutionMeters - 1.0),
         0.0,
         1.0
      );
      return linear * linear * (3.0 - 2.0 * linear);
   }

   /**
    * Selects a visual class from a center sample and the four samples around
    * the center of the categorical pixel grid.
    */
   public static int selectVisualClass(
      int centerClass,
      int class00,
      int class10,
      int class01,
      int class11,
      double fractionX,
      double fractionZ,
      double transitionStrength,
      double blockX,
      double blockZ,
      double sourceCellBlocks
   ) {
      if (!(transitionStrength > 0.0)
         || !isBlendableClass(centerClass)
         || isProtectedClass(centerClass)) {
         return centerClass;
      }
      class00 = visualCandidateOrCenter(centerClass, class00);
      class10 = visualCandidateOrCenter(centerClass, class10);
      class01 = visualCandidateOrCenter(centerClass, class01);
      class11 = visualCandidateOrCenter(centerClass, class11);
      if (centerClass == class00
         && centerClass == class10
         && centerClass == class01
         && centerClass == class11) {
         return centerClass;
      }

      return CategoricalTransition.selectBilinear(
         centerClass,
         class00,
         class10,
         class01,
         class11,
         fractionX,
         fractionZ,
         transitionStrength,
         blockX,
         blockZ,
         sourceCellBlocks,
         0,
         255,
         TRANSITION_PROFILE
      );
   }

   public static boolean isProtectedClass(int coverClass) {
      return coverClass == NO_DATA_CLASS
         || coverClass == WATER_CLASS
         || coverClass == MANGROVES_CLASS;
   }

   private static int visualCandidateOrCenter(int centerClass, int candidateClass) {
      return isBlendableClass(candidateClass) && !isProtectedClass(candidateClass)
         ? candidateClass
         : centerClass;
   }

   private static boolean isBlendableClass(int coverClass) {
      return coverClass >= 0 && coverClass <= 255;
   }

   private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }
}
