package com.yucareux.tellus.integration.distant_horizons;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodRegionalReliefGridTest {
   @Test
   void derivesRegionalReliefFromExistingTerrainSamples() {
      int width = 64;
      int[] coordinates = coordinates(width, 64);
      int[] terrain = new int[width * width];
      int[] cover = new int[terrain.length];
      Arrays.fill(cover, 60);
      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            terrain[z * width + x] = coordinates[x] / 10;
         }
      }
      AtomicInteger fallbacks = new AtomicInteger();

      double[] relief = LodRegionalReliefGrid.compute(
         coordinates,
         coordinates,
         terrain,
         cover,
         1.0,
         (height, blockZ) -> height,
         (blockX, blockZ) -> {
            fallbacks.incrementAndGet();
            return blockX / 10.0;
         }
      );

      int interior = 31 * width + 31;
      assertEquals(320.0, relief[interior], 0.001);
      assertTrue(fallbacks.get() < terrain.length);
   }

   @Test
   void skipsReliefForCoversThatCannotBecomeBadlands() {
      int[] coordinates = coordinates(4, 64);
      int[] terrain = new int[16];
      int[] cover = new int[16];

      double[] relief = LodRegionalReliefGrid.compute(
         coordinates, coordinates, terrain, cover, 1.0, (height, blockZ) -> height, (blockX, blockZ) -> 1000.0
      );

      assertTrue(Arrays.stream(relief).allMatch(Double::isNaN));
   }

   private static int[] coordinates(int width, int step) {
      int[] coordinates = new int[width];
      for (int i = 0; i < width; i++) {
         coordinates[i] = i * step + step / 2;
      }
      return coordinates;
   }
}
