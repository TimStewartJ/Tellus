package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BadlandsRegionFieldTest {
   private static final int BUILT = 50;
   private static final int BARE = 60;
   private static final int SHRUB = 20;
   private static final int TREES = 10;
   private static final int WATER = 80;

   @Test
   void reliefHasNoStepsAtRegionalCellEdges() {
      // A canyon rim: relief rises steeply eastward. Per-cell relief jumped by whole cells here.
      BadlandsRegionField field = new BadlandsRegionField(30.0);
      BadlandsRegionField.ElevationSampler terrain = (x, z, resolution) -> x * 4.0 + Math.sin(z / 40.0) * 60.0;
      int cell = BadlandsTerrainPolicy.regionalSampleCellBlocks(30.0);
      double largestStep = 0.0;
      for (int z = -3 * cell; z < 3 * cell; z++) {
         for (int x = -3 * cell; x < 3 * cell; x++) {
            double here = field.reliefMeters(x, z, Double.NaN, 1.0, terrain);
            double east = field.reliefMeters(x + 1, z, Double.NaN, 1.0, terrain);
            double south = field.reliefMeters(x, z + 1, Double.NaN, 1.0, terrain);
            largestStep = Math.max(largestStep, Math.max(Math.abs(east - here), Math.abs(south - here)));
         }
      }
      assertTrue(largestStep < 10.0, "relief must change smoothly between neighbouring columns, largest step " + largestStep);
   }

   @Test
   void canyonThresholdIsJitteredIntoAnOrganicBorder() {
      int promoted = 0;
      int kept = 0;
      for (int z = 0; z < 256; z++) {
         for (int x = 0; x < 256; x++) {
            if (BadlandsRegionField.isCanyonRelief(BadlandsTerrainPolicy.MIN_CANYON_RELIEF_METERS, x, z)) {
               promoted++;
            } else {
               kept++;
            }
         }
      }
      assertTrue(promoted > 0 && kept > 0, "relief at the threshold must fall on both sides of it");
      assertTrue(BadlandsRegionField.isCanyonRelief(BadlandsTerrainPolicy.MIN_CANYON_RELIEF_METERS * 1.25, 7, 11));
      assertFalse(BadlandsRegionField.isCanyonRelief(BadlandsTerrainPolicy.MIN_CANYON_RELIEF_METERS * 0.75, 7, 11));
      assertFalse(BadlandsRegionField.isCanyonRelief(Double.NaN, 7, 11));
   }

   @Test
   void aVacantLotInATownIsNotABadlandsLandscape() {
      BadlandsRegionField field = new BadlandsRegionField(1.0);
      // A 30 m bare lot in the middle of built-up land.
      BadlandsRegionField.CoverSampler town = (x, z, resolution) -> Math.abs(x) < 15 && Math.abs(z) < 15 ? BARE : BUILT;
      BadlandsRegionField.Support support = field.support(0, 0, 1.0, 1.0, town);
      assertTrue(support.bareShare() < 0.2, "bare share " + support.bareShare());
      assertFalse(BadlandsRegionField.keepsBadlands(support.bareShare(), 0, 0));
      assertEquals(BUILT, support.nonBareCoverClass());
      assertEquals(BUILT, support.nonDryCoverClass());
   }

   @Test
   void wideBareGroundAndAridScrubKeepTheirBadlands() {
      BadlandsRegionField field = new BadlandsRegionField(1.0);
      BadlandsRegionField.Support bare = field.support(500, 500, 1.0, 1.0, (x, z, resolution) -> BARE);
      assertEquals(1.0, bare.bareShare());
      assertEquals(-1, bare.nonBareCoverClass());
      for (int z = 0; z < 64; z++) {
         for (int x = 0; x < 64; x++) {
            assertTrue(BadlandsRegionField.keepsBadlands(bare.bareShare(), x, z));
         }
      }

      // Scrub with river trees: dry for a canyon, but not bare ground.
      BadlandsRegionField.CoverSampler scrub = (x, z, resolution) -> Math.floorMod(x, 300) < 40 ? TREES : SHRUB;
      BadlandsRegionField.Support canyon = field.support(150, 0, 1.0, 1.0, scrub);
      assertTrue(canyon.dryShare() > 0.7, "dry share " + canyon.dryShare());
      assertEquals(0.0, canyon.bareShare());
   }

   @Test
   void waterAndMissingSamplesDoNotDiluteTheShare() {
      BadlandsRegionField field = new BadlandsRegionField(1.0);
      BadlandsRegionField.CoverSampler riverside = (x, z, resolution) -> x < 0 ? WATER : z < -1000 ? Integer.MIN_VALUE : BARE;
      BadlandsRegionField.Support support = field.support(40, 0, 1.0, 1.0, riverside);
      assertEquals(1.0, support.bareShare());
      // Nodes are cached per field, which always reads one land-cover source: use a fresh one.
      BadlandsRegionField.Support unknown = new BadlandsRegionField(1.0).support(0, 0, 1.0, 1.0, (x, z, resolution) -> WATER);
      assertTrue(Double.isNaN(unknown.dryShare()));
      assertTrue(BadlandsRegionField.keepsBadlands(unknown.dryShare(), 0, 0), "unknown surroundings keep the table's choice");
   }

   @Test
   void supportVariesSmoothlyAcrossTheLattice() {
      BadlandsRegionField field = new BadlandsRegionField(1.0);
      // Bare ground east of x = 0, town to the west.
      BadlandsRegionField.CoverSampler edge = (x, z, resolution) -> x >= 0 ? BARE : BUILT;
      double previous = field.support(-300, 0, 1.0, 1.0, edge).bareShare();
      double largestStep = 0.0;
      for (int x = -299; x <= 300; x++) {
         double share = field.support(x, 0, 1.0, 1.0, edge).bareShare();
         assertTrue(share >= previous - 1.0e-9, "share must not fall going into the bare ground");
         largestStep = Math.max(largestStep, share - previous);
         previous = share;
      }
      assertTrue(largestStep < 0.05, "largest step " + largestStep);
   }

   @Test
   void distantLodColumnsReadTheirOwnPosition() {
      BadlandsRegionField field = new BadlandsRegionField(1.0);
      int[] calls = new int[1];
      BadlandsRegionField.CoverSampler counting = (x, z, resolution) -> {
         calls[0]++;
         return BARE;
      };
      field.support(12345, 678, 256.0, 256.0, counting);
      assertEquals(25, calls[0], "one 5x5 sample grid at the column itself");
   }
}
