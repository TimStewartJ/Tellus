package com.yucareux.tellus.integration.distant_horizons.managed;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ManagedTerrainAvailability {
   public static final byte READY = 0;
   public static final byte SPLIT = 1;
   public static final byte WAIT = 2;
   public static final byte PRIORITY = 3;
   private static final int DEFAULT_BATCH_CELLS_PER_SIDE = intProperty("tellus.managedDownloads.batchCellsPerSide", 8, 1, 64);
   static final int DEFAULT_PROGRESSIVE_BATCH_WIDTH_CHUNKS = ManagedTerrainCell.SIZE_CHUNKS * DEFAULT_BATCH_CELLS_PER_SIDE;
   private static final Map<String, Coverage> COVERAGE = new ConcurrentHashMap<>();

   private ManagedTerrainAvailability() {
   }

   public static String key(Object generator) {
      Objects.requireNonNull(generator, "generator");
      return generator.getClass().getName() + "@" + Integer.toUnsignedString(System.identityHashCode(generator), 16);
   }

   public static void markReady(String key, ManagedTerrainCell cell, boolean degraded) {
      Coverage coverage = COVERAGE.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Coverage());
      coverage.failed.remove(cell);
      coverage.ready.add(cell);
      if (degraded) {
         coverage.degraded.add(cell);
      } else {
         coverage.degraded.remove(cell);
      }
   }

   public static void markFailed(String key, ManagedTerrainCell cell) {
      Coverage coverage = COVERAGE.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Coverage());
      coverage.ready.remove(cell);
      coverage.degraded.remove(cell);
      coverage.failed.add(cell);
   }

   public static boolean isReady(String key, ManagedTerrainCell cell) {
      Coverage coverage = COVERAGE.get(key);
      return coverage != null && coverage.ready.contains(cell);
   }

   public static boolean isDegraded(String key, ManagedTerrainCell cell) {
      Coverage coverage = COVERAGE.get(key);
      return coverage != null && coverage.degraded.contains(cell);
   }

   public static boolean isFailed(String key, ManagedTerrainCell cell) {
      Coverage coverage = COVERAGE.get(key);
      return coverage != null && coverage.failed.contains(cell);
   }

   public static void configureProgressiveBatchWidth(String key, int widthChunks) {
      Coverage coverage = COVERAGE.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> new Coverage());
      coverage.progressiveBatchWidthChunks = Math.max(ManagedTerrainCell.SIZE_CHUNKS, widthChunks);
   }

   public static byte availability(String key, int minChunkX, int minChunkZ, int widthChunks) {
      Coverage coverage = COVERAGE.get(key);
      if (coverage == null || coverage.ready.isEmpty()) {
         return WAIT;
      }
      int safeWidth = Math.max(1, widthChunks);
      long maxXLong = (long)minChunkX + safeWidth - 1L;
      long maxZLong = (long)minChunkZ + safeWidth - 1L;
      int maxChunkX = (int)Math.min(Integer.MAX_VALUE, maxXLong);
      int maxChunkZ = (int)Math.min(Integer.MAX_VALUE, maxZLong);
      ManagedTerrainCell min = ManagedTerrainCell.containingChunk(minChunkX, minChunkZ);
      ManagedTerrainCell max = ManagedTerrainCell.containingChunk(maxChunkX, maxChunkZ);
      long total = ((long)max.x() - min.x() + 1L) * ((long)max.z() - min.z() + 1L);
      boolean canSplit = safeWidth > coverage.progressiveBatchWidthChunks;
      // Large, mostly undownloaded regions should cost at most the number of cached
      // cells, not their entire area. Counts are only used to prove incompleteness;
      // READY still requires checking every requested cell.
      if (total > coverage.ready.size()) {
         if (canSplit) {
            for (ManagedTerrainCell cell : coverage.ready) {
               if (cell.x() >= min.x() && cell.x() <= max.x() && cell.z() >= min.z() && cell.z() <= max.z()) {
                  return SPLIT;
               }
            }
         }
         return WAIT;
      }
      boolean anyReady = false;
      boolean anyMissing = false;
      for (int z = min.z(); z <= max.z(); z++) {
         for (int x = min.x(); x <= max.x(); x++) {
            if (coverage.ready.contains(new ManagedTerrainCell(x, z))) {
               anyReady = true;
            } else {
               anyMissing = true;
               if (!canSplit) {
                  return WAIT;
               }
            }
            if (anyReady && anyMissing) {
               return SPLIT;
            }
         }
      }
      if (!anyMissing) {
         return READY;
      }
      // Splitting below the current adaptive download-batch size while that batch is still
      // arriving permanently expands DH's quadtree into thousands of fine requests.
      // Keep the request queued instead; it becomes ready as soon as the rest of its batch lands.
      return WAIT;
   }

   public static void clear(String key) {
      COVERAGE.remove(key);
   }

   public static void clearAll() {
      COVERAGE.clear();
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      int value = Integer.getInteger(key, defaultValue);
      return Math.max(min, Math.min(max, value));
   }

   private static final class Coverage {
      private final Set<ManagedTerrainCell> ready = ConcurrentHashMap.newKeySet();
      private final Set<ManagedTerrainCell> degraded = ConcurrentHashMap.newKeySet();
      private final Set<ManagedTerrainCell> failed = ConcurrentHashMap.newKeySet();
      private volatile int progressiveBatchWidthChunks = DEFAULT_PROGRESSIVE_BATCH_WIDTH_CHUNKS;
   }
}
