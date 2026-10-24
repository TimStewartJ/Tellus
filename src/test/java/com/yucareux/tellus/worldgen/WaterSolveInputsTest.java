package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WaterSolveInputsTest {
   private static int height(int x, int z) {
      return Math.floorMod(x * 31 + z * 17, 251) - 64;
   }

   private static boolean override(int x, int z) {
      return Math.floorMod(x ^ z, 7) == 0;
   }

   @Test
   void surfaceTileHoldsTheSampledValueOfEveryBlock() {
      WaterSolveInputs inputs = new WaterSolveInputs(16, 16);
      WaterSolveInputs.SurfaceTile tile = inputs.surface(-3, 2, (x, z, target, index) -> {
         target.set(index, height(x, z), override(x, z));
         return true;
      });

      for (int z = 2 * 64; z < 3 * 64; z++) {
         for (int x = -3 * 64; x < -2 * 64; x++) {
            int index = WaterSolveInputs.index(x, z);
            assertEquals(height(x, z), tile.heights[index]);
            assertEquals(override(x, z), tile.landOverride(index));
         }
      }
   }

   @Test
   void completeTilesAreSampledOnce() {
      WaterSolveInputs inputs = new WaterSolveInputs(16, 16);
      AtomicInteger samples = new AtomicInteger();
      WaterSolveInputs.SurfaceSampler sampler = (x, z, target, index) -> {
         samples.incrementAndGet();
         target.set(index, height(x, z), false);
         return true;
      };

      WaterSolveInputs.SurfaceTile first = inputs.surface(5, -7, sampler);
      WaterSolveInputs.SurfaceTile second = inputs.surface(5, -7, sampler);

      assertSame(first, second);
      assertEquals(WaterSolveInputs.AREA, samples.get());
      assertTrue(first.complete());
   }

   @Test
   void aTileWithAnUnavailableSampleIsUsedOnceAndSampledAgainLater() {
      WaterSolveInputs inputs = new WaterSolveInputs(16, 16);
      AtomicInteger samples = new AtomicInteger();
      WaterSolveInputs.SurfaceSampler sampler = (x, z, target, index) -> {
         samples.incrementAndGet();
         target.set(index, height(x, z), false);
         return !(x == 3 && z == 4);
      };

      WaterSolveInputs.SurfaceTile first = inputs.surface(0, 0, sampler);
      WaterSolveInputs.SurfaceTile second = inputs.surface(0, 0, sampler);

      assertFalse(first.complete());
      assertNotSame(first, second);
      assertEquals(2 * WaterSolveInputs.AREA, samples.get());
   }

   @Test
   void clearingDropsKeptTiles() {
      WaterSolveInputs inputs = new WaterSolveInputs(16, 16);
      AtomicInteger samples = new AtomicInteger();
      WaterSolveInputs.SurfaceSampler sampler = (x, z, target, index) -> {
         samples.incrementAndGet();
         target.set(index, 1, false);
         return true;
      };

      WaterSolveInputs.SurfaceTile before = inputs.surface(1, 1, sampler);
      WaterSolveInputs.DepthTile depthBefore = inputs.depth(1, 1);
      inputs.clear();
      WaterSolveInputs.SurfaceTile after = inputs.surface(1, 1, sampler);

      assertNotSame(before, after);
      assertNotSame(depthBefore, inputs.depth(1, 1));
      assertEquals(2 * WaterSolveInputs.AREA, samples.get());
   }

   @Test
   void depthTilesStartUnknownAndAreSharedPerTile() {
      WaterSolveInputs inputs = new WaterSolveInputs(16, 16);
      WaterSolveInputs.DepthTile tile = inputs.depth(-1, -1);
      for (int value : tile.encoded) {
         assertEquals(0, value);
      }
      tile.encoded[WaterSolveInputs.index(-1, -1)] = -7;

      assertSame(tile, inputs.depth(-1, -1));
      assertEquals(-7, inputs.depth(-1, -1).encoded[WaterSolveInputs.AREA - 1]);
   }

   @Test
   void blockIndicesFollowTheTileOfNegativeCoordinates() {
      assertEquals(0, WaterSolveInputs.index(-64, -128));
      assertEquals(63, WaterSolveInputs.index(-1, -128));
      assertEquals(64 * 63 + 63, WaterSolveInputs.index(-1, -1));
      assertEquals(-1, -1 >> WaterSolveInputs.SHIFT);
      assertEquals(-2, -65 >> WaterSolveInputs.SHIFT);
   }
}
