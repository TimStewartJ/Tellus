package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import org.junit.jupiter.api.Test;

class TellusLodTreeFootprintTest {
   @Test
   void lodTreesUseTheSharedConnectedBasalFootprintDecision() {
      TellusProceduralTreeGenerator.TreePlan tree = treePlan();

      assertTrue(
         TellusLodGenerator.acceptsLodTreeFootprint(
            0,
            100,
            0,
            tree,
            (x, z) -> x == 1 ? 101 : 100
         )
      );
      assertFalse(
         TellusLodGenerator.acceptsLodTreeFootprint(
            0,
            100,
            0,
            tree,
            (x, z) -> x < 0 || x == 0 && (z == 0 || z == 1)
               ? 100
               : Integer.MIN_VALUE
         )
      );
   }

   private static TellusProceduralTreeGenerator.TreePlan treePlan() {
      return new TellusProceduralTreeGenerator.TreePlan(
         TellusProceduralTreeGenerator.Profile.TALL_CONIFER,
         52,
         4,
         8,
         26,
         27,
         0,
         0,
         false,
         true
      );
   }
}
