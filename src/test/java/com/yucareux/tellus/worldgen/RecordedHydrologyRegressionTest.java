package com.yucareux.tellus.worldgen;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline real-location regressions supplement the controlled topology cases. */
class RecordedHydrologyRegressionTest {
   @Test void niagaraKeepsTheLowerGorgeAndUpperPoolsOnTheirOwnLevels() throws IOException {
      var fixture = new RecordedHydrologyFixture("niagara");
      var planner = fixture.planner();
      // Include the receiving river polygon beyond the American Falls, curved
      // crest endpoints, and the overlapping mapped waterfall areas.
      int[][] lower = {{-8802032, -5325124}, {-8802280, -5324644},
         {-8802608, -5324032}, {-8802608, -5324792}, {-8802768, -5323828}};
      for (int[] point : lower) {
         var column = planner.sample(point[0], point[1], (int)Math.ceil(fixture.sample(point[0], point[1]).height()));
         assertTrue(column.wet(), "Disconnected lower pool at " + point[0] + "," + point[1]);
         assertTrue(column.water() >= 198 && column.water() <= 208, "Raised gorge at " + point[0] + "," + point[1] + ": " + column);
         // At the foot of a fall running down the terrain the last curtain stands on the gorge, not on a second drop.
         assertTrue(!column.falling() || column.terrain() >= 196, "A lower pool must not become another waterfall: " + column);
      }
      int[][] upper = {{-8802524, -5323992}, {-8802808, -5323732}, {-8802888, -5323636}};
      for (int[] point : upper) {
         var column = planner.sample(point[0], point[1], (int)Math.ceil(fixture.sample(point[0], point[1]).height()));
         assertTrue(column.wet());
         assertTrue(column.water() >= 270 && column.water() <= 278, "Excavated upper pool at " + point[0] + "," + point[1] + ": " + column);
      }
      assertFalse(planner.sample(-8802352, -5324292, 275).wet(), "Goat Island must remain land");
   }

   @Test void huentitanConfluenceAndRejoiningBranchesRemainConnected() throws IOException {
      var fixture = new RecordedHydrologyFixture("huentitan");
      var planner = fixture.planner();
      InlandHydrologyRegressionTest.assertConnected(planner, -11497556, -2359872, -11496920, -2359748,
         -11497696, -2359944, -11496776, -2359416);
      InlandHydrologyRegressionTest.assertConnected(planner, -11497304, -2359608, -11497172, -2359496,
         -11497696, -2359944, -11496776, -2359416);
   }
}
