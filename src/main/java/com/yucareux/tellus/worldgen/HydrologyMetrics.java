package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Optional timings for in-game diagnosis; disabled counters have no clock reads. */
final class HydrologyMetrics {
   private static final boolean ENABLED = Boolean.getBoolean("tellus.debugHydrology");
   private static final AtomicLong NEXT = new AtomicLong();
   private static final LongAdder PLAN_NS = new LongAdder(), PLANS = new LongAdder();
   private static final LongAdder MAP_NS = new LongAdder(), MAPS = new LongAdder();
   private static final LongAdder LOD_NS = new LongAdder(), LODS = new LongAdder(), LEGACY = new LongAdder();
   private static final LongAdder NETWORK_NS = new LongAdder(), NETWORKS = new LongAdder();
   private HydrologyMetrics() {}
   static long now() { return ENABLED ? System.nanoTime() : 0L; }
   static void plan(long start) { if (ENABLED) { PLAN_NS.add(System.nanoTime() - start); PLANS.increment(); } }
   static void map(long start) { if (ENABLED) { MAP_NS.add(System.nanoTime() - start); MAPS.increment(); } }
   static void network(long start) { if (ENABLED) { NETWORK_NS.add(System.nanoTime() - start); NETWORKS.increment(); } }
   static void lod(long start, boolean legacy) {
      if (!ENABLED) return;
      LOD_NS.add(System.nanoTime() - start); LODS.increment(); if (legacy) LEGACY.increment();
      long now = System.nanoTime(), next = NEXT.get();
      if (now < next || !NEXT.compareAndSet(next, now + 15_000_000_000L)) return;
      Tellus.LOGGER.info("Hydrology: {} river networks / {} ms, {} plans / {} ms, {} map inputs / {} ms, {} LOD areas / {} ms ({} coastal/fallback solves)",
         NETWORKS.sumThenReset(), NETWORK_NS.sumThenReset() / 1_000_000L, PLANS.sumThenReset(), PLAN_NS.sumThenReset() / 1_000_000L, MAPS.sumThenReset(), MAP_NS.sumThenReset() / 1_000_000L,
         LODS.sumThenReset(), LOD_NS.sumThenReset() / 1_000_000L, LEGACY.sumThenReset());
   }
}
