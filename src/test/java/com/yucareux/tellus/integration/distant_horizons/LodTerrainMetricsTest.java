package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodTerrainMetricsTest {
   private static final double SCALE = 30.0;

   @Test
   void fullDetailCellsKeepTheFourBlockMetrics() {
      LodTerrainMetrics.Level level = LodTerrainMetrics.level(SCALE, 1);
      assertTrue(level.exact());
      // One block of rise per block: four blocks over the four-block baseline.
      int[] slopes = new int[16], convexities = new int[16];
      fill(4, 1, (x, z) -> x, slopes, convexities);
      for (int i = 0; i < slopes.length; i++) {
         assertEquals(4, slopes[i]);
         assertEquals(0, convexities[i]);
      }
   }

   @Test
   void cellsAsFineAsTheElevationDataStayExact() {
      // At 1:1 an eight-block cell is still finer than 30 m elevation data.
      assertTrue(LodTerrainMetrics.level(1.0, 8).exact());
      assertFalse(LodTerrainMetrics.level(1.0, 64).exact());
      assertFalse(LodTerrainMetrics.level(SCALE, 2).exact());
   }

   @Test
   void flatGroundStaysFlatAtEveryLevel() {
      for (int cell : new int[]{2, 8, 32, 128, 1024}) {
         int[] slopes = new int[64], convexities = new int[64];
         fill(8, cell, (x, z) -> 120, slopes, convexities);
         for (int i = 0; i < slopes.length; i++) {
            assertEquals(0, slopes[i], "cell " + cell);
            assertEquals(0, convexities[i], "cell " + cell);
         }
      }
   }

   @Test
   void drawnSlopesFollowTheFittedDistribution() {
      LodTerrainMetrics.Level level = LodTerrainMetrics.level(SCALE, 32);
      double cellSlope = 2.0, relief = 2.0;
      double mean = level.reliefMean() * relief / (relief + 0.189) + 0.887 * cellSlope;
      int[] atLeast = new int[8];
      int samples = 0;
      for (int z = -8192; z < 8192; z += 32) {
         for (int x = -8192; x < 8192; x += 32) {
            int slope = LodTerrainMetrics.drawSlope(level, cellSlope, relief, x, z);
            for (int t = 0; t < atLeast.length; t++) {
               if (slope >= t) {
                  atLeast[t]++;
               }
            }
            samples++;
         }
      }
      for (int t = 2; t <= 6; t++) {
         double expected = 1.0 / (1.0 + Math.pow(t / mean, 1.0 / level.spread()));
         assertEquals(expected, (double)atLeast[t] / samples, 0.03, "slope >= " + t);
      }
   }

   @Test
   void steeperCellsDrawSteeperColumns() {
      LodTerrainMetrics.Level level = LodTerrainMetrics.level(SCALE, 64);
      double previous = -1.0;
      for (double cellSlope : new double[]{0.0, 0.5, 1.0, 2.0, 4.0}) {
         double sum = 0.0;
         int samples = 0;
         for (int z = 0; z < 8192; z += 64) {
            for (int x = 0; x < 8192; x += 64) {
               sum += LodTerrainMetrics.drawSlope(level, cellSlope, Math.max(cellSlope, 1.0), x, z);
               samples++;
            }
         }
         double mean = sum / samples;
         assertTrue(mean > previous, "cell slope " + cellSlope);
         previous = mean;
      }
   }

   @Test
   void tileEdgesMatchOneContinuousGrid() {
      int cell = 16, size = 8;
      int[] wholeSlopes = new int[2 * size * 2 * size], wholeConvexities = new int[wholeSlopes.length];
      fill(2 * size, cell, LodTerrainMetricsTest::rolling, wholeSlopes, wholeConvexities);
      for (int tile = 0; tile < 4; tile++) {
         int offsetX = (tile & 1) * size, offsetZ = (tile >> 1) * size;
         int[] slopes = new int[size * size], convexities = new int[size * size];
         fill(size, cell, (x, z) -> rolling(x + offsetX, z + offsetZ), offsetX, offsetZ, slopes, convexities);
         for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
               int whole = (z + offsetZ) * 2 * size + x + offsetX;
               assertEquals(wholeSlopes[whole], slopes[z * size + x]);
               assertEquals(wholeConvexities[whole], convexities[z * size + x]);
            }
         }
      }
   }

   @Test
   void concaveCellsHoldMoreConcaveColumns() {
      LodTerrainMetrics.Level level = LodTerrainMetrics.level(SCALE, 8);
      int concaveInHollows = 0, concaveOnCrests = 0;
      for (int z = 0; z < 8192; z += 8) {
         for (int x = 0; x < 8192; x += 8) {
            if (LodTerrainMetrics.drawConvexity(level, 4, 1.0, x, z) >= 1) {
               concaveInHollows++;
            }
            if (LodTerrainMetrics.drawConvexity(level, 4, -1.0, x, z) >= 1) {
               concaveOnCrests++;
            }
         }
      }
      assertTrue(concaveInHollows > 2 * concaveOnCrests, concaveInHollows + " vs " + concaveOnCrests);
   }

   @Test
   void cellsCoarserThanTheFitReuseItsConstants() {
      LodTerrainMetrics.Level coarse = LodTerrainMetrics.level(SCALE, 1024);
      assertEquals(LodTerrainMetrics.level(SCALE, 512), coarse);
      assertTrue(LodTerrainMetrics.level(SCALE, 8).spread() < coarse.spread());
   }

   private static int rolling(int x, int z) {
      return 200 + (int)Math.round(40.0 * Math.sin(x * 0.7) + 25.0 * Math.cos(z * 0.45) + 9.0 * Math.sin((x + z) * 1.3));
   }

   private static void fill(int size, int cell, HeightField field, int[] slopes, int[] convexities) {
      fill(size, cell, field, 0, 0, slopes, convexities);
   }

   /** Fills a {@code size}-cell tile whose cell (0, 0) sits at grid position (offsetX, offsetZ). */
   private static void fill(int size, int cell, HeightField field, int offsetX, int offsetZ, int[] slopes, int[] convexities) {
      int reach = LodTerrainMetrics.reach(cell);
      int width = size + 2 * reach;
      int[] heights = new int[width * width];
      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            heights[z * width + x] = field.height(x - reach, z - reach);
         }
      }
      int[] worldXs = new int[size], worldZs = new int[size];
      for (int i = 0; i < size; i++) {
         worldXs[i] = (i + offsetX) * cell + cell / 2;
         worldZs[i] = (i + offsetZ) * cell + cell / 2;
      }
      LodTerrainMetrics.fill(heights, size, cell, LodTerrainMetrics.level(SCALE, cell), worldXs, worldZs, slopes, convexities);
   }

   @FunctionalInterface
   private interface HeightField {
      int height(int x, int z);
   }
}
