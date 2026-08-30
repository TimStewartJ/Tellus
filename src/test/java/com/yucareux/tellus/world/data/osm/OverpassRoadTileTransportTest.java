package com.yucareux.tellus.world.data.osm;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverpassRoadTileTransportTest {
   private static final TransportFeature RAIL = new TransportFeature(
      1L, TransportKind.RAIL, "rail", "", new double[]{-99.10, -99.09}, new double[]{19.40, 19.41}
   );
   private static final TransportFeature FERRY = new TransportFeature(
      2L, TransportKind.WATER_ROUTE, "ferry", "", new double[]{-99.02, -99.01}, new double[]{19.48, 19.49}
   );
   private static final RoadFeature ROAD = new RoadFeature(
      3L, RoadClass.NORMAL, RoadMode.NORMAL, 0, "residential", new double[]{-99.10, -99.09}, new double[]{19.40, 19.41}
   );

   @Test
   void filtersTransportFeaturesByBounds() {
      OverpassRoadTile tile = new OverpassRoadTile(List.of(ROAD), List.of(), List.of(RAIL, FERRY), 19.35, -99.15, 19.55, -98.95);

      List<TransportFeature> nearRail = tile.transportFeaturesInBounds(19.395, -99.11, 19.415, -99.085);
      assertEquals(1, nearRail.size());
      assertEquals(TransportKind.RAIL, nearRail.get(0).kind());

      List<TransportFeature> nearFerry = tile.transportFeaturesInBounds(19.47, -99.03, 19.50, -99.00);
      assertEquals(1, nearFerry.size());
      assertEquals(TransportKind.WATER_ROUTE, nearFerry.get(0).kind());

      assertEquals(2, tile.transportFeaturesInBounds(19.35, -99.15, 19.55, -98.95).size());
      assertTrue(tile.transportFeaturesInBounds(19.00, -99.15, 19.10, -98.95).isEmpty());
      assertEquals(2, tile.transportFeaturesInBounds(19.55, -98.95, 19.35, -99.15).size());
   }

   @Test
   void keepsRoadQueriesUnchangedWhenTransportIsPresent() {
      OverpassRoadTile withTransport = new OverpassRoadTile(List.of(ROAD), List.of(), List.of(RAIL, FERRY), 19.35, -99.15, 19.55, -98.95);
      OverpassRoadTile withoutTransport = new OverpassRoadTile(List.of(ROAD), List.of(), 19.35, -99.15, 19.55, -98.95);

      List<RoadFeature> fromTransportTile = withTransport.featuresInBounds(19.35, -99.15, 19.55, -98.95);
      List<RoadFeature> fromPlainTile = withoutTransport.featuresInBounds(19.35, -99.15, 19.55, -98.95);

      assertEquals(fromPlainTile.size(), fromTransportTile.size());
      assertEquals(1, fromTransportTile.size());
      assertEquals(ROAD.wayId(), fromTransportTile.get(0).wayId());
      assertTrue(withoutTransport.transportFeatures().isEmpty());
   }

   @Test
   void treatsTransportOnlyTileAsNonEmpty() {
      OverpassRoadTile tile = new OverpassRoadTile(List.of(), List.of(), List.of(RAIL), 19.35, -99.15, 19.55, -98.95);

      assertFalse(tile.isEmpty());
      assertTrue(tile.features().isEmpty());
      assertTrue(OverpassRoadTile.empty().isEmpty());
      assertTrue(OverpassRoadTile.empty().transportFeatures().isEmpty());
   }

   @Test
   void exposesImmutableTransportList() {
      OverpassRoadTile tile = new OverpassRoadTile(List.of(), List.of(), List.of(RAIL), 19.35, -99.15, 19.55, -98.95);

      try {
         tile.transportFeatures().add(FERRY);
         throw new AssertionError("Expected an immutable transport list");
      } catch (UnsupportedOperationException expected) {
         assertEquals(1, tile.transportFeatures().size());
      }
   }
}
