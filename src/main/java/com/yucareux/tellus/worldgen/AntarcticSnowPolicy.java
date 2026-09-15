package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;

/**
 * Supplies snow presentation for Antarctic land where ESA WorldCover has no coverage.
 *
 * <p>This policy must be applied only after water classification. The raw no-data
 * land-cover class remains unchanged so ocean detection can still distinguish
 * Antarctic land from the surrounding ocean.</p>
 */
public final class AntarcticSnowPolicy {
   private static final AntarcticSnowPolicy DISABLED = new AntarcticSnowPolicy(Double.POSITIVE_INFINITY);

   private final double southernCoverageBoundaryZ;

   private AntarcticSnowPolicy(double southernCoverageBoundaryZ) {
      this.southernCoverageBoundaryZ = southernCoverageBoundaryZ;
   }

   public static AntarcticSnowPolicy forProjection(WorldProjection projection) {
      if (!(projection.worldScale() > 0.0)) {
         return DISABLED;
      }

      return new AntarcticSnowPolicy(projection.latToBlockZ(TellusLandCoverSource.WORLD_COVER_MIN_LATITUDE));
   }

   public boolean shouldUseSnowFallback(int rawCoverClass, double blockZ) {
      return rawCoverClass == TellusLandCoverSource.NO_DATA_CLASS
         && Double.isFinite(blockZ)
         && blockZ > this.southernCoverageBoundaryZ;
   }
}
