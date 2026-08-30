package com.yucareux.tellus.world.data.osm;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedTileCodecTransportTest {
   @TempDir
   Path tempDir;

   @Test
   void roundTripsTransportFeaturesAlongsideUnchangedRoads() throws IOException {
      RoadFeature road = new RoadFeature(
         123L,
         RoadClass.MAIN,
         RoadMode.BRIDGE,
         2,
         "primary",
         "asphalt",
         "link",
         12.5,
         4,
         false,
         new double[]{-99.1, -99.09},
         new double[]{19.4, 19.41}
      );
      RoadAreaFeature area = new RoadAreaFeature(
         456L,
         RoadClass.NORMAL,
         "pedestrian",
         "paving_stones",
         "plaza",
         new double[][]{{-99.100, -99.095, -99.095, -99.100, -99.100}},
         new double[][]{{19.400, 19.400, 19.405, 19.405, 19.400}}
      );
      TransportFeature rail = new TransportFeature(
         777L, TransportKind.RAIL, "subway", "tunnel", new double[]{-99.10, -99.08, -99.07}, new double[]{19.40, 19.41, 19.43}
      );
      TransportFeature ferry = new TransportFeature(
         888L, TransportKind.WATER_ROUTE, "ferry", "", new double[]{-99.12, -99.06}, new double[]{19.38, 19.44}
      );
      OverpassRoadTile tile = new OverpassRoadTile(List.of(road), List.of(area), List.of(rail, ferry), 19.37, -99.13, 19.45, -99.05);
      Path path = this.tempDir.resolve("road_transport.parsed");

      ParsedTileCodec.writeRoadTile(path, tile);
      OverpassRoadTile decoded = ParsedTileCodec.readRoadTile(path);

      assertEquals(1, decoded.features().size());
      RoadFeature decodedRoad = decoded.features().get(0);
      assertEquals(123L, decodedRoad.wayId());
      assertEquals(RoadClass.MAIN, decodedRoad.roadClass());
      assertEquals(RoadMode.BRIDGE, decodedRoad.mode());
      assertEquals("primary", decodedRoad.highwayTag());
      assertEquals("asphalt", decodedRoad.roadSurface());
      assertEquals(4, decodedRoad.lanes());
      assertEquals(1, decoded.areaFeatures().size());
      assertEquals(456L, decoded.areaFeatures().get(0).featureId());

      assertEquals(2, decoded.transportFeatures().size());
      TransportFeature decodedRail = decoded.transportFeatures().get(0);
      assertEquals(rail, decodedRail);
      assertEquals(777L, decodedRail.featureId());
      assertEquals(TransportKind.RAIL, decodedRail.kind());
      assertEquals("subway", decodedRail.transportClass());
      assertEquals("tunnel", decodedRail.subclass());
      assertEquals(3, decodedRail.pointCount());
      assertEquals(-99.07, decodedRail.lonAt(2), 1.0E-9);
      assertEquals(19.43, decodedRail.latAt(2), 1.0E-9);

      TransportFeature decodedFerry = decoded.transportFeatures().get(1);
      assertEquals(ferry, decodedFerry);
      assertEquals(TransportKind.WATER_ROUTE, decodedFerry.kind());
      assertEquals("ferry", decodedFerry.transportClass());
      assertEquals("", decodedFerry.subclass());
   }

   @Test
   void roundTripsTransportOnlyTiles() throws IOException {
      TransportFeature rail = new TransportFeature(
         5L, TransportKind.RAIL, "tram", "", new double[]{-99.10, -99.08}, new double[]{19.40, 19.41}
      );
      OverpassRoadTile tile = new OverpassRoadTile(List.of(), List.of(), List.of(rail), 19.37, -99.13, 19.45, -99.05);
      Path path = this.tempDir.resolve("transport_only.parsed");

      ParsedTileCodec.writeRoadTile(path, tile);
      OverpassRoadTile decoded = ParsedTileCodec.readRoadTile(path);

      assertTrue(decoded.features().isEmpty());
      assertTrue(decoded.areaFeatures().isEmpty());
      assertEquals(1, decoded.transportFeatures().size());
      assertEquals(rail, decoded.transportFeatures().get(0));
      assertEquals(19.37, decoded.tileSouth(), 1.0E-9);
      assertEquals(-99.05, decoded.tileEast(), 1.0E-9);
   }

   @Test
   void rejectsSupersededRoadTileFormat() throws IOException {
      Path path = this.tempDir.resolve("legacy_v6.parsed");
      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
         output.writeInt(1413827666);
         output.writeInt(6);
         output.writeDouble(19.4);
         output.writeDouble(-99.1);
         output.writeDouble(19.5);
         output.writeDouble(-99.0);
         output.writeInt(0);
         output.writeInt(0);
      }

      IOException failure = assertThrows(IOException.class, () -> ParsedTileCodec.readRoadTile(path));
      assertTrue(failure.getMessage().contains("Unsupported road tile version 6"));
   }
}
