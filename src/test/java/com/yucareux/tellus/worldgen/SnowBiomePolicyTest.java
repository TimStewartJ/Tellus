package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SnowBiomePolicyTest {
   private static final double BASELINE = 240.0;

   @Test
   void readsTheGroundOverAboutTwoHundredFortyMetres() {
      assertEquals(8, SnowBiomePolicy.baselineBlocks(30.0));
      assertEquals(24, SnowBiomePolicy.baselineBlocks(10.0));
      assertEquals(240, SnowBiomePolicy.baselineBlocks(1.0));
      // Coarse worlds keep at least eight blocks, so biome patches stay coherent.
      assertEquals(8, SnowBiomePolicy.baselineBlocks(1000.0));
   }

   @Test
   void glaciersAndIceCapsAreFrozenPeaks() {
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, plane(0.0));
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, plane(12.0));
      // A rounded snow dome falls gently all round.
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, classify(4000.0, 3940.0, 3940.0, 3940.0, 3940.0));
   }

   @Test
   void snowfieldsOnTheFlanksAreSnowySlopes() {
      assertEquals(SnowBiomePolicy.Kind.SNOWY_SLOPES, plane(16.0));
      assertEquals(SnowBiomePolicy.Kind.SNOWY_SLOPES, plane(30.0));
   }

   @Test
   void steepFacesAndSharpCrestsAreJaggedPeaks() {
      assertEquals(SnowBiomePolicy.Kind.JAGGED_PEAKS, plane(36.0));
      assertEquals(SnowBiomePolicy.Kind.JAGGED_PEAKS, plane(60.0));
      // An arete running north-south with 40-degree sides: no overall slope, but a sharp crest.
      double drop = BASELINE * Math.tan(Math.toRadians(40.0));
      assertEquals(SnowBiomePolicy.Kind.JAGGED_PEAKS, classify(4000.0, 4000.0 - drop, 4000.0 - drop, 4000.0, 4000.0));
      // The same arete with 20-degree sides is a broad snow ridge.
      double gentle = BASELINE * Math.tan(Math.toRadians(20.0));
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, classify(4000.0, 4000.0 - gentle, 4000.0 - gentle, 4000.0, 4000.0));
   }

   @Test
   void missingElevationsKeepFrozenPeaks() {
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, classify(4000.0, Double.NaN, 3000.0, 3000.0, 3000.0));
      assertEquals(SnowBiomePolicy.Kind.FROZEN_PEAKS, SnowBiomePolicy.classify(4000.0, 3000.0, 3000.0, 3000.0, 3000.0, 0.0, BASELINE));
   }

   /** A plane rising toward the east at this slope. */
   private static SnowBiomePolicy.Kind plane(double slopeDegrees) {
      double rise = BASELINE * Math.tan(Math.toRadians(slopeDegrees));
      return classify(3000.0, 3000.0 + rise, 3000.0 - rise, 3000.0, 3000.0);
   }

   private static SnowBiomePolicy.Kind classify(double center, double east, double west, double north, double south) {
      return SnowBiomePolicy.classify(center, east, west, north, south, BASELINE, BASELINE);
   }
}
