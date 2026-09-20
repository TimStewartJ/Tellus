package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WatercourseLocatorTest {
   @Test
   void reportsMappedOrderHeadingForNearestFlowingCentreline() {
      WorldProjection projection = WorldProjection.global(1.0);
      OsmWaterFeature stream = line(OsmWaterKind.STREAM, projection, 0.0, 0.0, 100.0, 0.0);

      WatercourseLocator.Result result = WatercourseLocator.nearest(List.of(stream), projection, 10, 0, 16);

      assertNotNull(result);
      assertEquals(OsmWaterKind.STREAM, result.kind());
      assertEquals(1.0, result.headingX(), 1.0E-9);
      assertEquals(0.0, result.headingZ(), 1.0E-9);
      assertEquals(0.0, result.distanceBlocks(), 1.0E-9);
      assertEquals(3, result.widthBlocks());
   }

   @Test
   void ignoresCentrelinesOutsideSearchRadius() {
      WorldProjection projection = WorldProjection.global(1.0);
      OsmWaterFeature stream = line(OsmWaterKind.STREAM, projection, 0.0, 0.0, 100.0, 0.0);

      assertNull(WatercourseLocator.nearest(List.of(stream), projection, 10, 20, 16));
   }

   private static OsmWaterFeature line(
      OsmWaterKind kind, WorldProjection projection, double startX, double startZ, double endX, double endZ
   ) {
      return new OsmWaterFeature(
         1L,
         true,
         false,
         kind,
         new double[][]{{projection.blockXToLon(startX), projection.blockXToLon(endX)}},
         new double[][]{{projection.blockZToLat(startZ), projection.blockZToLat(endZ)}}
      );
   }
}
