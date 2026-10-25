package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import org.junit.jupiter.api.Test;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.assertConnected;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.feature;
import static com.yucareux.tellus.worldgen.InlandHydrologyRegressionTest.planner;
import static org.junit.jupiter.api.Assertions.*;

/** Courses routed through the corridor around their mapped lines: valley floors, shared ends, levees, seams and lines drawn upstream. */
class RiverCorridorTest {
   /** A valley whose floor runs along {@code x = floor}, falling gently towards +z. */
   private static double valley(double x, double z, double floor) {
      return 100.0 - z * 0.01 + Math.abs(x - floor) * 0.3;
   }

   @Test void courseRunsOnTheValleyFloorBesideItsMappedLine() {
      OsmWaterFeature stream = feature(1, true, OsmWaterKind.STREAM, 1000, 100, 1000, 900);
      InlandHydrologyPlanner planner = planner((x, z) -> valley(x, z, 1040), 8, stream);
      for (int z = 200; z <= 800; z += 50) {
         assertTrue(wetNear(planner, 1040, z, 3), "No water on the valley floor at z=" + z);
         assertFalse(planner.sample(1000, z, 112).wet(), "Water kept to the mapped line up the valley side at z=" + z);
         int raw = (int)Math.ceil(valley(1010, z, 1040));
         InlandHydrologyPlanner.Column side = planner.sample(1010, z, raw);
         // The valley side is not cut back towards a channel drawn on the mapped line.
         assertTrue(!side.wet() && side.terrain() >= raw - 1, "Valley side cut at z=" + z + ": " + side);
      }
   }

   @Test void waysOfOneRiverMeetAtTheSameShiftedPoint() {
      OsmWaterFeature upper = feature(2, true, OsmWaterKind.STREAM, 1000, 100, 1000, 500);
      OsmWaterFeature lower = feature(3, true, OsmWaterKind.STREAM, 1000, 500, 1000, 900);
      InlandHydrologyPlanner planner = planner((x, z) -> valley(x, z, 1040), 8, upper, lower);
      assertConnected(planner, 1040, 300, 1040, 700, 990, 250, 1090, 750);
      for (int z = 470; z <= 530; z += 10) assertFalse(planner.sample(1000, z, 112).wet(), "Ways meet on the mapped line at z=" + z);
   }

   @Test void tributaryEndsOnTheCourseOfTheRiverItJoins() {
      OsmWaterFeature main = feature(4, true, OsmWaterKind.RIVER, 1000, 100, 1000, 900);
      OsmWaterFeature tributary = feature(5, true, OsmWaterKind.STREAM, 700, 500, 1000, 500);
      // The main valley's floor lies 30 blocks east of the mapped river; the tributary's valley runs along z = 500.
      InlandHydrologyPlanner planner = planner((x, z) -> Math.min(valley(x, z, 1030),
         x <= 1030 ? 96.0 + (1030 - x) * 0.02 + Math.abs(z - 500) * 0.3 : Double.POSITIVE_INFINITY), 8, main, tributary);
      assertTrue(wetNear(planner, 1030, 300, 3), "Main river not on its valley floor");
      assertConnected(planner, 850, 500, 1030, 500, 690, 440, 1060, 560);
   }

   @Test void leveeKeepsTheRiverInItsChannel() {
      OsmWaterFeature river = feature(6, true, OsmWaterKind.STREAM, 1000, 100, 1000, 900);
      // A channel between levees, with the floodplain behind them lower than the channel.
      InlandHydrologyPlanner planner = planner((x, z) -> {
         double base = 100.0 - z * 0.01, d = Math.abs(x - 1000);
         return d <= 4 ? base : d <= 12 ? base + 3.0 : base - 2.0;
      }, 2, river);
      for (int z = 200; z <= 800; z += 50) {
         assertTrue(wetNear(planner, 1000, z, 2), "River left its channel at z=" + z);
         assertFalse(wetNear(planner, 1030, z, 6), "River moved behind the levee at z=" + z);
      }
   }

