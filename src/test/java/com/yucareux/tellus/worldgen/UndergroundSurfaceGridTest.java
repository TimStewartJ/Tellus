package com.yucareux.tellus.worldgen;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UndergroundSurfaceGridTest {
   @Test
   void samplesPaddedSurfaceGridOnceAndFindsNeighborhoodMinimums() {
      AtomicInteger calls = new AtomicInteger();
      int[] minimums = UndergroundSurfaceGrid.minimumNearbySurfaceYByColumn(
         (x, z) -> {
            calls.incrementAndGet();
            return x * 100 + z;
         },
         32,
         -48,
         2
      );

      assertEquals(20 * 20, calls.get());
      assertEquals(30 * 100 - 50, minimums[0]);
      assertEquals(45 * 100 - 35, minimums[15 * 16 + 15]);
   }

   @Test
   void resolvesSolidWorldBottomFromConfiguredDepth() {
      int[] surfaces = filled(160);
      int[] bottoms = UndergroundSurfaceGrid.usableBottomYByColumn(surfaces, null, 64, -64);

      assertEquals(97, bottoms[0]);
      assertEquals(97, bottoms[255]);
   }

   @Test
   void resolvesShellBottomAboveHighestSideSkinBedrock() {
      int[] surfaces = filled(160);
      int[] skinTopYs = filled(96);
      skinTopYs[5 * 16 + 3] = 150;
      int[] bottoms = UndergroundSurfaceGrid.usableBottomYByColumn(
         surfaces, skinTopYs, 64, -64
      );

      assertEquals(97, bottoms[0]);
      assertEquals(151, bottoms[5 * 16 + 3]);
   }

   @Test
   void handlesShellShallowerThanOneBlock() {
      int[] surfaces = filled(-64);
      int[] skinTopYs = filled(-64);
      int[] bottoms = UndergroundSurfaceGrid.usableBottomYByColumn(
         surfaces, skinTopYs, 0, -64
      );

      assertEquals(-63, bottoms[0]);
   }

   @Test
   void indexesNegativeCoordinatesWithinTheirChunk() {
      int[] values = new int[256];
      for (int index = 0; index < values.length; index++) {
         values[index] = index;
      }

      assertEquals(255, UndergroundSurfaceGrid.columnValue(values, -1, -1));
      assertEquals(237, UndergroundSurfaceGrid.columnValue(values, -3, -2));
      assertEquals(Integer.MIN_VALUE, UndergroundSurfaceGrid.columnValue(null, 0, 0));
   }

   @Test
   void rejectsInvalidInputs() {
      assertThrows(
         IllegalArgumentException.class,
         () -> UndergroundSurfaceGrid.minimumNearbySurfaceYByColumn((x, z) -> 0, 0, 0, -1)
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> UndergroundSurfaceGrid.usableBottomYByColumn(new int[1], null, 64, -64)
      );
   }

   private static int[] filled(int value) {
      int[] values = new int[256];
      java.util.Arrays.fill(values, value);
      return values;
   }
}
