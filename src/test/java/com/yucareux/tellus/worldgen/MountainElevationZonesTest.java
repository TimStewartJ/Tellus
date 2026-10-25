package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MountainElevationZonesTest {
   @Test
   void treelineFallsTowardThePolesAndIsLowerInTheOceanicSouth() {
      assertEquals(2100.0, MountainElevationZones.treelineMeters(46.0), 1.0E-9);
      assertEquals(680.0, MountainElevationZones.treelineMeters(62.0), 1.0E-9);
      assertTrue(MountainElevationZones.treelineMeters(28.0) > 3800.0);
      assertTrue(MountainElevationZones.treelineMeters(-43.5) < MountainElevationZones.treelineMeters(43.5) - 500.0);
      assertEquals(0.0, MountainElevationZones.treelineMeters(80.0), 1.0E-9);
      double previous = Double.MAX_VALUE;
      for (double latitude = 30.0; latitude <= 75.0; latitude += 2.5) {
         double treeline = MountainElevationZones.treelineMeters(latitude);
         assertTrue(treeline <= previous, "latitude " + latitude);
         previous = treeline;
      }
   }

   @Test
   void anchorsSitAboveFloorsWhereTheTreelineIsLow() {
      assertEquals(MountainElevationZones.MIN_HIGHLAND_METERS, MountainElevationZones.highlandMeters(70.0), 1.0E-9);
      assertEquals(MountainElevationZones.MIN_ALPINE_METERS, MountainElevationZones.alpineMeters(70.0), 1.0E-9);
      assertEquals(1700.0, MountainElevationZones.highlandMeters(46.0), 1.0E-9);
      assertEquals(2400.0, MountainElevationZones.alpineMeters(46.0), 1.0E-9);
   }

   @Test
   void anchorsMapOntoTheRuleScale() {
      MountainElevationZones.Band band = MountainElevationZones.band(46.0, 30.0, 1.0, 1.0, false, true);
      int highland = (int)Math.ceil(band.highlandBlocks());
      int alpine = (int)Math.ceil(band.alpineBlocks());
      // Rounding up to whole blocks moves the anchors by less than one block's worth of zone units.
      assertEquals(MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA, MountainElevationZones.zoneHeight(highland, band), 3.0);
      assertEquals(MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA, MountainElevationZones.zoneHeight(alpine, band), 3.0);
      assertTrue(MountainElevationZones.zoneHeight(highland - 2, band) < MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA);
      assertEquals(-5, MountainElevationZones.zoneHeight(-5, band));
      assertEquals(0, MountainElevationZones.zoneHeight(0, band));
   }

   @Test
   void theSameRealElevationLandsInTheSameZoneAtEveryScale() {
      double latitude = 46.0;
      for (double meters : new double[]{1200.0, 1900.0, 2600.0, 3400.0}) {
         int zoneAt30 = zoneAt(meters, latitude, 30.0);
         int zoneAt10 = zoneAt(meters, latitude, 10.0);
         int zoneAt5 = zoneAt(meters, latitude, 5.0);
         // Within the block quantisation of the coarsest scale.
         assertEquals(zoneAt10, zoneAt30, 3.0, meters + " m");
         assertEquals(zoneAt10, zoneAt5, 3.0, meters + " m");
      }
   }

   @Test
   void norwegianFjellIsAlpineWhileAlpineValleysAreNot() {
      // 1,500 m in western Norway is above the treeline; 1,500 m in the Alps is forest.
      assertTrue(zoneAt(1500.0, 62.1, 30.0) >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA);
      assertTrue(zoneAt(1500.0, 46.0, 30.0) < MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA);
      assertTrue(zoneAt(4500.0, 28.0, 30.0) >= MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA);
   }

   private static int zoneAt(double meters, double latitude, double worldScale) {
      MountainElevationZones.Band band = MountainElevationZones.band(latitude, worldScale, 1.0, 1.0, false, true);
      int blocks = TerrainHeightTransform.blockOffsetAtLatitude(meters, latitude, worldScale, 1.0, 1.0, false, true);
      return MountainElevationZones.zoneHeight(blocks, band);
   }
}