   @Test void streamAlongACliffTopStaysOnThePlateau() {
      OsmWaterFeature stream = feature(8, true, OsmWaterKind.STREAM, 1000, 100, 1000, 900);
      // A shallow channel on a plateau whose edge lies 30 blocks east; the floor below is a hundred blocks lower.
      InlandHydrologyPlanner planner = planner((x, z) -> x >= 1030 ? 100.0 - z * 0.01
         : 200.0 - z * 0.01 + Math.min(2.0, Math.abs(x - 1000) * 0.2), 2, stream);
      for (int z = 200; z <= 800; z += 50) {
         assertTrue(wetNear(planner, 1000, z, 3), "Stream left the plateau at z=" + z);
         assertFalse(wetNear(planner, 1045, z, 10), "Stream moved down the cliff at z=" + z);
      }
   }

   @Test void fallStaysWhereItsLineCrossesTheCliff() {
      OsmWaterFeature stream = feature(9, true, OsmWaterKind.STREAM, 800, 500, 1200, 500);
      // The cliff edge runs obliquely: south of the line the floor below is reached well before the mapped crossing.
      InlandHydrologyPlanner planner = planner((x, z) -> {
         double valley = Math.min(3.0, Math.abs(z - 500) * 0.2);
         return x < 1000 + (z - 500) * 0.5 ? 200.0 - x * 0.01 + valley : 100.0 - x * 0.01 + valley;
      }, 2, stream);
      assertTrue(wetNear(planner, 960, 500, 3), "No stream above the fall");
      assertTrue(wetNear(planner, 1040, 500, 3), "No stream below the fall");
      boolean falls = false;
      for (int x = 994; x <= 1006 && !falls; x++) for (int z = 496; z <= 504 && !falls; z++) falls = planner.sample(x, z, 150).falling();
      assertTrue(falls, "The fall moved away from where its line crosses the cliff");
      for (int x = 975; x <= 990; x++) assertFalse(planner.sample(x, 468, 100).wet(), "Stream dropped early at x=" + x);
   }

   @Test void narrowRiverAreaMovesWithItsCourseOntoTheValleyFloor() {
      // A river area 20 blocks wide mapped up the valley side, its floor 40 blocks east.
      OsmWaterFeature area = feature(11, false, OsmWaterKind.RIVER, 990, 60, 1010, 60, 1010, 940, 990, 940, 990, 60);
      OsmWaterFeature river = feature(12, true, OsmWaterKind.RIVER, 1000, 100, 1000, 900);
      InlandHydrologyPlanner planner = planner((x, z) -> valley(x, z, 1040), 8, area, river);
      for (int z = 300; z <= 700; z += 100) {
         int raw = (int)Math.ceil(valley(1040, z, 1040));
         InlandHydrologyPlanner.Column floor = planner.sample(1040, z, raw);
         assertTrue(floor.wet() && floor.water() >= raw - 3, "No water on the valley floor at z=" + z + ": " + floor);
         // The area keeps its width: water across it on the floor, none where it was mapped.
         assertTrue(wetNear(planner, 1032, z, 0) && wetNear(planner, 1048, z, 0), "Area narrowed on the floor at z=" + z);
         assertFalse(wetNear(planner, 1000, z, 6), "Water left where the area was mapped at z=" + z);
      }
   }

   @Test void riverAreaOnItsValleyFloorKeepsItsOutline() {
      OsmWaterFeature area = feature(13, false, OsmWaterKind.RIVER, 1030, 60, 1050, 60, 1050, 940, 1030, 940, 1030, 60);
      OsmWaterFeature river = feature(14, true, OsmWaterKind.RIVER, 1040, 100, 1040, 900);
      InlandHydrologyPlanner planner = planner((x, z) -> valley(x, z, 1040), 8, area, river);
      for (int z = 300; z <= 700; z += 100) {
         for (int x = 1032; x <= 1048; x += 4) assertTrue(planner.sample(x, z, 100).wet(), "Area left its outline at " + x + "," + z);
         assertFalse(planner.sample(1060, z, 112).wet(), "Area spread beyond its outline at z=" + z);
      }
   }

