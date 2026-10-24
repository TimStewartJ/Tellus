package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodUnderwaterVegetationTest {
   private static final int AREA = 1536;

   @Test
   void wideCellsKeepTheColourBalanceOfFineOnes() {
      double[] fine = shares(LodUnderwaterVegetation.HABITAT_WARM, 1, 8);
      double[] coarse = shares(LodUnderwaterVegetation.HABITAT_WARM, 16, 8);
      double fineCoral = 0.0;
      for (int kind = LodUnderwaterVegetation.KIND_TUBE_CORAL; kind <= LodUnderwaterVegetation.KIND_HORN_CORAL; kind++) {
         fineCoral += fine[kind];
      }
      // A reef cell seen from afar is about half coral and half sand.
      double coarseCoral = 0.5 * (coarse[LodUnderwaterVegetation.KIND_REEF] + coarse[LodUnderwaterVegetation.KIND_REEF_DENSE]);
      assertTrue(fineCoral > 0.1, "coral share " + fineCoral);
      assertEquals(fineCoral, coarseCoral, 0.06);
      assertEquals(fine[LodUnderwaterVegetation.KIND_SEAGRASS], coarse[LodUnderwaterVegetation.KIND_SEAGRASS], 0.06);

      double[] fineKelp = shares(LodUnderwaterVegetation.HABITAT_TEMPERATE, 2, 12);
      double[] coarseKelp = shares(LodUnderwaterVegetation.HABITAT_TEMPERATE, 32, 12);
      assertTrue(fineKelp[LodUnderwaterVegetation.KIND_KELP] > 0.1);
      assertEquals(fineKelp[LodUnderwaterVegetation.KIND_KELP], coarseKelp[LodUnderwaterVegetation.KIND_KELP], 0.04);
   }

   @Test
   void wideCellsHoldNoCoralColoursOrStandingPlants() {
      int[] habitats = {LodUnderwaterVegetation.HABITAT_WARM, LodUnderwaterVegetation.HABITAT_LUKEWARM, LodUnderwaterVegetation.HABITAT_TEMPERATE};
      for (int habitat : habitats) {
         for (int cell = 8; cell <= 256; cell *= 2) {
            for (int i = 0; i < 4000; i++) {
               int packed = resolve(habitat, i * 37 * cell, i * 11 * cell, cell, 10);
               assertEquals(LodUnderwaterVegetation.KIND_NONE, LodUnderwaterVegetation.raisedKind(packed));
               assertTrue(LodUnderwaterVegetation.floorKind(packed) <= LodUnderwaterVegetation.KIND_REEF_DENSE);
            }
         }
      }
   }

   @Test
   void coralGrowsInColoniesAFewBlocksWideNotInSquarePatches() {
      int size = 1024;
      int[][] kinds = new int[size][size];
      int coral = 0;
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            int packed = resolve(LodUnderwaterVegetation.HABITAT_WARM, x, z, 1, 8);
            kinds[z][x] = LodUnderwaterVegetation.raisedKind(packed);
            if (kinds[z][x] >= LodUnderwaterVegetation.KIND_TUBE_CORAL) {
               coral++;
            }
         }
      }
      assertTrue(coral > size * size / 20, "coral blocks " + coral);
      // A colony is at most about five blocks across; neighbours of one species touch now and then.
      int runs = 0;
      int runBlocks = 0;
      int longRunBlocks = 0;
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; ) {
            int end = x;
            while (end < size && kinds[z][end] == kinds[z][x]) {
               end++;
            }
            if (kinds[z][x] != 0) {
               runs++;
               runBlocks += end - x;
               longRunBlocks += end - x > 8 ? end - x : 0;
            }
            x = end;
         }
      }
      assertTrue(runBlocks / (double)runs < 4.0, "mean run " + runBlocks / (double)runs);
      assertTrue(longRunBlocks < runBlocks / 50, "blocks in runs over 8: " + longRunBlocks + " of " + runBlocks);
      // The old 8-block patches filled whole aligned squares with one block.
      for (int z = 0; z < size; z += 8) {
         for (int x = 0; x < size; x += 8) {
            assertTrue(kinds[z][x] == 0 || !uniform(kinds, x, z, 8), "uniform 8x8 patch at " + x + "," + z);
         }
      }
   }

   @Test
   void standingPlantsLeaveWaterAboveThem() {
      int raised = 0;
      int flatCoral = 0;
      for (int depth = 1; depth <= 14; depth++) {
         for (int i = 0; i < 20_000; i++) {
            int x = i * 7 % 3000;
            int z = i * 13 / 3;
            int warm = resolve(LodUnderwaterVegetation.HABITAT_WARM, x, z, 1, depth);
            int kelp = resolve(LodUnderwaterVegetation.HABITAT_COLD, x, z, 1, depth);
            if (LodUnderwaterVegetation.raisedKind(warm) != 0) {
               raised++;
               assertTrue(LodUnderwaterVegetation.raisedHeight(warm) >= 1 && LodUnderwaterVegetation.raisedHeight(warm) <= Math.min(3, depth - 1));
            } else if (LodUnderwaterVegetation.floorKind(warm) >= LodUnderwaterVegetation.KIND_TUBE_CORAL) {
               flatCoral++;
               assertEquals(1, depth, "coral lies flat only where it cannot stand");
            }
            if (LodUnderwaterVegetation.raisedKind(kelp) != 0) {
               assertEquals(LodUnderwaterVegetation.KIND_KELP, LodUnderwaterVegetation.raisedKind(kelp));
               assertTrue(LodUnderwaterVegetation.raisedHeight(kelp) <= depth - 2);
            }
         }
      }
      assertTrue(raised > 0 && flatCoral > 0);
   }

   @Test
   void plantsFollowTheirDepthAndWater() {
      for (int i = 0; i < 20_000; i++) {
         int x = i * 31;
         int z = -i * 17;
         for (int habitat = LodUnderwaterVegetation.HABITAT_WARM; habitat <= LodUnderwaterVegetation.HABITAT_SWAMP; habitat++) {
            assertEquals(0, LodUnderwaterVegetation.resolve(habitat, x, z, 1, 60, 59.5), "nothing grows 60 m down");
         }
         assertEquals(0, resolve(LodUnderwaterVegetation.HABITAT_NONE, x, z, 1, 6));
         assertEquals(0, resolve(LodUnderwaterVegetation.HABITAT_FRESH, x, z, 16, 1), "rivers would turn wide cells green");
         // Kelp needs a few metres of water.
         int shallow = LodUnderwaterVegetation.resolve(LodUnderwaterVegetation.HABITAT_COLD, x, z, 1, 1, 1.0);
         assertTrue(LodUnderwaterVegetation.raisedKind(shallow) != LodUnderwaterVegetation.KIND_KELP && LodUnderwaterVegetation.floorKind(shallow) != LodUnderwaterVegetation.KIND_KELP);
      }
      assertTrue(shares(LodUnderwaterVegetation.HABITAT_FRESH, 1, 1)[LodUnderwaterVegetation.KIND_SEAGRASS] > 0.05);
   }

   @Test
   void habitatsGrowWhatVanillaDecoratesThemWith() {
      double[] warm = shares(LodUnderwaterVegetation.HABITAT_WARM, 2, 8);
      double[] lukewarm = shares(LodUnderwaterVegetation.HABITAT_LUKEWARM, 2, 8);
      double[] cold = shares(LodUnderwaterVegetation.HABITAT_COLD, 2, 8);
      assertEquals(0.0, warm[LodUnderwaterVegetation.KIND_KELP]);
      assertTrue(warm[LodUnderwaterVegetation.KIND_BRAIN_CORAL] > 0.01 && warm[LodUnderwaterVegetation.KIND_SEAGRASS] > 0.1);
      assertTrue(lukewarm[LodUnderwaterVegetation.KIND_SEAGRASS] > lukewarm[LodUnderwaterVegetation.KIND_KELP]);
      assertTrue(lukewarm[LodUnderwaterVegetation.KIND_KELP] > 0.02 && lukewarm[LodUnderwaterVegetation.KIND_TUBE_CORAL] == 0.0);
      assertTrue(cold[LodUnderwaterVegetation.KIND_KELP] > cold[LodUnderwaterVegetation.KIND_SEAGRASS]);
   }

   @Test
   void depthIsMeasuredAtTheMiddleOfTheDeepestBlock() {
      assertEquals(15.0, LodUnderwaterVegetation.depthMeters(1, 30.0));
      assertEquals(7.5, LodUnderwaterVegetation.depthMeters(8, 1.0));
   }

   private static int resolve(int habitat, int x, int z, int cell, int depthBlocks) {
      return LodUnderwaterVegetation.resolve(habitat, x, z, cell, depthBlocks, LodUnderwaterVegetation.depthMeters(depthBlocks, 1.0));
   }

   /** Share of cells per kind (standing kind first, else floor kind) over {@link #AREA} blocks. */
   private static double[] shares(int habitat, int cell, int depthBlocks) {
      double[] shares = new double[LodUnderwaterVegetation.KIND_COUNT];
      int cells = AREA / cell;
      for (int z = 0; z < cells; z++) {
         for (int x = 0; x < cells; x++) {
            int packed = resolve(habitat, 20_000 + x * cell + (cell >> 1), -9_000 + z * cell + (cell >> 1), cell, depthBlocks);
            int raised = LodUnderwaterVegetation.raisedKind(packed);
            shares[raised != 0 ? raised : LodUnderwaterVegetation.floorKind(packed)]++;
         }
      }
      for (int kind = 0; kind < shares.length; kind++) {
         shares[kind] /= (double)cells * cells;
      }
      return shares;
   }

   private static boolean uniform(int[][] kinds, int x0, int z0, int size) {
      for (int z = z0; z < z0 + size; z++) {
         for (int x = x0; x < x0 + size; x++) {
            if (kinds[z][x] != kinds[z0][x0]) {
               return false;
            }
         }
      }
      return true;
   }
}
