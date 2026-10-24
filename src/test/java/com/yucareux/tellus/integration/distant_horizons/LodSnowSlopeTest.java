package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class LodSnowSlopeTest {
   private static final double SCALE = 30.0;

   @Test
   void cellsAsFineAsTheChunkSlopeKeepTheirOwnSlope() {
      assertTrue(LodSnowSlope.level(SCALE, SCALE).exact());
      // Two-block cells at 1:30 read the same 60 m baseline as chunks.
      assertTrue(LodSnowSlope.level(SCALE, 60.0).exact());
      assertFalse(LodSnowSlope.level(SCALE, 120.0).exact());
      // At 1:1, cells finer than the 30 m elevation data add no smoothing.
      assertTrue(LodSnowSlope.level(1.0, 16.0).exact());
      assertFalse(LodSnowSlope.level(1.0, 128.0).exact());
      assertEquals(52.5, LodSnowSlope.drawSlopeDegrees(LodSnowSlope.level(SCALE, 60.0), 52.5, 0.8, 2000.0, 11, 13), 1.0E-9);
      assertTrue(Double.isNaN(LodSnowSlope.drawSlopeDegrees(LodSnowSlope.level(SCALE, 960.0), Double.NaN, 0.5, 0.0, 11, 13)));
   }

   @Test
   void flatIceStaysWhiteAtEveryDistance() {
      // An ice sheet reads flat and smooth at every level, kilometres above any treeline.
      for (int cell : new int[]{4, 8, 32, 128, 256, 1024}) {
         LodSnowSlope.Level level = LodSnowSlope.level(SCALE, SCALE * cell);
         assertTrue(steepShare(level, 0.3, 0.002, 2500.0) < 0.01, "cell " + cell);
      }
   }

   @Test
   void steepCellsHoldSteepColumnsAndCoarseCellsHideSome() {
      // A face that reads 50 degrees on fine data is mostly steeper than 45 degrees.
      assertTrue(steepShare(LodSnowSlope.level(SCALE, SCALE * 4), 50.0, 1.0, 1000.0) > 0.6);
      // A coarse cell reading 20 degrees among high peaks still hides steep faces.
      double hidden = steepShare(LodSnowSlope.level(SCALE, SCALE * 64), 20.0, 0.4, 1500.0);
      assertTrue(hidden > 0.05 && hidden < 0.5, "share " + hidden);
   }

   @Test
   void drawsGrowWithSlopeReliefAndHeightAboveTheTreeline() {
      LodSnowSlope.Level level = LodSnowSlope.level(SCALE, SCALE * 32);
      for (int x = 0; x < 400; x += 7) {
         int z = x * 3 + 11;
         double gentle = LodSnowSlope.drawSlopeDegrees(level, 15.0, 0.4, 800.0, x, z);
         assertTrue(LodSnowSlope.drawSlopeDegrees(level, 35.0, 0.4, 800.0, x, z) >= gentle, "steeper cell at " + x + "," + z);
         assertTrue(LodSnowSlope.drawSlopeDegrees(level, 15.0, 0.4, 2500.0, x, z) >= gentle, "higher cell at " + x + "," + z);
         assertTrue(LodSnowSlope.drawSlopeDegrees(level, 15.0, 0.0, 800.0, x, z) <= gentle, "smooth cell at " + x + "," + z);
         // Height below the treeline counts as none, not as smoother ground.
         assertEquals(
            LodSnowSlope.drawSlopeDegrees(level, 15.0, 0.4, 0.0, x, z),
            LodSnowSlope.drawSlopeDegrees(level, 15.0, 0.4, -600.0, x, z),
            1.0E-9
         );
      }
   }

   @Test
   void columnsDrawnSteepForRockAreDrawnSteepForSnow() {
      LodSnowSlope.Level level = LodSnowSlope.level(SCALE, SCALE * 16);
      List<int[]> positions = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
         positions.add(new int[]{i * 37, i * 53});
      }
      // Ordering positions by their rock-slope roll orders their snow slopes too.
      positions.sort(Comparator.comparingDouble(position -> LodTerrainMetrics.slopeRoll(position[0], position[1])));
      double previous = -1.0;
      for (int[] position : positions) {
         double slope = LodSnowSlope.drawSlopeDegrees(level, 25.0, 0.4, 1200.0, position[0], position[1]);
         assertTrue(slope >= previous - 1.0E-9);
         previous = slope;
      }
   }

   @Test
   void coarserCellsThanTheFitReuseItsConstants() {
      assertEquals(LodSnowSlope.level(SCALE, SCALE * 512), LodSnowSlope.level(SCALE, SCALE * 4096));
   }

   private static double steepShare(LodSnowSlope.Level level, double cellSlopeDegrees, double relief, double metersAboveTreeline) {
      int steep = 0;
      int total = 0;
      for (int x = 0; x < 4096; x += 13) {
         for (int z = 0; z < 4096; z += 29) {
            total++;
            if (LodSnowSlope.drawSlopeDegrees(level, cellSlopeDegrees, relief, metersAboveTreeline, x, z) >= 45.0) {
               steep++;
            }
         }
      }
      return (double)steep / total;
   }
}
