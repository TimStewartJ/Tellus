package com.yucareux.tellus.worldgen.landmark;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class LandmarkCatalogTest {
   // Building a volume logs through Tellus.LOGGER, which initializes TellusCommon's registry keys.
   private static final String FORGE_BOOTSTRAP = "Forge's raw JUnit bootstrap cannot initialize vanilla registries";

   private static final double TOWER_LAT = 48.858261;
   private static final double TOWER_LON = 2.294498;
   private static final String TOWER_BUILDING = "930b868b-1163-4f10-b7ee-d3e3bf82bf7a";

   @Test
   void shipsTheEiffelTower() {
      LandmarkCatalog.Entry entry = LandmarkCatalog.entries().stream()
         .filter(candidate -> candidate.definition().id().equals("eiffel_tower")).findFirst().orElseThrow();
      LandmarkMesh mesh = entry.mesh();
      assertNotNull(mesh);
      assertEquals(330.0, mesh.height(), 0.1);
      assertEquals(124.9, mesh.max(0) - mesh.min(0), 0.1);
      assertEquals(124.9, mesh.max(2) - mesh.min(2), 0.1);
   }

   @Test
   void replacesTheTowersOwnBuildingsAndWhatStandsUnderIt() {
      // A part of the tower's Overture building far from its centroid still goes.
      assertTrue(LandmarkCatalog.suppressesBuilding(feature(TOWER_BUILDING, 0.0, 74.0)));
      // A kiosk between the legs.
      assertTrue(LandmarkCatalog.suppressesBuilding(feature("kiosk", 18.0, -48.0)));
      // The transmitter building beside the south pillar and the nearby flats stay.
      assertFalse(LandmarkCatalog.suppressesBuilding(feature("tdf", -24.6, -90.9)));
      assertFalse(LandmarkCatalog.suppressesBuilding(feature("flats", 155.0, 145.6)));
   }

   @Test
   void keepsTheSameListWhenNothingIsReplaced() {
      List<OsmBuildingFeature> far = List.of(feature("flats", 900.0, 900.0));
      assertSame(far, LandmarkCatalog.withoutSuppressedBuildings(far));
      assertEquals(1, LandmarkCatalog.withoutSuppressedBuildings(List.of(feature(TOWER_BUILDING, 0.0, 0.0), far.get(0))).size());
   }

   @Test
   void scalesLikeTheMapAroundIt() {
      assumeFalse(isMinecraftForge(), FORGE_BOOTSTRAP);
      double sec = EarthProjection.heightScaleCorrectionAtLatitude(TOWER_LAT);
      for (double scale : new double[]{5.0, 15.0}) {
         LandmarkPlacement placement = TellusLandmarks.create(scale, true, (x, z) -> 64).placements().get(0);
         LandmarkVolume volume = placement.volume();
         assertEquals(330.0 * sec / scale, placement.heightBlocks(), 1.0e-6);
         assertEquals(Math.ceil(330.0 * sec / scale), volume.height(), 1.5);
         // Turned about 45 degrees, the 124.9 m base spans its diagonal along x and z (plus a block of margin each side).
         double diagonal = 124.9 * Math.sqrt(2.0) * sec / scale;
         int width = volume.maxX() - volume.minX() + 1;
         assertTrue(width >= diagonal && width <= diagonal + 5.0, () -> "volume " + width + " blocks wide for a " + diagonal + " block diagonal");
         assertEquals(65, placement.baseY());
         assertEquals(64, placement.flattenedSurface(volume.minX() + (volume.maxX() - volume.minX()) / 2, volume.minZ() + (volume.maxZ() - volume.minZ()) / 2));
      }
   }

   @Test
   void isLeftOutWhenBuildingsAreOff() {
      assertTrue(TellusLandmarks.create(1.0, false, (x, z) -> 64).placements().isEmpty());
   }

   @Test
   void shipsTheGoldenGateBridgeWithItsOwnBlocks() {
      LandmarkMesh mesh = bridge().mesh();
      assertNotNull(mesh);
      assertTrue(mesh.hasBlocks());
      assertTrue(List.of(mesh.blocks()).containsAll(List.of("red_concrete", "gray_concrete", "light_gray_concrete", "bricks")));
      // From 39.3 m below the sea (the south pier's fender) to the tower tops, 227.9 m above it.
      assertEquals(267.3, mesh.height(), 0.2);
      assertEquals(2808.0, mesh.max(2) - mesh.min(2), 2.0);
   }

   @Test
   void standsInTheSeaAtItsRealHeight() {
      assumeFalse(isMinecraftForge(), FORGE_BOOTSTRAP);
      double scale = 1.0;
      LandmarkPlacement placement = TellusLandmarks.create(scale, true, (x, z) -> 200).placements().stream()
         .filter(candidate -> candidate.id().equals("golden_gate_bridge")).findFirst().orElseThrow();
      assertFalse(placement.standsOnTerrain());
      double blocksPerMeter = EarthProjection.heightScaleCorrectionAtLatitude(BRIDGE_LAT) / scale;
      // Ground at sea level is Y 63 here, whatever the terrain sampler says.
      assertEquals(63 + Math.round(-39.33 * blocksPerMeter) + 1, placement.baseY(), 1);
      LandmarkVolume volume = placement.volume();
      int towerTop = placement.baseY() + placement.highestLayer(volume.minX(), volume.minZ(), Math.max(volume.maxX() - volume.minX(), volume.maxZ() - volume.minZ()) + 1);
      assertEquals(63 + 227.9 * blocksPerMeter, towerTop, 2.0);
      assertEquals(Integer.MIN_VALUE, placement.flattenedSurface((volume.minX() + volume.maxX()) / 2, (volume.minZ() + volume.maxZ()) / 2));
   }

   @Test
   void carriesTheMapsBridgeRoadsAlongItsDeck() {
      // Two points on the deck: centres of road surface far apart along the main span.
      double[] south = deckPoint(-500.0);
      double[] north = deckPoint(500.0);
      RoadFeature onDeck = road(1L, RoadMode.BRIDGE, south, north, 0.0);
      assertTrue(LandmarkCatalog.replacesRoad(onDeck));
      assertTrue(LandmarkCatalog.withoutReplacedRoads(List.of(onDeck)).isEmpty());
      // A bridge 150 m east of it, and a road that is not a bridge, stay.
      assertFalse(LandmarkCatalog.replacesRoad(road(1L, RoadMode.BRIDGE, south, north, 150.0)));
      assertFalse(LandmarkCatalog.replacesRoad(road(1L, RoadMode.NORMAL, south, north, 0.0)));
   }

   @Test
   void takesTheGapsInACarriedRoadsBridgeTaggingWithIt() {
      // The map splits US-101 where its bridge tagging has gaps; the gaps under the Marin viaduct would lie on the valley floor.
      double[] a = deckPoint(-500.0), b = deckPoint(-100.0), c = deckPoint(100.0), d = deckPoint(500.0);
      RoadFeature bridge = road(7L, RoadMode.BRIDGE, a, b, 0.0);
      RoadFeature gap = road(7L, RoadMode.NORMAL, b, c, 0.0);
      // The road at the bridge's San Francisco end (back on the ground, where the deck sinks below it), the same road
      // beside the landmark, and another road the deck passes over all stay.
      RoadFeature end = road(7L, RoadMode.NORMAL, deckPoint(-1390.0), deckPoint(-1350.0), 0.0);
      RoadFeature beside = road(7L, RoadMode.NORMAL, a, d, 150.0);
      RoadFeature under = road(8L, RoadMode.NORMAL, b, c, 0.0);
      assertEquals(List.of(end, beside, under), LandmarkCatalog.withoutReplacedRoads(List.of(bridge, gap, end, beside, under)));
   }

   @Test
   void meetsTheMarinGroundAtTheRoadsHeight() {
      // The map's ground: the north anchorage's top at 66-67 m, and US-101 leaving the bridge for the hillside at 74 m,
      // 1380 m north of the centre (where the source model's deck had sunk to 31 m).
      for (double north = 980.0; north <= 1050.0; north += 10.0) {
         double deck = cutElevations(north, "gray_concrete")[1];
         assertTrue(deck > 68.0, () -> "deck at " + deck + " m over the anchorage");
      }
      assertEquals(74.0, cutElevations(1380.0, "gray_concrete")[1], 1.0);
   }

   @Test
   void standsTheMarinApproachOnFourTowers() {
      // The ~300 m viaduct from the north anchorage to the hillside: four towers reaching into the ground, open between.
      int towers = 0;
      boolean standing = false;
      for (double north = 1060.0; north <= 1360.0; north += 2.0) {
         boolean reachesDown = cutElevations(north, null)[0] < 0.0;
         if (reachesDown && !standing) {
            towers++;
         }
         standing = reachesDown;
      }
      assertEquals(4, towers);
   }

   @Test
   void replacesFortPointButNotThePresidio() {
      double[] wall = firstPointOf("bricks");
      assertTrue(LandmarkCatalog.suppressesBuilding(featureAt("fort-point", wall[0], wall[1])));
      assertFalse(LandmarkCatalog.suppressesBuilding(featureAt("presidio", 37.81050, -122.47450)));
   }

   private static final double BRIDGE_LAT = 37.8200668;
   private static final double BRIDGE_LON = -122.4783550;

   private static LandmarkCatalog.Entry bridge() {
      return LandmarkCatalog.entries().stream().filter(candidate -> candidate.definition().id().equals("golden_gate_bridge")).findFirst().orElseThrow();
   }

   /** Latitude and longitude of the middle of the road surface the mesh has nearest {@code northMeters} north of its centre. */
   private static double[] deckPoint(double northMeters) {
      LandmarkMesh mesh = bridge().mesh();
      int road = List.of(mesh.blocks()).indexOf("gray_concrete");
      double sumX = 0.0, sumZ = 0.0;
      int count = 0;
      for (int t = 0; t < mesh.triangleCount(); t++) {
         double z = (mesh.coordinate(t, 0, 2) + mesh.coordinate(t, 1, 2) + mesh.coordinate(t, 2, 2)) / 3.0;
         if (mesh.triangleBlock(t) == road && Math.abs(-z - northMeters) < 20.0) {
            sumX += (mesh.coordinate(t, 0, 0) + mesh.coordinate(t, 1, 0) + mesh.coordinate(t, 2, 0)) / 3.0;
            sumZ += z;
            count++;
         }
      }
      double east = sumX / count, northOfCentre = -sumZ / count;
      return new double[]{BRIDGE_LAT + northOfCentre / LandmarkCatalog.METERS_PER_DEGREE,
         BRIDGE_LON + east / (LandmarkCatalog.METERS_PER_DEGREE * Math.cos(Math.toRadians(BRIDGE_LAT)))};
   }

   /**
    * Real elevations (metres) of the lowest and highest points where the triangles painted {@code block} (all of them
    * for null) cross {@code northMeters} north of the centre.
    */
   private static double[] cutElevations(double northMeters, String block) {
      LandmarkMesh mesh = bridge().mesh();
      int painted = block == null ? -1 : List.of(mesh.blocks()).indexOf(block);
      double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
      for (int t = 0; t < mesh.triangleCount(); t++) {
         if (block != null && mesh.triangleBlock(t) != painted) {
            continue;
         }
         for (int e = 0; e < 3; e++) {
            double a = -mesh.coordinate(t, e, 2), b = -mesh.coordinate(t, (e + 1) % 3, 2);
            if (a != b && (a - northMeters) * (b - northMeters) <= 0.0) {
               double ya = mesh.coordinate(t, e, 1), yb = mesh.coordinate(t, (e + 1) % 3, 1);
               double y = ya + (yb - ya) * (northMeters - a) / (b - a);
               low = Math.min(low, y);
               high = Math.max(high, y);
            }
         }
      }
      double base = bridge().definition().baseElevationMeters();
      return new double[]{low + base, high + base};
   }

   /** Latitude and longitude of the first triangle painted with {@code block} (Fort Point's walls for bricks). */
   private static double[] firstPointOf(String block) {
      LandmarkMesh mesh = bridge().mesh();
      int index = List.of(mesh.blocks()).indexOf(block);
      for (int t = 0; t < mesh.triangleCount(); t++) {
         if (mesh.triangleBlock(t) == index) {
            double east = (mesh.coordinate(t, 0, 0) + mesh.coordinate(t, 1, 0) + mesh.coordinate(t, 2, 0)) / 3.0;
            double north = -(mesh.coordinate(t, 0, 2) + mesh.coordinate(t, 1, 2) + mesh.coordinate(t, 2, 2)) / 3.0;
            return new double[]{BRIDGE_LAT + north / LandmarkCatalog.METERS_PER_DEGREE,
               BRIDGE_LON + east / (LandmarkCatalog.METERS_PER_DEGREE * Math.cos(Math.toRadians(BRIDGE_LAT)))};
         }
      }
      throw new AssertionError("no " + block);
   }

   private static RoadFeature road(long wayId, RoadMode mode, double[] from, double[] to, double eastShiftMeters) {
      double shift = eastShiftMeters / (LandmarkCatalog.METERS_PER_DEGREE * Math.cos(Math.toRadians(BRIDGE_LAT)));
      return new RoadFeature(wayId, RoadClass.MAIN, mode, 1, "motorway", new double[]{from[1] + shift, to[1] + shift}, new double[]{from[0], to[0]});
   }

   private static OsmBuildingFeature featureAt(String buildingId, double lat, double lon) {
      double side = 0.00002;
      return new OsmBuildingFeature(
         OsmBuildingKind.FOOTPRINT,
         buildingId.hashCode(),
         buildingId,
         false,
         new OsmBuildingMetadata(null, null, null, null, 1, null, null, null, null, null),
         6.0,
         0.0,
         new double[][]{{lon - side, lon + side, lon + side, lon - side, lon - side}},
         new double[][]{{lat - side, lat - side, lat + side, lat + side, lat - side}}
      );
   }

   private static OsmBuildingFeature feature(String buildingId, double eastMeters, double northMeters) {
      double lat = TOWER_LAT + northMeters / LandmarkCatalog.METERS_PER_DEGREE;
      double lon = TOWER_LON + eastMeters / (LandmarkCatalog.METERS_PER_DEGREE * Math.cos(Math.toRadians(TOWER_LAT)));
      double side = 0.00002;
      return new OsmBuildingFeature(
         OsmBuildingKind.FOOTPRINT,
         buildingId.hashCode(),
         buildingId,
         false,
         new OsmBuildingMetadata(null, null, null, null, 1, null, null, null, null, null),
         6.0,
         0.0,
         new double[][]{{lon - side, lon + side, lon + side, lon - side, lon - side}},
         new double[][]{{lat - side, lat - side, lat + side, lat + side, lat - side}}
      );
   }

   private static boolean isMinecraftForge() {
      try {
         Class.forName("net.minecraftforge.fml.ModList", false, LandmarkCatalogTest.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }
}
