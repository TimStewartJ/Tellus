package com.yucareux.tellus.worldgen;

import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import net.minecraft.util.Mth;

/**
 * Work arrays of a large grid solve, lent to one solve at a time instead of kept by every thread that
 * ever ran one. A thread-local copy stayed with each world-generation, server and LOD worker thread:
 * at 1:1 a coastal water solve uses about 115 MB and a coast field build about 65 MB, and with 25 such
 * threads in game they pinned several GB of the heap. At most {@code concurrency} solves hold scratch at
 * once (further callers wait for one to finish). Up to {@code retained} idle copies are kept for reuse,
 * softly, so the collector frees them when the heap runs short.
 */
final class ScratchPool<T> {
   private final Supplier<T> factory;
   private final Semaphore permits;
   private final int retained;
   private final ArrayDeque<SoftReference<T>> idle = new ArrayDeque<>();

   ScratchPool(Supplier<T> factory, int concurrency, int retained) {
      this.factory = factory;
      this.permits = new Semaphore(Math.max(1, concurrency));
      this.retained = Math.max(0, retained);
   }

   T acquire() {
      // A cancelled LOD worker finishes its (short) wait and is stopped by its own cancellation checks.
      this.permits.acquireUninterruptibly();
      T scratch = null;
      synchronized (this.idle) {
         while (scratch == null && !this.idle.isEmpty()) {
            scratch = this.idle.pollFirst().get();
         }
      }
      if (scratch == null) {
         try {
            scratch = this.factory.get();
         } catch (RuntimeException | Error error) {
            this.permits.release();
            throw error;
         }
      }
      return scratch;
   }

   void release(T scratch) {
      try {
         synchronized (this.idle) {
            this.idle.removeIf(reference -> reference.get() == null);
            if (this.idle.size() < this.retained) {
               this.idle.addFirst(new SoftReference<>(scratch));
            }
         }
      } finally {
         this.permits.release();
      }
   }

   /**
    * Concurrent solves allowed for scratch of about {@code megabytesPerSolve} within {@code 1 / heapShare} of the
    * maximum heap: at least two, at most one per processor. A system property can set it (1 to 64).
    */
   static int concurrency(String property, int megabytesPerSolve, int heapShare) {
      String value = System.getProperty(property);
      if (value != null && !value.isBlank()) {
         try {
            return Mth.clamp(Integer.parseInt(value.trim()), 1, 64);
         } catch (NumberFormatException ignored) {
         }
      }
      long maxMemory = Runtime.getRuntime().maxMemory();
      long budgetMegabytes = maxMemory == Long.MAX_VALUE ? Long.MAX_VALUE : maxMemory / Math.max(1, heapShare) / (1024L * 1024L);
      long byMemory = budgetMegabytes / Math.max(1, megabytesPerSolve);
      int processors = Runtime.getRuntime().availableProcessors();
      return (int)Math.max(2L, Math.min(processors, byMemory));
   }
}