   @Test void tributaryReachesTheRiverAreaWhereItMoved() {
      OsmWaterFeature area = feature(15, false, OsmWaterKind.RIVER, 990, 60, 1010, 60, 1010, 940, 990, 940, 990, 60);
      OsmWaterFeature river = feature(16, true, OsmWaterKind.RIVER, 1000, 100, 1000, 900);
      OsmWaterFeature tributary = feature(17, true, OsmWaterKind.STREAM, 700, 500, 1000, 500);
      // The main valley's floor lies 40 blocks east of the mapped river; the tributary's valley runs along z = 500.
      InlandHydrologyPlanner planner = planner((x, z) -> Math.min(valley(x, z, 1040),
         x <= 1040 ? 92.0 + (1040 - x) * 0.02 + Math.abs(z - 500) * 0.3 : Double.POSITIVE_INFINITY), 8, area, river, tributary);
      assertTrue(wetNear(planner, 1040, 300, 3), "Main river not on its valley floor");
      assertConnected(planner, 850, 500, 1040, 500, 690, 440, 1070, 560);
   }

   @Test void streamDrawnUpstreamFlowsDownhill() {
      // Drawn from the foot of its valley to the head: the ground rises 40 blocks along the line.
      OsmWaterFeature stream = feature(10, true, OsmWaterKind.STREAM, 1000, 900, 1000, 100);
      InlandHydrologyPlanner planner = planner((x, z) -> valley(x, z * 5.0, 1000), 8, stream);
      for (int z = 200; z <= 800; z += 100) {
         int raw = (int)Math.ceil(valley(1000, z * 5.0, 1000));
         InlandHydrologyPlanner.Column column = planner.sample(1000, z, raw);
         assertTrue(column.wet() && column.water() >= raw - 3, "Stream sunk below its valley floor at z=" + z + ": " + column);
      }
   }

   @Test void courseCrossingANetworkTileSeamIsTheSameOnBothSides() {
      // Network tiles are 8192 blocks wide at 1:1: plans either side of x = 8192 read different tiles.
      OsmWaterFeature stream = feature(7, true, OsmWaterKind.STREAM, 7700, 300, 8700, 300);
      InlandHydrologyPlanner planner = planner((x, z) -> 100.0 - x * 0.002 + Math.abs(z - 340) * 0.3, 8, stream);
      assertConnected(planner, 8000, 340, 8400, 340, 7950, 280, 8450, 400);
      for (int x = 8186; x <= 8198; x += 2) assertTrue(wetNear(planner, x, 340, 2), "Seam gap at x=" + x);
   }

   @Test void offsetsFindTheLowGroundAndKeepAnchoredEnds() {
      int samples = 401;
      double[] px = new double[samples], pz = new double[samples], nx = new double[samples], nz = new double[samples];
      for (int k = 0; k < samples; k++) { px[k] = 0.0; pz[k] = k; nx[k] = -1.0; nz[k] = 0.0; }
      // The low ground lies at x = -30 (offset +30 along the normal).
      RiverChannels.Terrain terrain = (x, z) -> Math.abs(x + 30) * 0.5;
      double[] free = RiverCorridor.offsets(px, pz, nx, nz, 1.0, 4, 64, 1.0, terrain, Double.NaN, Double.NaN, null, 24);
      for (int k = 40; k < samples - 40; k += 20) assertEquals(30.0, free[k], 1.0, "Free course at sample " + k);
      double[] anchored = RiverCorridor.offsets(px, pz, nx, nz, 1.0, 4, 64, 1.0, terrain, 0.0, -10.0, null, 24);
      assertEquals(0.0, anchored[0], 1.0E-9);
      assertEquals(-10.0, anchored[samples - 1], 1.0E-9);
      assertEquals(30.0, anchored[samples / 2], 1.0, "Anchored course leaves the low ground");
      for (int k = 1; k < samples; k++) assertTrue(Math.abs(anchored[k] - anchored[k - 1]) <= 1.0, "Course jumps at sample " + k);
   }

   private static boolean wetNear(InlandHydrologyPlanner planner, int x, int z, int radius) {
      for (int dx = -radius; dx <= radius; dx++) if (planner.sample(x + dx, z, 100).wet()) return true;
      return false;
   }
}
