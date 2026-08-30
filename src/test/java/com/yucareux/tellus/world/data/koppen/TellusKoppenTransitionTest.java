package com.yucareux.tellus.world.data.koppen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TellusKoppenTransitionTest {
   private static final double ONE_TO_ONE_SOURCE_CELL_BLOCKS = 927.0;

   @Test
   void derivesClimatePatchScaleFromSourceResolution() {
      assertEquals(8.0, TellusKoppenSource.transitionPatchBlocks(40.0));
      assertEquals(92.7, TellusKoppenSource.transitionPatchBlocks(ONE_TO_ONE_SOURCE_CELL_BLOCKS), 1.0E-9);
      assertEquals(128.0, TellusKoppenSource.transitionPatchBlocks(2000.0));
   }

   @Test
   void ignoresUnavailableNeighborsAndReturnsOnlyWeightedClimateValues() {
      for (int z = -128; z <= 128; z++) {
         for (int x = -128; x <= 128; x++) {
            int selected = select(4, 4, 0, 7, 0, 0.5, 0.5, x, z, 128.0);
            assertTrue(selected == 4 || selected == 7);
         }
      }
   }

   @Test
   void noLongerLocksEveryFourByFourCellToOneThreshold() {
      boolean foundMixedCell = false;
      for (int cellZ = -32; cellZ <= 32 && !foundMixedCell; cellZ++) {
         for (int cellX = -32; cellX <= 32 && !foundMixedCell; cellX++) {
            int first = select(1, 1, 2, 1, 2, 0.5, 0.5, cellX * 4, cellZ * 4, ONE_TO_ONE_SOURCE_CELL_BLOCKS);
            for (int dz = 0; dz < 4 && !foundMixedCell; dz++) {
               for (int dx = 0; dx < 4; dx++) {
                  int selected = select(
                     1,
                     1,
                     2,
                     1,
                     2,
                     0.5,
                     0.5,
                     cellX * 4 + dx,
                     cellZ * 4 + dz,
                     ONE_TO_ONE_SOURCE_CELL_BLOCKS
                  );
                  if (selected != first) {
                     foundMixedCell = true;
                     break;
                  }
               }
            }
         }
      }
      assertTrue(foundMixedCell, "the transition threshold must not be quantized to a fixed 4x4 grid");
   }

   @Test
   void producesCoherentRatherThanCheckerboardClimatePatches() {
      int disagreements = 0;
      int comparisons = 0;
      for (int z = -256; z <= 256; z++) {
         int previous = select(1, 1, 2, 1, 2, 0.5, 0.5, -256, z, ONE_TO_ONE_SOURCE_CELL_BLOCKS);
         for (int x = -255; x <= 256; x++) {
            int selected = select(1, 1, 2, 1, 2, 0.5, 0.5, x, z, ONE_TO_ONE_SOURCE_CELL_BLOCKS);
            disagreements += selected == previous ? 0 : 1;
            comparisons++;
            previous = selected;
         }
      }
      double disagreementRate = disagreements / (double)comparisons;
      assertTrue(disagreementRate > 0.0, "both climate candidates should occur");
      assertTrue(disagreementRate < 0.04, "neighboring samples should form coherent patches");
   }

   @Test
   void preservesBilinearCandidateCoverageAcrossAClimateBoundary() {
      int secondClimate = 0;
      int samples = 0;
      for (int z = -512; z < 512; z++) {
         for (int x = -512; x < 512; x++) {
            if (select(1, 1, 2, 1, 2, 0.25, 0.5, x, z, 128.0) == 2) {
               secondClimate++;
            }
            samples++;
         }
      }
      assertEquals(0.25, secondClimate / (double)samples, 0.04);
   }

   @Test
   void isDeterministicAcrossNegativeCoordinates() {
      int expected = select(1, 1, 2, 3, 4, 0.37, 0.61, -12345, -67890, ONE_TO_ONE_SOURCE_CELL_BLOCKS);
      for (int attempt = 0; attempt < 100; attempt++) {
         assertEquals(
            expected,
            select(1, 1, 2, 3, 4, 0.37, 0.61, -12345, -67890, ONE_TO_ONE_SOURCE_CELL_BLOCKS)
         );
      }
   }

   private static int select(
      int center,
      int value00,
      int value10,
      int value01,
      int value11,
      double fractionX,
      double fractionY,
      double blockX,
      double blockZ,
      double sourceCellBlocks
   ) {
      return TellusKoppenSource.selectTransitionValue(
         center,
         value00,
         value10,
         value01,
         value11,
         fractionX,
         fractionY,
         blockX,
         blockZ,
         sourceCellBlocks
      );
   }
}
