package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.worldgen.StreamChannelProfile.Direction;
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
      int[] surfaces = profile(terrain);
      assertArrayEquals(new int[]{100, 100, 99, 99, 98, 98, 97, 97, 96, 96, 95}, surfaces);
      for (int k = 1; k < surfaces.length; k++) {
         assertTrue(surfaces[k] <= surfaces[k - 1], "surface climbs at station " + k);
         assertTrue(surfaces[k] <= terrain[k], "water above terrain at station " + k);
      }
   }

   @Test
   void steadyDescentIsNotCutAtAll() {
      int[] terrain = {50, 49, 48, 47, 46, 45};
      assertArrayEquals(terrain, profile(terrain));
   }

   @Test
   void establishedReachLevelDoesNotDriftUpstream() {
      int[] terrain = {90, 92, 92, 92, 92, 92, 92, 92};
      assertArrayEquals(
         new int[]{90, 90, 90, 90, 90, 90, 90, 90},
         StreamChannelProfile.profileDirected(terrain, 6, 12, 48)
      );
   }

   @Test
   void sourceDirectionIsReversedWhenThatRemovesAnUphillConflict() {
      int[] terrain = {455, 463, 462};
      StreamChannelProfile.Profile profile = StreamChannelProfile.profile(terrain, 6, 12, 48);
      assertEquals(Direction.REVERSED, profile.direction());
      assertArrayEquals(new int[]{455, 462, 462}, profile.surfaces());
      assertTrue(profile.reversedConflict().compareTo(profile.sourceOrderConflict()) < 0);
   }

   @Test
   void directionScoringRespectsAValidBracketedHump() {
      int[] terrain = {6, 0, 7, 5};
      StreamChannelProfile.Profile profile = StreamChannelProfile.profile(terrain, 6, 12, 48);
      assertEquals(Direction.SOURCE_ORDER, profile.direction());
      assertArrayEquals(new int[]{6, 0, 0, 0}, profile.surfaces());
   }

   @Test
   void ambiguousTerrainUsesCanonicalGeometryTieBreak() {
      int[] terrain = {1, 0, 1};
      StreamChannelProfile.Profile canonical = StreamChannelProfile.profile(
         terrain, 6, 12, 48, false
      );
      StreamChannelProfile.Profile reversedGeometry = StreamChannelProfile.profile(
         reverse(terrain), 6, 12, 48, true
      );
      assertEquals(Direction.SOURCE_ORDER, canonical.direction());
      assertEquals(Direction.REVERSED, reversedGeometry.direction());
      assertArrayEquals(canonical.surfaces(), reverse(reversedGeometry.surfaces()));
   }

   @Test
   void fewerBarriersAlwaysWinsRegardlessOfOtherCosts() {
      int[] terrain = {2381, 354, 781, 975};
      StreamChannelProfile.Profile profile = StreamChannelProfile.profile(
         terrain, 6, 12, 48
      );
      assertEquals(1, profile.reversedConflict().barriers());
      assertEquals(2, profile.sourceOrderConflict().barriers());
      assertEquals(Direction.REVERSED, profile.direction());
   }

   @Test
   void unresolvedHumpsStayDryUntilTheOldReachLevelCanResume() {
      int[] terrain = {10, 10, 9, 9, 20, 22, 21, 19, 18};
      int[] surfaces = StreamChannelProfile.profileDirected(terrain, 6, 12, 3);
      assertArrayEquals(new int[]{10, 10, 9, 9, DRY, DRY, DRY, DRY, DRY}, surfaces);
      assertWetSurfaceNeverClimbs(surfaces);
   }

   @Test
   void aLongBarrierNeverRestartsAtItsHigherPlateau() {
      int[] terrain = {10, 20, 20, 20, 20, 20, 18};
      int[] surfaces = StreamChannelProfile.profileDirected(terrain, 6, 12, 2);
      assertArrayEquals(new int[]{10, DRY, DRY, DRY, DRY, DRY, DRY}, surfaces);
   }

   @Test
   void shortBracketedDemHumpsUseTheHydroFlatteningBudget() {
      int[] terrain = {20, 20, 28, 27, 25, 20, 19};
      assertArrayEquals(
         new int[]{20, 20, 20, 20, 20, 20, 19},
         StreamChannelProfile.profileDirected(terrain, 6, 12, 6)
      );
      assertArrayEquals(
         new int[]{20, 20, DRY, 20, 20, 20, 19},
         StreamChannelProfile.profileDirected(terrain, 6, 7, 6)
      );
   }

   @Test
   void unknownStationsBreakTheMemoryAndAreReportedAsUnknown() {
      int[] terrain = {10, 9, UNKNOWN, 30, 29};
      int[] surfaces = StreamChannelProfile.profileDirected(terrain, 6, 12, 48);
      assertArrayEquals(new int[]{10, 9, UNKNOWN, 30, 29}, surfaces);
      assertEquals(4, StreamChannelProfile.wetStations(surfaces));
      assertEquals(9, StreamChannelProfile.lowestKnown(terrain));
      assertEquals(UNKNOWN, StreamChannelProfile.lowestKnown(new int[]{UNKNOWN, UNKNOWN}));
   }

   @Test
   void reverseInputProducesTheSamePhysicalProfile() {
      int[] terrain = {100, 101, 99, 100, 98, 99, 97, 98, 96, 97, 95};
      int[] forward = profile(terrain);
      int[] reversedTerrain = reverse(terrain);
      int[] reversedProfile = reverse(profile(reversedTerrain));
      assertArrayEquals(forward, reversedProfile);
   }

   @Test
   void emptyAndSingleStationInputs() {
      assertArrayEquals(new int[0], profile(new int[0]));
      assertArrayEquals(new int[]{7}, profile(new int[]{7}));
      assertFalse(profile(new int[]{7})[0] == DRY);
   }

   private static int[] profile(int[] terrain) {
      return StreamChannelProfile.profile(terrain, 6, 12, 48).surfaces();
   }

   private static void assertWetSurfaceNeverClimbs(int[] surfaces) {
      int previous = UNKNOWN;
      for (int surface : surfaces) {
         if (surface == DRY || surface == UNKNOWN) {
            continue;
         }
         if (previous != UNKNOWN) {
            assertTrue(surface <= previous, surface + " climbs above " + previous);
         }
         previous = surface;
      }
   }

   private static int[] reverse(int[] values) {
      int[] reversed = new int[values.length];
      for (int i = 0; i < values.length; i++) {
         reversed[i] = values[values.length - 1 - i];
      }
      return reversed;
   }
}
