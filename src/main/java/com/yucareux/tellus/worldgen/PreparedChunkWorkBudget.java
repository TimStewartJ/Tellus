package com.yucareux.tellus.worldgen;

/**
 * Device-aware memory budget for read-only work moved ahead of serialized chunk stages.
 */
public final class PreparedChunkWorkBudget {
   private PreparedChunkWorkBudget() {
   }

   /**
    * Uses at most 1/128 of the max heap and roughly 2 MiB per logical processor, clamped so
    * low-memory devices stay useful and high-core machines cannot retain an unbounded backlog.
    */
   public static int cavePlanCacheMiB(long maxHeapBytes, int availableProcessors) {
      long mebibyte = 1024L * 1024L;
      long heapBudget = Math.max(4L, maxHeapBytes / (128L * mebibyte));
      long cpuBudget = Math.max(4L, Math.max(1, availableProcessors) * 2L);
      return (int)Math.min(64L, Math.min(heapBudget, cpuBudget));
   }
}
