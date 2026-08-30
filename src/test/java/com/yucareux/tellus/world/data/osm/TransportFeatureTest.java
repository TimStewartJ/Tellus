package com.yucareux.tellus.world.data.osm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportFeatureTest {
   @Test
   void mapsOnlyExplicitOvertureSubtypes() {
      assertEquals(TransportKind.RAIL, TransportKind.fromSubtype("rail"));
      assertEquals(TransportKind.RAIL, TransportKind.fromSubtype("RAIL"));
      assertEquals(TransportKind.RAIL, TransportKind.fromSubtype("  rail  "));
      assertEquals(TransportKind.WATER_ROUTE, TransportKind.fromSubtype("water"));
      assertEquals(TransportKind.WATER_ROUTE, TransportKind.fromSubtype("Water"));
      assertNull(TransportKind.fromSubtype("road"));
      assertNull(TransportKind.fromSubtype("ferry"));
      assertNull(TransportKind.fromSubtype("railway"));
      assertNull(TransportKind.fromSubtype(""));
      assertNull(TransportKind.fromSubtype(null));
   }

   @Test
   void exposesOvertureSubtypeTokensAndOrdinals() {
      assertEquals("rail", TransportKind.RAIL.overtureSubtype());
      assertEquals("water", TransportKind.WATER_ROUTE.overtureSubtype());
      assertEquals(TransportKind.RAIL, TransportKind.fromOrdinal(TransportKind.RAIL.ordinal()));
      assertEquals(TransportKind.WATER_ROUTE, TransportKind.fromOrdinal(TransportKind.WATER_ROUTE.ordinal()));
      assertNull(TransportKind.fromOrdinal(-1));
      assertNull(TransportKind.fromOrdinal(TransportKind.values().length));
   }

   @Test
   void normalizesClassAndSubclassTokens() {
      TransportFeature feature = new TransportFeature(
         42L, TransportKind.RAIL, " Light-Rail ", "Station Approach", new double[]{-99.1, -99.09}, new double[]{19.4, 19.41}
      );

      assertEquals("light_rail", feature.transportClass());
      assertEquals("station_approach", feature.subclass());
      assertTrue(feature.matchesClass("LIGHT-RAIL"));
      assertFalse(feature.matchesClass("subway"));
      assertTrue(feature.isRail());
      assertFalse(feature.isWaterRoute());
   }

   @Test
   void treatsMissingClassAndSubclassAsEmpty() {
      TransportFeature feature = new TransportFeature(
         7L, TransportKind.WATER_ROUTE, null, null, new double[]{-99.1, -99.09}, new double[]{19.4, 19.41}
      );

      assertEquals("", feature.transportClass());
      assertEquals("", feature.subclass());
      assertTrue(feature.isWaterRoute());
      assertFalse(feature.isRail());
   }

   @Test
   void computesBoundsAndIntersections() {
      TransportFeature feature = new TransportFeature(
         1L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05, -99.08}, new double[]{19.40, 19.42, 19.45}
      );

      assertEquals(3, feature.pointCount());
      assertEquals(-99.10, feature.minLon(), 1.0E-9);
      assertEquals(-99.05, feature.maxLon(), 1.0E-9);
      assertEquals(19.40, feature.minLat(), 1.0E-9);
      assertEquals(19.45, feature.maxLat(), 1.0E-9);
      assertEquals(-99.05, feature.lonAt(1), 1.0E-9);
      assertEquals(19.42, feature.latAt(1), 1.0E-9);
      assertTrue(feature.intersects(19.41, -99.09, 19.43, -99.06));
      assertFalse(feature.intersects(19.50, -99.09, 19.60, -99.06));
      assertFalse(feature.intersects(19.41, -98.00, 19.43, -97.00));
   }

   @Test
   void isImmutableAgainstCallerAndAccessorArrays() {
      double[] longitudes = {-99.10, -99.05};
      double[] latitudes = {19.40, 19.42};
      TransportFeature feature = new TransportFeature(5L, TransportKind.WATER_ROUTE, "ferry", "", longitudes, latitudes);

      longitudes[0] = 0.0;
      latitudes[0] = 0.0;
      assertEquals(-99.10, feature.lonAt(0), 1.0E-9);
      assertEquals(19.40, feature.latAt(0), 1.0E-9);

      double[] exposed = feature.longitudes();
      exposed[0] = 1.0;
      assertEquals(-99.10, feature.lonAt(0), 1.0E-9);
      double[] exposedLat = feature.latitudes();
      exposedLat[0] = 1.0;
      assertEquals(19.40, feature.latAt(0), 1.0E-9);
   }

   @Test
   void rejectsDegenerateGeometry() {
      assertThrows(
         IllegalArgumentException.class,
         () -> new TransportFeature(1L, TransportKind.RAIL, "rail", "", new double[]{-99.1}, new double[]{19.4})
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> new TransportFeature(1L, TransportKind.RAIL, "rail", "", new double[]{-99.1, -99.0}, new double[]{19.4})
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> new TransportFeature(1L, TransportKind.RAIL, "rail", "", new double[]{-99.1, Double.NaN}, new double[]{19.4, 19.5})
      );
      assertThrows(
         NullPointerException.class,
         () -> new TransportFeature(1L, null, "rail", "", new double[]{-99.1, -99.0}, new double[]{19.4, 19.5})
      );
   }

   @Test
   void dedupeKeySeparatesDistinctTileClippedPieces() {
      TransportFeature west = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}
      );
      TransportFeature east = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.05, -99.00}, new double[]{19.42, 19.44}
      );
      TransportFeature duplicate = new TransportFeature(
         900L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.05}, new double[]{19.40, 19.42}
      );

      assertNotEquals(west.dedupeKey(), east.dedupeKey());
      assertEquals(west.dedupeKey(), duplicate.dedupeKey());
      assertEquals(west, duplicate);
      assertEquals(west.hashCode(), duplicate.hashCode());
      assertNotEquals(west, east);
   }
}
