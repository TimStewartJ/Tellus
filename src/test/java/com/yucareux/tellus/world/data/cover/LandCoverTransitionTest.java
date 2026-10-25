package com.yucareux.tellus.world.data.cover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LandCoverTransitionTest {
   @Test
   void fadesInOnlyBelowTheCategoricalSourceResolution() {
      assertEquals(0.0, LandCoverTransition.strength(10.0, 10.0));
      assertEquals(0.0, LandCoverTransition.strength(10.0, 20.0));
      assertEquals(1.0, LandCoverTransition.strength(10.0, 1.0));
      assertTrue(LandCoverTransition.strength(10.0, 5.0) > 0.5);
   }

   @Test
   void keepsSemanticHardClassesOutOfVisualDithering() {
      assertEquals(80, select(80, 10, 10, 10, 10, 0.5, 0.5, 12, 8));
      assertEquals(10, select(10, 10, 80, 10, 80, 0.5, 0.5, 12, 8));
      assertEquals(95, select(95, 10, 60, 30, 40, 0.5, 0.5, 12, 8));
      assertEquals(0, select(0, 10, 60, 30, 40, 0.5, 0.5, 12, 8));
   }

   @Test
   void hardNeighboursLendTheirWeightToTheCenterInsteadOfStoppingTheBlend() {
      // A meadow beside a lake still blends with the forest on its other side.
      Set<Integer> selected = new HashSet<>();
      for (int z = 0; z < 64; z++) {
         for (int x = 0; x < 64; x++) {
            selected.add(select(30, 30, 10, 80, 10, 0.5, 0.5, x, z));
         }
      }
      assertEquals(Set.of(10, 30), selected);
   }

   @Test
   void townsBlendIntoTheSurroundingCountry() {
      Set<Integer> selected = new HashSet<>();
      for (int z = 0; z < 64; z++) {
         for (int x = 0; x < 64; x++) {
            selected.add(select(50, 50, 10, 50, 10, 0.5, 0.5, x, z));
            selected.add(select(10, 50, 10, 50, 10, 0.5, 0.5, x, z));
         }
      }
      assertEquals(Set.of(10, 50), selected, "built-up land must take part in the blend");
   }

   @Test
   void warpBendsBordersByAboutAPixelAndIsStable() {
      double maxOffset = 0.0;
      double sum = 0.0;
      int samples = 0;
      for (int z = -400; z <= 400; z += 7) {
         for (int x = -400; x <= 400; x += 7) {
            double dx = LandCoverTransition.warpPixelsX(x, z, 10.0, 1.0);
            double dz = LandCoverTransition.warpPixelsZ(x, z, 10.0, 1.0);
            assertEquals(dx, LandCoverTransition.warpPixelsX(x, z, 10.0, 1.0));
            maxOffset = Math.max(maxOffset, Math.max(Math.abs(dx), Math.abs(dz)));
            sum += Math.abs(dx) + Math.abs(dz);
            samples += 2;
         }
      }
      assertTrue(maxOffset <= 1.6, "warp stays within about a pixel and a half: " + maxOffset);
      assertTrue(sum / samples > 0.2, "warp must actually move borders: " + sum / samples);
      assertEquals(0.0, LandCoverTransition.warpPixelsX(12, 34, 10.0, 0.0));
   }

   @Test
   void neighbouringPixelCellsKeepOneClassOrder() {
      // The same corner weights must pick the same class whichever corner holds it, or a straight
      // seam appears along every pixel-centre line.
      for (int z = 0; z < 32; z++) {
         for (int x = 0; x < 32; x++) {
            assertEquals(select(10, 10, 60, 10, 60, 0.5, 0.5, x, z), select(10, 60, 10, 60, 10, 0.5, 0.5, x, z));
         }
      }
   }

   @Test
   void producesDeterministicAbsoluteCoordinateTransitions() {
      int first = select(10, 10, 60, 10, 60, 0.55, 0.25, -12345, 67890);
      for (int attempt = 0; attempt < 100; attempt++) {
         assertEquals(first, select(10, 10, 60, 10, 60, 0.55, 0.25, -12345, 67890));
      }
   }

   @Test
   void breaksAFormerlyStraightRasterBoundaryIntoCoherentVariation() {
      Set<Integer> transitionEdges = new HashSet<>();
      for (int z = 0; z < 96; z++) {
         int firstRightClass = 10;
         for (int x = 0; x <= 10; x++) {
            int selected = select(10, 10, 60, 10, 60, x / 10.0, 0.35, x, z);
            if (selected == 60) {
               firstRightClass = x;
               break;
            }
         }
         transitionEdges.add(firstRightClass);
      }

      assertTrue(transitionEdges.size() >= 4, "the transition should not remain an axis-aligned raster edge");
   }

   @Test
   void leavesUniformRasterInteriorsUntouched() {
      for (int z = -16; z <= 16; z++) {
         for (int x = -16; x <= 16; x++) {
            assertEquals(30, select(30, 30, 30, 30, 30, 0.37, 0.61, x, z));
         }
      }
   }

   private static int select(
      int center,
      int class00,
      int class10,
      int class01,
      int class11,
      double fractionX,
      double fractionZ,
      double blockX,
      double blockZ
   ) {
      return LandCoverTransition.selectVisualClass(
         center,
         class00,
         class10,
         class01,
         class11,
         fractionX,
         fractionZ,
         1.0,
         blockX,
         blockZ,
         10.0
      );
   }
}
