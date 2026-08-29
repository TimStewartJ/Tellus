package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.WorldProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LodSurfaceSlopeGridTest {
   private static final WorldProjection PROJECTION = WorldProjection.global(1.0);

   @Test
   void computesFlatGridWithoutAdditionalSamples() {
      int[] coordinates = {32, 96, 160};
      int[] heights = {
         80, 80, 80,
         80, 80, 80,
         80, 80, 80
      };
      int[] conversions = {0};
      int[] haloSamples = {0};

      double[] slopes = LodSurfaceSlopeGrid.compute(
         coordinates,
         coordinates,
         heights,
         PROJECTION,
         (height, blockZ) -> {
            conversions[0]++;
            return height;
         },
         (worldX, worldZ) -> {
            haloSamples[0]++;
            return 80.0;
         }
      );

      assertEquals(heights.length, conversions[0]);
      assertEquals(12, haloSamples[0]);
      assertArrayEquals(new double[heights.length], slopes, 1.0E-9);
   }

   @Test
   void usesHaloElevationAtTileEdges() {
      int[] coordinates = {32, 96, 160};
      int[] heights = {
         0, 64, 128,
         0, 64, 128,
         0, 64, 128
      };

      double[] slopes = LodSurfaceSlopeGrid.compute(
         coordinates,
         coordinates,
         heights,
         PROJECTION,
         (height, blockZ) -> height,
         (worldX, worldZ) -> worldX - 32.0
      );

      assertEquals(45.0, slopes[0], 1.0E-6);
      assertEquals(45.0, slopes[4], 1.0E-6);
      assertEquals(45.0, slopes[8], 1.0E-6);
   }

   @Test
   void rejectsIrregularCoordinates() {
      assertThrows(
         IllegalArgumentException.class,
         () -> LodSurfaceSlopeGrid.compute(
            new int[]{0, 64, 129},
            new int[]{0, 64, 128},
            new int[9],
            PROJECTION,
            (height, blockZ) -> height,
            (worldX, worldZ) -> 0.0
         )
      );
   }
}
