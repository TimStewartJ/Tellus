package com.yucareux.tellus.world.data.osm;

import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusOsmPlaceSourceTest {
   private static final int ZOOM = 2;
   private static final int TILE_X = 1;
   private static final int TILE_Y = 1;
   private static final int EXTENT = 4096;

   @Test
   void parsesNamedPointFeaturesFromLandAndWaterLayers() {
      OsmPlaceTile tile = TellusOsmPlaceSource.parseVectorTile(
         vectorTile(
            layer(
               "land",
               point(10, 1024, 2048, tags("id", "10", "@name", "Half Dome", "class", "peak", "subtype", "physical", "elevation", 2694, "wikidata", "Q251365")),
               point(11, 2048, 1024, tags("id", "11", "names", "{\"primary\":\"Royal Arches\"}", "class", "cliff", "subtype", "physical")),
               point(12, 3072, 2048, tags("class", "peak")),
               line(13, tags("@name", "Named Line"))
            ),
            layer(
               "water",
               point(20, 3072, 1024, tags("@name", "Vernal Fall", "class", "waterfall", "subtype", "physical"))
            )
         ),
         ZOOM,
         TILE_X,
         TILE_Y
      );

      assertEquals(3, tile.features().size());
      OsmPlaceFeature halfDome = tile.features().get(0);
      assertEquals("10", halfDome.id());
      assertEquals("Half Dome", halfDome.name());
      assertEquals("land", halfDome.layer());
      assertEquals("physical", halfDome.subtype());
      assertEquals("peak", halfDome.placeClass());
      assertEquals(2694.0, halfDome.elevationMeters());
      assertEquals("Q251365", halfDome.wikidata());
      assertEquals(tileLatitude(ZOOM, TILE_Y, 0.5), halfDome.latitude(), 1.0E-4);
      assertEquals(tileLongitude(ZOOM, TILE_X, 0.25), halfDome.longitude(), 1.0E-4);

      OsmPlaceFeature namedFromJson = tile.features().get(1);
      assertEquals("Royal Arches", namedFromJson.name());
      assertTrue(Double.isNaN(namedFromJson.elevationMeters()));
      assertNull(namedFromJson.wikidata());

      OsmPlaceFeature waterfall = tile.features().get(2);
      assertEquals("water", waterfall.layer());
      assertEquals("Vernal Fall", waterfall.name());
   }

   @Test
   void boundsFilterKeepsOnlyPlacesInsideRectangle() {
      OsmPlaceTile tile = TellusOsmPlaceSource.parseVectorTile(
         vectorTile(
            layer(
               "land",
               point(1, 512, 512, tags("@name", "Inside")),
               point(2, 3500, 3500, tags("@name", "Outside"))
            )
         ),
         ZOOM,
         TILE_X,
         TILE_Y
      );

      OsmPlaceFeature inside = tile.features().get(0);
      List<OsmPlaceFeature> matches = tile.featuresInBounds(
         inside.latitude() - 0.01,
         inside.longitude() - 0.01,
         inside.latitude() + 0.01,
         inside.longitude() + 0.01
      );

      assertEquals(1, matches.size());
      assertEquals("Inside", matches.get(0).name());
      assertFalse(matches.stream().anyMatch(feature -> "Outside".equals(feature.name())));
   }

   private static byte[] vectorTile(Layer.Builder... layers) {
      Tile.Builder tile = Tile.newBuilder();
      for (Layer.Builder layer : layers) {
         tile.addLayers(layer.build());
      }
      return tile.build().toByteArray();
   }

   private static Layer.Builder layer(String name, FeatureSpec... features) {
      Layer.Builder layer = Layer.newBuilder().setName(name).setExtent(EXTENT).setVersion(2);
      List<String> keys = new ArrayList<>();
      List<Object> values = new ArrayList<>();
      for (FeatureSpec spec : features) {
         Feature.Builder feature = Feature.newBuilder().setId(spec.id()).setType(spec.type());
         for (Map.Entry<String, Object> entry : spec.tags().entrySet()) {
            feature.addTags(indexOfString(keys, entry.getKey()));
            feature.addTags(indexOfObject(values, entry.getValue()));
         }
         feature.addAllGeometry(spec.geometry());
         layer.addFeatures(feature);
      }
      keys.forEach(layer::addKeys);
      values.stream().map(TellusOsmPlaceSourceTest::value).forEach(layer::addValues);
      return layer;
   }

   private static FeatureSpec point(long id, int x, int y, Map<String, Object> tags) {
      return new FeatureSpec(id, GeomType.POINT, tags, List.of(9, zigZag(x), zigZag(y)));
   }

   private static FeatureSpec line(long id, Map<String, Object> tags) {
      return new FeatureSpec(id, GeomType.LINESTRING, tags, List.of(9, 0, 0, 10, zigZag(100), zigZag(100)));
   }

   private static Map<String, Object> tags(Object... values) {
      Map<String, Object> tags = new LinkedHashMap<>();
      for (int i = 0; i + 1 < values.length; i += 2) {
         tags.put(String.valueOf(values[i]), values[i + 1]);
      }
      return tags;
   }

   private static int indexOfString(List<String> pool, String value) {
      int index = pool.indexOf(value);
      if (index >= 0) {
         return index;
      }
      pool.add(value);
      return pool.size() - 1;
   }

   private static int indexOfObject(List<Object> pool, Object value) {
      int index = pool.indexOf(value);
      if (index >= 0) {
         return index;
      }
      pool.add(value);
      return pool.size() - 1;
   }

   private static Value value(Object value) {
      Value.Builder builder = Value.newBuilder();
      if (value instanceof Number number) {
         builder.setDoubleValue(number.doubleValue());
      } else {
         builder.setStringValue(String.valueOf(value));
      }
      return builder.build();
   }

   private static double tileLongitude(int zoom, int tileX, double fraction) {
      return (tileX + fraction) / (1 << zoom) * 360.0 - 180.0;
   }

   private static double tileLatitude(int zoom, int tileY, double fraction) {
      return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (tileY + fraction) / (1 << zoom)))));
   }

   private static int zigZag(int value) {
      return (value << 1) ^ (value >> 31);
   }

   private record FeatureSpec(long id, GeomType type, Map<String, Object> tags, List<Integer> geometry) {
   }
}
