package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntarcticSnowPolicyTest {
   private static final WorldProjection PROJECTION = WorldProjection.global(30.0);

   @Test
   void appliesOnlyToNoDataSouthOfWorldCoverCoverage() {
      AntarcticSnowPolicy policy = AntarcticSnowPolicy.forProjection(PROJECTION);

      assertFalse(policy.shouldUseSnowFallback(
         TellusLandCoverSource.NO_DATA_CLASS,
         PROJECTION.latToBlockZ(TellusLandCoverSource.WORLD_COVER_MIN_LATITUDE)
      ));
      assertTrue(policy.shouldUseSnowFallback(TellusLandCoverSource.NO_DATA_CLASS, PROJECTION.latToBlockZ(-75.0)));
      assertFalse(policy.shouldUseSnowFallback(TellusLandCoverSource.NO_DATA_CLASS, PROJECTION.latToBlockZ(-45.0)));
   }

   @Test
   void preservesValidLandCoverSouthOfCoverage() {
      AntarcticSnowPolicy policy = AntarcticSnowPolicy.forProjection(PROJECTION);
      double antarcticZ = PROJECTION.latToBlockZ(-75.0);

      assertFalse(policy.shouldUseSnowFallback(30, antarcticZ));
      assertFalse(policy.shouldUseSnowFallback(70, antarcticZ));
      assertFalse(policy.shouldUseSnowFallback(80, antarcticZ));
   }

   @Test
   void nonPositiveWorldScalesDisableTheFallback() {
      double antarcticZ = PROJECTION.latToBlockZ(-75.0);

      assertFalse(AntarcticSnowPolicy.forProjection(WorldProjection.global(0.0)).shouldUseSnowFallback(0, antarcticZ));
      assertFalse(AntarcticSnowPolicy.forProjection(WorldProjection.global(-1.0)).shouldUseSnowFallback(0, antarcticZ));
   }

   @Test
   void followsTheWorldProjectionWhenMappingTheCoverageBoundary() {
      for (WorldProjection projection : new WorldProjection[]{
         WorldProjection.global(1.0),
         WorldProjection.global(1000.0),
         WorldProjection.centeredOn(30.0, 37.75, -119.59),
         WorldProjection.centeredOn(1.0, -77.85, 166.67)
      }) {
         AntarcticSnowPolicy policy = AntarcticSnowPolicy.forProjection(projection);
         assertTrue(policy.shouldUseSnowFallback(0, projection.latToBlockZ(-60.01)));
         assertFalse(policy.shouldUseSnowFallback(0, projection.latToBlockZ(-59.99)));
      }
   }
}
