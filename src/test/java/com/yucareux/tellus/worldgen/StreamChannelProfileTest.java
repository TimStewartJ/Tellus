package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import org.junit.jupiter.api.Test;

class StreamChannelProfileTest {
   private static final int DRY = StreamChannelProfile.DRY;
   private static final int UNKNOWN = StreamChannelProfile.UNKNOWN;

   @Test
   void widthFollowsTheMappedKindAndTheGroundScale() {
      assertEquals(3, StreamChannelProfile.widthBlocks(OsmWaterKind.STREAM, 1.0));
      assertEquals(14, StreamChannelProfile.widthBlocks(OsmWaterKind.RIVER, 1.0));
      assertEquals(8, StreamChannelProfile.widthBlocks(OsmWaterKind.CANAL, 1.0));
      assertEquals(2, StreamChannelProfile.widthBlocks(OsmWaterKind.DITCH, 1.0));
      assertEquals(1, StreamChannelProfile.widthBlocks(OsmWaterKind.UNKNOWN, 1.0));
      // Coarser worlds keep at least one block; finer worlds are capped.
      assertEquals(1, StreamChannelProfile.widthBlocks(OsmWaterKind.STREAM, 30.0));
      assertEquals(1, StreamChannelProfile.widthBlocks(OsmWaterKind.RIVER, 30.0));
      assertEquals(6, StreamChannelProfile.widthBlocks(OsmWaterKind.STREAM, 0.5));
      assertEquals(OsmWaterKind.MAX_CENTERLINE_WIDTH_BLOCKS, StreamChannelProfile.widthBlocks(OsmWaterKind.RIVER, 0.1));
      // A missing or nonsensical scale behaves like 1:1.
      assertEquals(3, StreamChannelProfile.widthBlocks(OsmWaterKind.STREAM, 0.0));
      assertEquals(3, StreamChannelProfile.widthBlocks(OsmWaterKind.STREAM, Double.NaN));
      assertEquals(1, StreamChannelProfile.widthBlocks(null, 1.0));
   }

   @Test
   void halfWidthKeepsTheHistoricalOneBlockLineAndGrowsWithWidth() {
      assertEquals(0.5, StreamChannelProfile.halfWidthBlocks(1));
      assertEquals(1.5, StreamChannelProfile.halfWidthBlocks(3));
      assertEquals(7.0, StreamChannelProfile.halfWidthBlocks(14));
      assertEquals(0.5, StreamChannelProfile.halfWidthBlocks(0));
   }

   @Test
   void depthGrowsWithWidth() {
      assertEquals(1, StreamChannelProfile.depthBlocks(1));
      assertEquals(1, StreamChannelProfile.depthBlocks(2));
      assertEquals(2, StreamChannelProfile.depthBlocks(3));
      assertEquals(2, StreamChannelProfile.depthBlocks(6));
      assertEquals(3, StreamChannelProfile.depthBlocks(12));
      assertEquals(4, StreamChannelProfile.depthBlocks(14));
   }

   @Test
   void surfaceNeverClimbsThroughDemNoise() {
      // A stream descending 100 -> 95 with +-1 noise in the samples.
      int[] terrain = {100, 101, 99, 100, 98, 99, 97, 98, 96, 97, 95};
      int[] surfaces = StreamChannelProfile.surfaces(terrain, 32, 6);
      assertArrayEquals(new int[]{100, 100, 99, 99, 98, 98, 97, 97, 96, 96, 95}, surfaces);
      for (int k = 1; k < surfaces.length; k++) {
         assertTrue(surfaces[k] <= surfaces[k - 1], "surface climbs at station " + k);
         assertTrue(surfaces[k] <= terrain[k], "water above terrain at station " + k);
      }
   }

   @Test
   void steadyDescentIsNotCutAtAll() {
      int[] terrain = {50, 49, 48, 47, 46, 45};
      assertArrayEquals(terrain, StreamChannelProfile.surfaces(terrain, 32, 6));
   }

