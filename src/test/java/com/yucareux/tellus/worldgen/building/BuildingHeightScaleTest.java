package com.yucareux.tellus.worldgen.building;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingHeightScaleTest {
   private static final double PARIS_LATITUDE = 48.8584;

   @Test
   void heightsTakeTheMercatorFactorOfTheirFootprints() {
      double sec = 1.0 / Math.cos(Math.toRadians(PARIS_LATITUDE));
      assertEquals(32, TellusBuildingProfiles.heightBlocks(feature(0.0, 32.0, 0.0, "office"), 1.0));
      assertEquals((int)Math.round(32.0 * sec), TellusBuildingProfiles.heightBlocks(feature(PARIS_LATITUDE, 32.0, 0.0, "office"), 1.0));
      assertEquals((int)Math.round(50.0 * sec / 5.0), TellusBuildingProfiles.minHeightBlocks(feature(PARIS_LATITUDE, 60.0, 50.0, "office"), 5.0));
   }

   @Test
   void storeysFillTheRealHeightAtEveryScale() {
      for (double latitude : new double[]{0.0, PARIS_LATITUDE, 64.1}) {
         for (double scale : new double[]{1.0, 2.0, 5.0, 10.0, 15.0}) {
            OsmBuildingFeature office = feature(latitude, 32.0, 0.0, "office");
            BuildingProfile profile = TellusBuildingProfiles.resolveProfile(office, scale, null, scale == 1.0);
            double targetBlocks = 32.0 * TellusBuildingProfiles.heightBlocksPerMeter(office, scale);
            int builtBlocks = profile.floorCount() * profile.storeyHeightBlocks() + profile.roofRise();
            assertTrue(
               Math.abs(builtBlocks - targetBlocks) <= profile.storeyHeightBlocks() * 0.5 + 0.5,
               () -> "lat " + latitude + " scale " + scale + ": built " + builtBlocks + " for " + targetBlocks
            );
         }
      }
   }

   @Test
   void elevatedPartsOnlyStackStoreysAboveTheirMinHeight() {
      BuildingProfile profile = TellusBuildingProfiles.resolveProfile(feature(0.0, 60.0, 50.0, "office"), 1.0, null, true);
      int builtBlocks = profile.floorCount() * profile.storeyHeightBlocks() + profile.roofRise();
      assertTrue(Math.abs(builtBlocks - 10) <= 2, () -> "built " + builtBlocks + " blocks for a 10 m part");
   }

   private static OsmBuildingFeature feature(double latitude, double heightMeters, double minHeightMeters, String use) {
      double side = 0.0003;
      return new OsmBuildingFeature(
         minHeightMeters > 0.0 ? OsmBuildingKind.PART : OsmBuildingKind.FOOTPRINT,
         7L,
         "building-7",
         false,
         new OsmBuildingMetadata(null, use, use, null, 1, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0),
         heightMeters,
         minHeightMeters,
         new double[][]{{2.0, 2.0 + side, 2.0 + side, 2.0, 2.0}},
         new double[][]{{latitude, latitude, latitude + side, latitude + side, latitude}}
      );
   }
}
