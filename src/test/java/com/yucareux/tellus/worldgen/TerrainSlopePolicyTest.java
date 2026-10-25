package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSlopePolicyTest {
   @Test
   void measuresPlanarSlopeFromBothSides() {
      assertEquals(
         45.0,
         TerrainSlopePolicy.localSlopeDegrees(0.0, 30.0, -30.0, 0.0, 0.0, 30.0, 30.0),
         1.0E-9
      );
   }

   @Test
   void preservesSteepExposureAtRidgesAndBowls() {
      assertEquals(
         45.0,
         TerrainSlopePolicy.localSlopeDegrees(30.0, 0.0, 0.0, 30.0, 30.0, 30.0, 30.0),
         1.0E-9
      );
      assertEquals(
         45.0,
         TerrainSlopePolicy.localSlopeDegrees(0.0, 30.0, 30.0, 0.0, 0.0, 30.0, 30.0),
         1.0E-9
      );
   }

   @Test
   void usesAvailableSidesWhenNeighborSamplesAreMissing() {
      assertEquals(
         45.0,
         TerrainSlopePolicy.localSlopeDegrees(0.0, 30.0, Double.NaN, Double.NaN, Double.NaN, 30.0, 30.0),
         1.0E-9
      );
      assertTrue(
         Double.isNaN(
            TerrainSlopePolicy.localSlopeDegrees(
               0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 30.0, 30.0
            )
         )
      );
   }

   @Test
   void gradientKeepsTheSlopeAndAddsTheWayTheGroundFalls() {
      // Rises 30 m toward the south over 30 m: a 45-degree face falling toward the north.
      TerrainSlopePolicy.Gradient gradient = TerrainSlopePolicy.localGradient(100.0, 100.0, 100.0, 70.0, 130.0, 30.0, 30.0);
      assertEquals(100.0, gradient.centerElevation(), 1.0E-9);
      assertEquals(TerrainSlopePolicy.localSlopeDegrees(100.0, 100.0, 100.0, 70.0, 130.0, 30.0, 30.0), gradient.slopeDegrees(), 1.0E-9);
      assertEquals(0.0, gradient.riseEast(), 1.0E-9);
      assertEquals(1.0, gradient.riseSouth(), 1.0E-9);
      assertTrue(Double.isNaN(TerrainSlopePolicy.localGradient(0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 30.0, 30.0).slopeDegrees()));
   }

   @Test
   void facingIsMeasuredAgainstTheNearerPole() {
      TerrainSlopePolicy.Gradient fallsNorth = TerrainSlopePolicy.localGradient(100.0, 100.0, 100.0, 70.0, 130.0, 30.0, 30.0);
      TerrainSlopePolicy.Gradient fallsSouth = TerrainSlopePolicy.localGradient(100.0, 100.0, 100.0, 130.0, 70.0, 30.0, 30.0);
      TerrainSlopePolicy.Gradient fallsEast = TerrainSlopePolicy.localGradient(100.0, 70.0, 130.0, 100.0, 100.0, 30.0, 30.0);
      TerrainSlopePolicy.Gradient flat = TerrainSlopePolicy.localGradient(100.0, 100.0, 100.0, 100.0, 100.0, 30.0, 30.0);
      assertEquals(1.0, fallsNorth.polewardFacing(46.0), 1.0E-9);
      assertEquals(-1.0, fallsNorth.polewardFacing(-46.0), 1.0E-9);
      assertEquals(-1.0, fallsSouth.polewardFacing(46.0), 1.0E-9);
      assertEquals(1.0, fallsSouth.polewardFacing(-46.0), 1.0E-9);
      assertEquals(0.0, fallsEast.polewardFacing(46.0), 1.0E-9);
      assertEquals(0.0, flat.polewardFacing(46.0), 1.0E-9);
      assertEquals(0.0, fallsNorth.polewardFacing(0.0), 1.0E-9);
      // One missing neighbour still gives a direction from the other side.
      assertEquals(1.0, TerrainSlopePolicy.localGradient(100.0, Double.NaN, 100.0, Double.NaN, 130.0, 30.0, 30.0).polewardFacing(46.0), 1.0E-9);
   }
}
