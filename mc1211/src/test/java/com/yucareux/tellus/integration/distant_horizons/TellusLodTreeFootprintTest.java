package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.api.detail.ChunkDetailLodPendingException;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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

   @Test
   void pendingLodInputsBecomeSilentRetryableRejections() {
      var pending = new ChunkDetailLodPendingException(
         20, "terrain elevation unavailable"
      );

      RejectedExecutionException retry =
         TellusLodGenerator.retryablePendingRejection(pending);

      assertSame(pending, retry.getCause());
   }

   @Test
   void companionRetryHintsCannotOvershootTheFailOpenDeadline() {
      var pending = new ChunkDetailLodPendingException(
         1_200, "route data unavailable"
      );

      var clamped = TellusLodGenerator.clampPendingRetryToRemaining(
         pending, TimeUnit.SECONDS.toNanos(30)
      );

      assertEquals(600, clamped.retryAfterTicks());
      assertSame(
         pending,
         TellusLodGenerator.clampPendingRetryToRemaining(
            pending, TimeUnit.SECONDS.toNanos(90)
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
