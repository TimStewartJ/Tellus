package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WaterSurfaceResolverChannelTest {
   @Test
   void gapRepairNeverBridgesThroughAChannelSill() {
      int side = 9;
      int area = side * side;
      boolean[] water = new boolean[area];
      boolean[] ocean = new boolean[area];
      boolean[] line = new boolean[area];
      boolean[] flowing = new boolean[area];
      boolean[] sills = new boolean[area];
      int row = 4 * side;
      // Two wet reaches of a channel separated by a two-cell gap whose first cell is a sill.
      for (int x : new int[]{1, 2, 5, 6}) {
         water[row + x] = line[row + x] = flowing[row + x] = true;
      }
      sills[row + 3] = true;

      boolean[] unguarded = water.clone();
      assertEquals(2, WaterSurfaceResolver.repairFlowingWaterGaps(unguarded, ocean, line.clone(), flowing.clone(), side, 3));
      assertTrue(unguarded[row + 3] && unguarded[row + 4]);

      assertEquals(0, WaterSurfaceResolver.repairFlowingWaterGaps(water, ocean, line, flowing, side, 3, sills));
      assertFalse(water[row + 3]);
      assertFalse(water[row + 4]);
   }

   @Test
   void bankLipsLiftLandBesideAChannelToItsWaterLine() {
      int side = 5;
      int area = side * side;
      int[] terrain = new int[area];
      Arrays.fill(terrain, 60);
      int[] water = new int[area];
      Arrays.fill(water, Integer.MIN_VALUE);
      boolean[] channel = new boolean[area];
      boolean[] waterfall = new boolean[area];
      boolean[] land = new boolean[area];
      Arrays.fill(land, true);
      // A channel along the middle row with its surface at 62; one bank cell is lower, one is a waterfall zone.
      for (int x = 0; x < side; x++) {
         int cell = 2 * side + x;
         channel[cell] = true;
         land[cell] = false;
         water[cell] = 62;
         terrain[cell] = 61;
      }
      terrain[1 * side + 2] = 58;
      terrain[3 * side + 2] = 65;
      waterfall[2 * side + 4] = true;
      terrain[1 * side + 4] = 40;

      int lifted = WaterSurfaceResolver.applyChannelBankLips(terrain, water, channel, waterfall, land, side);

      assertEquals(62, terrain[1 * side + 2], "a low bank is raised to the water line");
      assertEquals(65, terrain[3 * side + 2], "a high bank is left alone");
      assertEquals(61, terrain[2 * side + 2], "the channel bed is untouched");
      // The waterfall-zone cell's own neighbour above it is not lifted by that cell, but the cell to its
      // left (x=3) is a normal channel cell whose diagonal neighbour (x=4, row 1) is lifted.
      assertEquals(62, terrain[1 * side + 4]);
      assertTrue(lifted >= 2);
   }

   @Test
   void closedGeometryUsesItsFullSequenceForCanonicalDirection() {
      double[] xs = {0.0, 2.0, 0.0, 0.0};
      double[] zs = {0.0, 1.0, 2.0, 0.0};
      assertTrue(WaterSurfaceResolver.reverseCanonicalGeometry(xs, zs));
      assertFalse(
         WaterSurfaceResolver.reverseCanonicalGeometry(reverse(xs), reverse(zs))
      );
   }

   private static double[] reverse(double[] values) {
      double[] reversed = new double[values.length];
      for (int i = 0; i < values.length; i++) {
         reversed[i] = values[values.length - 1 - i];
      }
      return reversed;
   }
}
