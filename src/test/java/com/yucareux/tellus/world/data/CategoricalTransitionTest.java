package com.yucareux.tellus.world.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CategoricalTransitionTest {
   private static final CategoricalTransition.NoiseProfile PROFILE = new CategoricalTransition.NoiseProfile(
      0.25,
      4.0,
      64.0,
      0.47,
      0.75,
      0.25,
      1.25,
      0.04,
      17L,
      29L,
      43L
   );

   @Test
   void preservesFallbackWhenTransitionsAreDisabledOrCandidatesAreInvalid() {
      assertEquals(7, select(7, 1, 2, 3, 4, 0.5, 0.5, 0.0, 12, 34));
      assertEquals(7, select(7, -1, -2, 99, 100, 0.5, 0.5, 1.0, 12, 34));
   }

   @Test
   void mergesRepeatedCategoriesAndReturnsOnlyWeightedCandidates() {
      for (int z = -64; z <= 64; z++) {
         for (int x = -64; x <= 64; x++) {
            int selected = select(1, 2, 2, 3, 3, 0.4, 0.6, 1.0, x, z);
            assertTrue(selected == 2 || selected == 3);
         }
      }
   }

   @Test
   void isDeterministicAtNegativeAbsoluteCoordinates() {
      int expected = select(1, 1, 2, 3, 4, 0.37, 0.61, 1.0, -12345, -67890);
      for (int attempt = 0; attempt < 100; attempt++) {
         assertEquals(expected, select(1, 1, 2, 3, 4, 0.37, 0.61, 1.0, -12345, -67890));
      }
   }

   @Test
   void validatesNoiseProfilesAtConstruction() {
      assertThrows(
         IllegalArgumentException.class,
         () -> new CategoricalTransition.NoiseProfile(0.0, 1.0, 2.0, 0.5, 0.5, 0.5, 1.0, 0.0, 1L, 2L, 3L)
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> new CategoricalTransition.NoiseProfile(1.0, 3.0, 2.0, 0.5, 0.5, 0.5, 1.0, 0.0, 1L, 2L, 3L)
      );
   }

   private static int select(
      int fallback,
      int value00,
      int value10,
      int value01,
      int value11,
      double fractionX,
      double fractionZ,
      double strength,
      double blockX,
      double blockZ
   ) {
      return CategoricalTransition.selectBilinear(
         fallback,
         value00,
         value10,
         value01,
         value11,
         fractionX,
         fractionZ,
         strength,
         blockX,
         blockZ,
         64.0,
         0,
         10,
         PROFILE
      );
   }
}
