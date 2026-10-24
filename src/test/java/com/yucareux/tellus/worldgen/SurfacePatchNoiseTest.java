package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurfacePatchNoiseTest {
   private static final long SALT = 1234567L;

   @Test
   void coverageKeepsTheRequestedAreaShare() {
      for (double coverage : new double[]{10.0, 35.0, 65.0, 90.0}) {
         int covered = 0;
         int samples = 0;
         for (int z = -4096; z < 4096; z += 16) {
            for (int x = -4096; x < 4096; x += 16) {
               if (SurfacePatchNoise.covers(x, z, SALT, coverage)) {
                  covered++;
               }
               samples++;
            }
         }

         assertEquals(coverage, 100.0 * covered / samples, 3.0, "coverage " + coverage);
      }
   }

   @Test
   void neighbouringColumnsFormPatchesInsteadOfSpeckle() {
      int agreeing = 0;
      int pairs = 0;
      for (int z = 0; z < 512; z += 2) {
         for (int x = 0; x < 512; x += 2) {
            boolean here = SurfacePatchNoise.covers(x, z, SALT, 50.0);
            agreeing += here == SurfacePatchNoise.covers(x + 1, z, SALT, 50.0) ? 1 : 0;
            agreeing += here == SurfacePatchNoise.covers(x, z + 1, SALT, 50.0) ? 1 : 0;
            pairs += 2;
         }
      }

      // Independent per-block picks agree half the time at 50% coverage.
      assertTrue(agreeing > pairs * 0.9, "neighbour agreement " + agreeing + "/" + pairs);
   }

   @Test
   void selectionIsStableAndBounded() {
      for (int i = 0; i < 1000; i++) {
         int x = i * 7919 - 3_000_000;
         int z = i * 104729 - 50_000_000;
         double value = SurfacePatchNoise.uniform(x, z, SALT);
         assertTrue(value >= 0.0 && value < 1.0);
         assertEquals(value, SurfacePatchNoise.uniform(x, z, SALT));
         int percent = SurfacePatchNoise.percent(x, z, SALT);
         assertTrue(percent >= 0 && percent <= 99);
      }

      assertFalse(SurfacePatchNoise.covers(5, 5, SALT, 0.0));
      assertFalse(SurfacePatchNoise.covers(5, 5, SALT, Double.NaN));
      assertTrue(SurfacePatchNoise.covers(5, 5, SALT, 100.0));
   }

   @Test
   void differentSaltsGiveIndependentPatterns() {
      int agreeing = 0;
      int samples = 0;
      for (int z = 0; z < 2048; z += 8) {
         for (int x = 0; x < 2048; x += 8) {
            agreeing += SurfacePatchNoise.covers(x, z, SALT, 50.0) == SurfacePatchNoise.covers(x, z, SALT ^ 99L, 50.0) ? 1 : 0;
            samples++;
         }
      }

      assertEquals(0.5, (double)agreeing / samples, 0.1);
   }
}