   @Test
   void lookbackBoundsHowLongALowLevelIsRemembered() {
      // Level 90 at station 0, then flat terrain at 92: within the lookback the water stays at 90 (a 2-block cut).
      int[] terrain = {90, 92, 92, 92, 92, 92, 92, 92};
      int[] shortMemory = StreamChannelProfile.surfaces(terrain, 2, 6);
      assertArrayEquals(new int[]{90, 90, 90, 92, 92, 92, 92, 92}, shortMemory);
      int[] longMemory = StreamChannelProfile.surfaces(terrain, 32, 6);
      assertArrayEquals(new int[]{90, 90, 90, 90, 90, 90, 90, 90}, longMemory);
   }

   @Test
   void humpsTooHighToCutBecomeSillsAndTheWaterResumesOnTheCrest() {
      int[] terrain = {10, 10, 9, 9, 20, 22, 21, 19, 18};
      int[] surfaces = StreamChannelProfile.surfaces(terrain, 32, 6);
      assertArrayEquals(new int[]{10, 10, 9, 9, DRY, DRY, 21, 19, 18}, surfaces);
      // The last dry station (22) stands above the water that follows it (21): no backflow over the sill.
      assertTrue(terrain[5] >= surfaces[6]);
      // The sill (20) stands above the pool it holds back (9).
      assertTrue(terrain[4] > surfaces[3]);
   }

   @Test
   void aPlateauHumpResumesOnItsFirstLevelStation() {
      int[] terrain = {10, 20, 20, 20, 15};
      assertArrayEquals(new int[]{10, DRY, 20, 20, 15}, StreamChannelProfile.surfaces(terrain, 32, 6));
   }

   @Test
   void humpsWithinTheCutBudgetAreCutThroughInstead() {
      int[] terrain = {10, 14, 16, 12, 10};
      assertArrayEquals(new int[]{10, 10, 10, 10, 10}, StreamChannelProfile.surfaces(terrain, 32, 6));
      assertArrayEquals(new int[]{10, 10, DRY, 12, 10}, StreamChannelProfile.surfaces(terrain, 32, 4));
   }

   @Test
   void unknownStationsBreakTheMemoryAndAreReportedAsUnknown() {
      int[] terrain = {10, 9, UNKNOWN, 30, 29};
      int[] surfaces = StreamChannelProfile.surfaces(terrain, 32, 6);
      assertArrayEquals(new int[]{10, 9, UNKNOWN, 30, 29}, surfaces);
      assertEquals(4, StreamChannelProfile.wetStations(surfaces));
      assertEquals(9, StreamChannelProfile.lowestKnown(terrain));
      assertEquals(UNKNOWN, StreamChannelProfile.lowestKnown(new int[]{UNKNOWN, UNKNOWN}));
   }

   @Test
   void overlappingWindowsAgreeWhereBothSeeTheLine() {
      // The same line analysed from two grids that overlap: stations well inside the overlap must agree,
      // because a surface depends only on the 2 * lookback stations before it.
      int lookback = 4;
      int[] terrain = new int[64];
      long seed = 12345L;
      int level = 200;
      for (int k = 0; k < terrain.length; k++) {
         seed = seed * 6364136223846793005L + 1442695040888963407L;
         int noise = (int)((seed >>> 33) % 5) - 2;
         if (k % 9 == 0) {
            level -= 3;
         }
         terrain[k] = level + noise;
      }
      int[] full = StreamChannelProfile.surfaces(terrain, lookback, 6);
      int start = 20;
      int[] clipped = new int[terrain.length - start];
      System.arraycopy(terrain, start, clipped, 0, clipped.length);
      int[] partial = StreamChannelProfile.surfaces(clipped, lookback, 6);
      for (int k = 2 * lookback; k < clipped.length; k++) {
         assertEquals(full[start + k], partial[k], "station " + (start + k));
      }
   }

   @Test
   void emptyAndSingleStationInputs() {
      assertArrayEquals(new int[0], StreamChannelProfile.surfaces(new int[0], 32, 6));
      assertArrayEquals(new int[]{7}, StreamChannelProfile.surfaces(new int[]{7}, 32, 6));
      assertFalse(StreamChannelProfile.surfaces(new int[]{7}, 0, 0)[0] == DRY);
   }
}
