package com.yucareux.tellus.world.data.osm;

import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusOsmRoadSourceTransportTest {
   private static final int ZOOM = 14;
   private static final int TILE_X = 3711;
   private static final int TILE_Y = 7217;
   private static final int EXTENT = 4096;
   private static final int[] LINE_POINTS = {512, 512, 1024, 900, 2048, 1600};

   @Test
   void parsesExplicitRailAndWaterSegmentsWithoutTouchingRoads() {
      byte[] payload = tile(
         segment(1L, Map.of("subtype", "road", "class", "residential"), LINE_POINTS),
         segment(2L, Map.of("subtype", "rail", "class", "subway", "subclass", "tunnel"), LINE_POINTS),
         segment(3L, Map.of("subtype", "water", "class", "ferry"), LINE_POINTS)
      );

      OverpassRoadTile parsed = TellusOsmRoadSource.parseVectorTile(payload, bounds(), key());

      assertEquals(1, parsed.features().size());
      RoadFeature road = parsed.features().get(0);
      assertEquals(RoadClass.NORMAL, road.roadClass());
      assertEquals("residential", road.highwayTag());

      assertEquals(2, parsed.transportFeatures().size());
      TransportFeature rail = parsed.transportFeatures().get(0);
      assertEquals(TransportKind.RAIL, rail.kind());
      assertEquals("subway", rail.transportClass());
      assertEquals("tunnel", rail.subclass());

      TransportFeature water = parsed.transportFeatures().get(1);
      assertEquals(TransportKind.WATER_ROUTE, water.kind());
      assertEquals("ferry", water.transportClass());
      assertEquals("", water.subclass());
      assertTrue(water.isWaterRoute());
   }

   @Test
   void railAndWaterGeometryMatchesRoadGeometryDecoding() {
      byte[] payload = tile(
         segment(1L, Map.of("subtype", "road", "class", "residential"), LINE_POINTS),
         segment(2L, Map.of("subtype", "rail", "class", "rail"), LINE_POINTS)
      );

      OverpassRoadTile parsed = TellusOsmRoadSource.parseVectorTile(payload, bounds(), key());
      RoadFeature road = parsed.features().get(0);
      TransportFeature rail = parsed.transportFeatures().get(0);

      assertEquals(LINE_POINTS.length / 2, rail.pointCount());
      assertEquals(road.pointCount(), rail.pointCount());
      for (int i = 0; i < rail.pointCount(); i++) {
         assertEquals(road.lonAt(i), rail.lonAt(i), 1.0E-9);
         assertEquals(road.latAt(i), rail.latAt(i), 1.0E-9);
      }

      TellusOsmRoadSource.TileGeoBounds tileBounds = TellusOsmRoadSource.tileBounds(key());
      assertTrue(rail.minLon() >= tileBounds.west() && rail.maxLon() <= tileBounds.east());
      assertTrue(rail.minLat() >= tileBounds.south() && rail.maxLat() <= tileBounds.north());
   }

   @Test
   void keepsTileWithOnlyTransportSegments() {
      byte[] payload = tile(segment(9L, Map.of("subtype", "rail", "class", "tram"), LINE_POINTS));

      OverpassRoadTile parsed = TellusOsmRoadSource.parseVectorTile(payload, bounds(), key());

      assertTrue(parsed.features().isEmpty());
      assertTrue(parsed.areaFeatures().isEmpty());
      assertEquals(1, parsed.transportFeatures().size());
      assertFalse(parsed.isEmpty());
   }

   @Test
   void neverInfersTransportFromOrdinaryRoads() {
      byte[] payload = tile(
         segment(1L, Map.of("subtype", "road", "class", "rail"), LINE_POINTS),
         segment(2L, Map.of("subtype", "road", "class", "ferry"), LINE_POINTS),
         segment(3L, Map.of("subtype", "road", "class", "motorway", "subclass", "link"), LINE_POINTS),
         segment(4L, Map.of("class", "residential"), LINE_POINTS)
      );

      OverpassRoadTile parsed = TellusOsmRoadSource.parseVectorTile(payload, bounds(), key());

      assertTrue(parsed.transportFeatures().isEmpty());
      assertEquals(3, parsed.features().size());
   }

   @Test
   void ignoresUnknownNonRoadSubtypes() {
      byte[] payload = tile(
         segment(1L, Map.of("subtype", "cable_car", "class", "gondola"), LINE_POINTS),
         segment(2L, Map.of("subtype", "", "class", "rail"), LINE_POINTS)
      );

      OverpassRoadTile parsed = TellusOsmRoadSource.parseVectorTile(payload, bounds(), key());

      assertTrue(parsed.transportFeatures().isEmpty());
      assertTrue(parsed.features().isEmpty());
   }

   @Test
   void buildsTransportFeatureOnlyForExplicitSubtypes() {
      double[] longitudes = {-99.10, -99.05};
      double[] latitudes = {19.40, 19.42};

      TransportFeature rail = TellusOsmRoadSource.buildTransportFeature(
         11L, Map.of("subtype", "rail", "class", "light_rail"), longitudes, latitudes
      );
      assertNotNull(rail);
      assertEquals(TransportKind.RAIL, rail.kind());
      assertEquals("light_rail", rail.transportClass());
      assertEquals(11L, rail.featureId());

      assertNull(TellusOsmRoadSource.buildTransportFeature(12L, Map.of("subtype", "road", "class", "primary"), longitudes, latitudes));
      assertNull(TellusOsmRoadSource.buildTransportFeature(13L, Map.of("class", "rail"), longitudes, latitudes));
      assertNull(TellusOsmRoadSource.buildTransportFeature(14L, null, longitudes, latitudes));
      assertNull(TellusOsmRoadSource.buildTransportFeature(15L, Map.of("subtype", "rail"), new double[]{-99.1}, new double[]{19.4}));
      assertNull(TellusOsmRoadSource.buildTransportFeature(16L, Map.of("subtype", "rail"), longitudes, new double[]{19.4}));
      assertNull(TellusOsmRoadSource.buildTransportFeature(17L, Map.of("subtype", "water"), null, latitudes));
   }

   @Test
   void resolvesSubclassFromScopedRulesBeforePlainTag() {
      Map<String, Object> tags = new LinkedHashMap<>();
      tags.put("subtype", "rail");
      tags.put("class", "rail");
      tags.put("subclass", "plain");
      tags.put("subclass_rules", "[{\"value\":\"scoped\"}]");

      TransportFeature feature = TellusOsmRoadSource.buildTransportFeature(
         21L, tags, new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}
      );

      assertNotNull(feature);
      assertEquals("scoped", feature.subclass());
   }

   @Test
   void dedupesOnlyExactDuplicateTransportGeometry() {
      TransportFeature west = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}
      );
      TransportFeature east = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.05, -99.00}, new double[]{19.42, 19.44}
      );
      TransportFeature duplicateOfWest = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}
      );

      List<TransportFeature> collected = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      TellusOsmRoadSource.appendTransport(collected, seen, List.of(west, east));
      TellusOsmRoadSource.appendTransport(collected, seen, List.of(duplicateOfWest, east));

      assertEquals(2, collected.size());
      assertEquals(west, collected.get(0));
      assertEquals(east, collected.get(1));
   }

   @Test
   void transportQueryResultIsImmutableAndReportsCacheMisses() {
      List<TransportFeature> source = new ArrayList<>();
      source.add(new TransportFeature(1L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}));

      TellusOsmRoadSource.TransportQueryResult result = new TellusOsmRoadSource.TransportQueryResult(source, true);
      source.clear();

      assertEquals(1, result.features().size());
      assertTrue(result.hadCacheMiss());
      assertThrowsUnsupported(result.features());
      assertTrue(new TellusOsmRoadSource.TransportQueryResult(null, false).features().isEmpty());
      assertFalse(new TellusOsmRoadSource.TransportQueryResult(null, false).hadCacheMiss());
   }

   private static void assertThrowsUnsupported(List<TransportFeature> features) {
      try {
         features.add(null);
         throw new AssertionError("Expected an immutable feature list");
      } catch (UnsupportedOperationException | NullPointerException expected) {
         // immutable list
      }
   }

   private static TellusOsmRoadSource.TileKey key() {
      return new TellusOsmRoadSource.TileKey(ZOOM, TILE_X, TILE_Y);
   }

   private static TellusOsmRoadSource.TileGeoBounds bounds() {
      return TellusOsmRoadSource.tileBounds(key());
   }

   private static byte[] tile(SegmentSpec... segments) {
      Layer.Builder layer = Layer.newBuilder().setName("segment").setExtent(EXTENT).setVersion(2);
      List<String> keys = new ArrayList<>();
      List<String> values = new ArrayList<>();

      for (SegmentSpec spec : segments) {
         Feature.Builder feature = Feature.newBuilder().setId(spec.id()).setType(GeomType.LINESTRING);
         for (Map.Entry<String, String> entry : spec.tags().entrySet()) {
            feature.addTags(indexOf(keys, entry.getKey()));
            feature.addTags(indexOf(values, entry.getValue()));
         }

         for (int command : encodeLine(spec.tilePoints())) {
            feature.addGeometry(command);
         }

         layer.addFeatures(feature.build());
      }

      for (String key : keys) {
         layer.addKeys(key);
      }
      for (String value : values) {
         layer.addValues(Value.newBuilder().setStringValue(value).build());
      }

      return Tile.newBuilder().addLayers(layer.build()).build().toByteArray();
   }

   private static SegmentSpec segment(long id, Map<String, String> tags, int[] tilePoints) {
      return new SegmentSpec(id, new LinkedHashMap<>(tags), tilePoints);
   }

   private static int indexOf(List<String> pool, String value) {
      int index = pool.indexOf(value);
      if (index >= 0) {
         return index;
      }

      pool.add(value);
      return pool.size() - 1;
   }

   private static List<Integer> encodeLine(int[] tilePoints) {
      List<Integer> geometry = new ArrayList<>();
      int cursorX = 0;
      int cursorY = 0;
      geometry.add(9);
      geometry.add(zigZag(tilePoints[0] - cursorX));
      geometry.add(zigZag(tilePoints[1] - cursorY));
      cursorX = tilePoints[0];
      cursorY = tilePoints[1];

      int lineToCount = tilePoints.length / 2 - 1;
      geometry.add((lineToCount << 3) | 2);
      for (int i = 1; i <= lineToCount; i++) {
         int x = tilePoints[i * 2];
         int y = tilePoints[i * 2 + 1];
         geometry.add(zigZag(x - cursorX));
         geometry.add(zigZag(y - cursorY));
         cursorX = x;
         cursorY = y;
      }

      return geometry;
   }

   private static int zigZag(int value) {
      return (value << 1) ^ (value >> 31);
   }

   private record SegmentSpec(long id, Map<String, String> tags, int[] tilePoints) {
   }
}
