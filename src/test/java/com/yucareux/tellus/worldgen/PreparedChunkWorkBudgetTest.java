package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreparedChunkWorkBudgetTest {
   @Test
   void scalesWithHeapAndProcessorCountWithinBounds() {
      long gib = 1L << 30;

      assertEquals(4, PreparedChunkWorkBudget.cavePlanCacheMiB(512L << 20, 20));
      assertEquals(16, PreparedChunkWorkBudget.cavePlanCacheMiB(2 * gib, 20));
      assertEquals(8, PreparedChunkWorkBudget.cavePlanCacheMiB(16 * gib, 4));
      assertEquals(40, PreparedChunkWorkBudget.cavePlanCacheMiB(16 * gib, 20));
      assertEquals(64, PreparedChunkWorkBudget.cavePlanCacheMiB(64 * gib, 64));
      assertEquals(4, PreparedChunkWorkBudget.cavePlanCacheMiB(64 * gib, 1));
   }
}
