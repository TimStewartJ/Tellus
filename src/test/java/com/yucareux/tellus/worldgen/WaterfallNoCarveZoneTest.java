package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaterfallNoCarveZoneTest {
   private static final double WORLD_SCALE = 1.0;
   private static final WorldProjection PROJECTION = WorldProjection.global(WORLD_SCALE);
   private static final OsmWaterFeature MARKER = OsmWaterFeature.waterfallMarker(1L, 0.0, 0.0);

   @Test
   void protectsATwoChunkRadiusCircle() {
      assertEquals(2, WaterfallNoCarveZone.radiusChunks());
      assertEquals(2, WaterfallNoCarveZone.radiusChunks(WORLD_SCALE));
      assertTrue(WaterfallNoCarveZone.containsBlock(MARKER, -32, 0, PROJECTION));
      assertTrue(WaterfallNoCarveZone.containsBlock(MARKER, 47, 0, PROJECTION));
      assertFalse(WaterfallNoCarveZone.containsBlock(MARKER, -33, 0, PROJECTION));
      assertFalse(WaterfallNoCarveZone.containsBlock(MARKER, 48, 0, PROJECTION));
      assertTrue(WaterfallNoCarveZone.containsBlock(MARKER, 16, 16, PROJECTION));
      assertFalse(WaterfallNoCarveZone.containsBlock(MARKER, 32, 16, PROJECTION));
      assertFalse(WaterfallNoCarveZone.containsBlock(MARKER, -32, -32, PROJECTION));
   }

   @Test
   void scalesTheRadiusByRealWorldDistanceUntilItIsDisabled() {
      assertEquals(1, WaterfallNoCarveZone.radiusChunks(2.0));
      assertEquals(0, WaterfallNoCarveZone.radiusChunks(3.0));
      assertEquals(31, WaterfallNoCarveZone.queryMarginBlocks(2.0));
      assertEquals(0, WaterfallNoCarveZone.queryMarginBlocks(3.0));
      assertFalse(WaterfallNoCarveZone.containsBlock(MARKER, 0, 0, WorldProjection.global(3.0)));
   }

   @Test
   void marksPreviewSamplesAndCoarseDistantHorizonCells() {
      double[] samples = new double[]{-33.0, -32.0, 0.0, 16.0, 32.0, 47.0, 48.0};
      boolean[] previewMask = new boolean[samples.length * samples.length];
      WaterfallNoCarveZone.markSampleGrid(previewMask, samples, samples, List.of(MARKER), PROJECTION);

      assertFalse(previewMask[index(samples.length, 0, 2)]);
      assertTrue(previewMask[index(samples.length, 1, 2)]);
      assertTrue(previewMask[index(samples.length, 3, 3)]);
      assertFalse(previewMask[index(samples.length, 4, 4)]);
      assertTrue(previewMask[index(samples.length, 5, 2)]);
      assertFalse(previewMask[index(samples.length, 6, 2)]);
      assertFalse(previewMask[index(samples.length, 1, 1)]);

      boolean[] lodMask = new boolean[5 * 5];
      WaterfallNoCarveZone.markRegularCellGrid(lodMask, -32, -32, 5, 16, List.of(MARKER), PROJECTION);
      assertFalse(lodMask[index(5, 0, 0)]);
      assertTrue(lodMask[index(5, 0, 2)]);
      assertTrue(lodMask[index(5, 4, 2)]);
      assertFalse(lodMask[index(5, 4, 4)]);
   }

   @Test
   void marksAChunkAlignedCircleInTheFullResolutionGrid() {
      boolean[] mask = new boolean[17 * 17];

      WaterfallNoCarveZone.markBlockGrid(mask, 16, 16, 17, 17, List.of(MARKER), PROJECTION);

      assertTrue(mask[index(17, 0, 0)]);
      assertFalse(mask[index(17, 16, 16)]);
   }

   @Test
   void ignoresWaterfallPolygonsAsProtectionMarkers() {
      OsmWaterFeature inaccuratePolygon = new OsmWaterFeature(
         2L,
         false,
         false,
         OsmWaterKind.WATERFALL,
         new double[][]{{-0.01, 0.01, 0.01, -0.01, -0.01}},
         new double[][]{{-0.01, -0.01, 0.01, 0.01, -0.01}}
      );
      boolean[] mask = new boolean[9];

      WaterfallNoCarveZone.markBlockGrid(mask, -1, -1, 3, 3, List.of(inaccuratePolygon), PROJECTION);

      for (boolean protectedCell : mask) {
         assertFalse(protectedCell);
      }
   }

   private static int index(int size, int x, int z) {
      return x + z * size;
   }
}
