package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiHorizontalQuality;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Downloads the map tiles of the LOD sections Distant Horizons will request next, ahead of its queue.
 *
 * <p>DH offers the generator only its nearest 20 tasks per thread, and the near ones are cached by the
 * time the world has loaded, so the far rings (hundreds of megabytes of buildings and roads in a city at
 * 1:1) only started downloading once the near ones had been generated, about 90 s into a cold 4096-chunk
 * load. This walks the sections of DH's render distance nearest first, like its queue, and downloads the
 * raw tiles of each to the disk cache. Nothing is decoded: the per-LOD preparation still reads them when DH
 * asks for the section, and tiles already on disk are skipped.
 */
final class LodLookaheadPrefetcher implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.dhLookahead", "true"));
   private static final int WORKERS = Math.max(1, Math.min(8, Integer.getInteger("tellus.dhLookahead.workers", 3)));
   private static final int SECTION_MIN_DETAIL = 6;
   private static final int ROOT_SECTION_DETAIL = SECTION_MIN_DETAIL + 8;
   private static final int SECTION_DATA_COLUMNS = 64;
   private static final int FALLBACK_RADIUS_CHUNKS = 256;
   /** A new plan starts once the player is this far from the last plan's centre. */
   private static final long REPLAN_DISTANCE_BLOCKS = 1024L;

   interface AreaDownloader {
      void download(int chunkPosMinX, int chunkPosMinZ, int detailLevel, int widthInColumns);
   }

   private final Supplier<List<int[]>> targets;
   private final AreaDownloader downloader;
   private final boolean enabled;
   private final Object lock = new Object();
   private ExecutorService workers;
   private Plan plan;
   private boolean closed;

   LodLookaheadPrefetcher(Supplier<List<int[]>> targets, AreaDownloader downloader, boolean enabled) {
      this.targets = targets;
      this.downloader = downloader;
      this.enabled = enabled && ENABLED;
   }

   /** Starts or moves the plan for the current player position; cheap when nothing changed. */
   void tick() {
      if (!this.enabled) {
         return;
      }
      List<int[]> positions = this.targets.get();
      if (positions.isEmpty()) {
         return;
      }
      int[] target = positions.get(0);
      synchronized (this.lock) {
         if (this.closed) {
            return;
         }
         Plan current = this.plan;
         if (current != null
            && Math.max(Math.abs((long)target[0] - current.centerX), Math.abs((long)target[1] - current.centerZ)) < REPLAN_DISTANCE_BLOCKS) {
            return;
         }
         if (current != null) {
            current.cancelled = true;
         }
         Plan next = new Plan(target[0], target[1], sections(target[0], target[1]));
         this.plan = next;
         if (this.workers == null) {
            AtomicInteger index = new AtomicInteger();
            this.workers = Executors.newFixedThreadPool(WORKERS, runnable -> {
               Thread thread = new Thread(runnable, "tellus-dh-lookahead-" + index.incrementAndGet());
               thread.setDaemon(true);
               thread.setPriority(Thread.MIN_PRIORITY);
               return thread;
            });
         }
         for (int i = 0; i < WORKERS; i++) {
            this.workers.execute(() -> this.run(next));
         }
         LOGGER.info("Tellus DH lookahead: fetching map tiles for {} LOD sections around [{}, {}] ahead of Distant Horizons", next.sections.size(), next.centerX, next.centerZ);
      }
   }

   private void run(Plan plan) {
      int index;
      while (!plan.cancelled && (index = plan.cursor.getAndIncrement()) < plan.sections.size()) {
         Section section = plan.sections.get(index);
         try {
            this.downloader.download(section.chunkMinX(), section.chunkMinZ(), section.dataDetail(), SECTION_DATA_COLUMNS);
         } catch (CancellationException error) {
            return;
         } catch (RuntimeException error) {
            // A failed tile is fetched again by the section's own preparation.
            LOGGER.debug("Tellus DH lookahead download failed for detail {} section [{}, {}]", section.dataDetail(), section.x(), section.z(), error);
         }
      }
   }

   @Override
   public void close() {
      synchronized (this.lock) {
         this.closed = true;
         if (this.plan != null) {
            this.plan.cancelled = true;
         }
         if (this.workers != null) {
            this.workers.shutdownNow();
            this.workers = null;
         }
      }
   }

   /**
    * The sections DH's LodQuadTree renders around a centre for the configured render distance and
    * horizontal quality, nearest first (the order its generation queue takes them in).
    */
   static List<Section> sections(int centerX, int centerZ) {
      int radiusChunks = FALLBACK_RADIUS_CHUNKS;
      EDhApiHorizontalQuality quality = EDhApiHorizontalQuality.MEDIUM;
      try {
         Integer configured = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
         if (configured != null && configured > 0) {
            radiusChunks = configured;
         }
         EDhApiHorizontalQuality configuredQuality = DhApi.Delayed.configs.graphics().horizontalQuality().getValue();
         if (configuredQuality != null) {
            quality = configuredQuality;
         }
      } catch (RuntimeException | LinkageError error) {
         LOGGER.debug("Distant Horizons render distance is unavailable; Tellus lookahead uses {} chunks", FALLBACK_RADIUS_CHUNKS, error);
      }
      return sections(centerX, centerZ, radiusChunks, quality.quadraticBase, quality.distanceUnitInBlocks);
   }

   static List<Section> sections(int centerX, int centerZ, int radiusChunks, double quadraticBase, int distanceUnitInBlocks) {
      int diameter = radiusChunks * 16 * 2;
      int treeRootDetail = Math.max(SECTION_MIN_DETAIL, (int)(Math.log(diameter) / Math.log(2.0)));
      double unit = distanceUnitInBlocks * 16.0;
      double logBase = Math.log(quadraticBase);
      int minSectionDetail = detailFromDistance(diameter, unit, logBase) - 1;
      minSectionDetail = Math.min(minSectionDetail, treeRootDetail);
      minSectionDetail = Math.min(minSectionDetail, ROOT_SECTION_DETAIL - SECTION_MIN_DETAIL);
      int minRootRenderDetail = Math.max(minSectionDetail, 0);
      long treeMinX = (long)centerX - diameter / 2;
      long treeMinZ = (long)centerZ - diameter / 2;
      int half = (Math.floorDiv(diameter, 2) >> treeRootDetail) + 1;
      int rootCenterX = centerX >> treeRootDetail;
      int rootCenterZ = centerZ >> treeRootDetail;
      List<Section> result = new ArrayList<>();
      for (int rx = rootCenterX - half; rx <= rootCenterX + half; rx++) {
         for (int rz = rootCenterZ - half; rz <= rootCenterZ + half; rz++) {
            visit(treeRootDetail, rx, rz, centerX, centerZ, treeMinX, treeMinZ, diameter, unit, logBase, minRootRenderDetail, result);
         }
      }
      result.sort(Comparator.comparingLong(section -> section.manhattanDistance(centerX, centerZ)));
      return result;
   }

   private static void visit(
      int sectionDetail, int x, int z, int centerX, int centerZ, long treeMinX, long treeMinZ, int diameter,
      double unit, double logBase, int minRootRenderDetail, List<Section> out
   ) {
      long width = 1L << sectionDetail;
      long minX = (long)x * width;
      long minZ = (long)z * width;
      boolean overlaps = treeMinX < minX + width && treeMinX + diameter > minX && treeMinZ < minZ + width && treeMinZ + diameter > minZ;
      if (!overlaps || sectionDetail < SECTION_MIN_DETAIL) {
         return;
      }
      double distance = Math.hypot(minX + width / 2.0 - centerX, minZ + width / 2.0 - centerZ);
      int expected = Math.min(detailFromDistance(distance, unit, logBase), minRootRenderDetail) + SECTION_MIN_DETAIL;
      if (sectionDetail > expected) {
         for (int i = 0; i < 4; i++) {
            visit(sectionDetail - 1, x * 2 + (i & 1), z * 2 + (i >> 1), centerX, centerZ, treeMinX, treeMinZ, diameter, unit, logBase,
               minRootRenderDetail, out);
         }
      } else {
         out.add(new Section(sectionDetail, x, z));
      }
   }

   private static int detailFromDistance(double distance, double unit, double logBase) {
      int detail = (int)(Math.log(distance / unit) / logBase);
      return Math.max(0, Math.min(detail, ROOT_SECTION_DETAIL));
   }

   record Section(int sectionDetail, int x, int z) {
      int dataDetail() {
         return this.sectionDetail - SECTION_MIN_DETAIL;
      }

      int chunkMinX() {
         return (int)(((long)this.x << this.sectionDetail) >> 4);
      }

      int chunkMinZ() {
         return (int)(((long)this.z << this.sectionDetail) >> 4);
      }

      long manhattanDistance(int centerX, int centerZ) {
         long width = 1L << this.sectionDetail;
         long centerBlockX = ((long)this.x << this.sectionDetail) + width / 2;
         long centerBlockZ = ((long)this.z << this.sectionDetail) + width / 2;
         return Math.abs(centerBlockX - centerX) + Math.abs(centerBlockZ - centerZ);
      }
   }

   private static final class Plan {
      private final int centerX;
      private final int centerZ;
      private final List<Section> sections;
      private final AtomicInteger cursor = new AtomicInteger();
      private volatile boolean cancelled;

      private Plan(int centerX, int centerZ, List<Section> sections) {
         this.centerX = centerX;
         this.centerZ = centerZ;
         this.sections = sections;
      }
   }
}
