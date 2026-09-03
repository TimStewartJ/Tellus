package com.yucareux.tellus.integration.distant_horizons.managed;

public final class ManagedTerrainNetworkPolicy {
   private static final ThreadLocal<Integer> CACHE_ONLY_DEPTH = ThreadLocal.withInitial(() -> 0);

   private ManagedTerrainNetworkPolicy() {
   }

   public static Scope cacheOnly() {
      int previousDepth = CACHE_ONLY_DEPTH.get();
      CACHE_ONLY_DEPTH.set(previousDepth + 1);
      return new Scope(previousDepth);
   }

   /**
    * Temporarily lets a nonblocking companion planner enqueue its own inputs inside a cache-only LOD build.
    */
   public static Scope networkAllowed() {
      int previousDepth = CACHE_ONLY_DEPTH.get();
      CACHE_ONLY_DEPTH.remove();
      return new Scope(previousDepth);
   }

   public static boolean isCacheOnly() {
      return CACHE_ONLY_DEPTH.get() > 0;
   }

   public static final class Scope implements AutoCloseable {
      private final int restoreDepth;
      private boolean closed;

      private Scope(int restoreDepth) {
         this.restoreDepth = restoreDepth;
      }

      @Override
      public void close() {
         if (!this.closed) {
            this.closed = true;
            if (this.restoreDepth == 0) {
               CACHE_ONLY_DEPTH.remove();
            } else {
               CACHE_ONLY_DEPTH.set(this.restoreDepth);
            }
         }
      }
   }
}
