package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowSlopePolicyTest {
   @Test
   void usesConfiguredCoverageBands() {
      assertEquals(100, SnowSlopePolicy.coveragePercent(0.0));
      assertEquals(100, SnowSlopePolicy.coveragePercent(44.999));
      assertEquals(75, SnowSlopePolicy.coveragePercent(45.0));
      assertEquals(75, SnowSlopePolicy.coveragePercent(49.999));
      assertEquals(40, SnowSlopePolicy.coveragePercent(50.0));
      assertEquals(40, SnowSlopePolicy.coveragePercent(54.999));
      assertEquals(10, SnowSlopePolicy.coveragePercent(55.0));
      assertEquals(10, SnowSlopePolicy.coveragePercent(59.999));
      assertEquals(0, SnowSlopePolicy.coveragePercent(60.0));
      assertEquals(0, SnowSlopePolicy.coveragePercent(90.0));
   }

   @Test
   void missingSlopePreservesSnowAndVerySteepTerrainRejectsIt() {
      assertTrue(SnowSlopePolicy.shouldCover(12, 34, Double.NaN));
      assertFalse(SnowSlopePolicy.shouldCover(12, 34, 60.0));
      assertTrue(SnowSlopePolicy.hasFullCoverage(Double.NaN));
      assertTrue(SnowSlopePolicy.hasFullCoverage(44.999));
      assertFalse(SnowSlopePolicy.hasFullCoverage(45.0));
   }

   @Test
   void facingMattersOnlyAwayFromTheTropics() {
      assertEquals(0.0, SnowSlopePolicy.aspectStrength(0.0), 1.0E-9);
      assertEquals(0.0, SnowSlopePolicy.aspectStrength(25.0), 1.0E-9);
      assertEquals(0.15, SnowSlopePolicy.aspectStrength(28.0), 1.0E-9);
      assertEquals(0.5, SnowSlopePolicy.aspectStrength(-35.0), 1.0E-9);
      assertEquals(1.0, SnowSlopePolicy.aspectStrength(46.0), 1.0E-9);
      assertEquals(1.0, SnowSlopePolicy.aspectStrength(-75.0), 1.0E-9);
      assertEquals(0.0, SnowSlopePolicy.aspectStrength(Double.NaN), 1.0E-9);
   }

   @Test
   void shadedFacesHoldSnowSteeperAndSunnyFacesShedItSooner() {
      // A 52-degree face keeps 40% of its snow facing east or west.
      assertEquals(52.0, SnowSlopePolicy.effectiveSlopeDegrees(52.0, 0.0, 46.0), 1.0E-9);
      assertEquals(100, SnowSlopePolicy.coveragePercent(SnowSlopePolicy.effectiveSlopeDegrees(52.0, 1.0, 46.0)));
      assertEquals(0, SnowSlopePolicy.coveragePercent(SnowSlopePolicy.effectiveSlopeDegrees(52.0, -1.0, 46.0)));
      // Near the equator the face's direction makes no difference.
      assertEquals(52.0, SnowSlopePolicy.effectiveSlopeDegrees(52.0, 1.0, 10.0), 1.0E-9);
      assertTrue(Double.isNaN(SnowSlopePolicy.effectiveSlopeDegrees(Double.NaN, 1.0, 46.0)));
   }

   @Test
   void theSouthernHemisphereShadesItsSouthFaces() {
      TerrainSlopePolicy.Gradient fallsSouth = TerrainSlopePolicy.localGradient(100.0, 100.0, 100.0, 140.0, 60.0, 30.0, 30.0);
      double slope = fallsSouth.slopeDegrees();
      assertEquals(slope - SnowSlopePolicy.ASPECT_SHIFT_DEGREES, SnowSlopePolicy.effectiveSlopeDegrees(fallsSouth, -46.0), 1.0E-9);
      assertEquals(slope + SnowSlopePolicy.ASPECT_SHIFT_DEGREES, SnowSlopePolicy.effectiveSlopeDegrees(fallsSouth, 46.0), 1.0E-9);
   }

   @Test
   void computesSlopeFromDemElevationsAndMetricSampleDistances() {
      assertEquals(45.0, SnowSlopePolicy.slopeDegrees(30.0, -30.0, 0.0, 0.0, 60.0, 60.0), 1.0E-9);
      assertEquals(0.0, SnowSlopePolicy.slopeDegrees(10.0, 10.0, 10.0, 10.0, 60.0, 60.0), 1.0E-9);
   }
}
