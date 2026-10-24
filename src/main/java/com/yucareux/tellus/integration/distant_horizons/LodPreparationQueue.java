package com.yucareux.tellus.integration.distant_horizons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Prepares source inputs before a request occupies a DH generation slot. */
final class LodPreparationQueue implements AutoCloseable {
   // Uncached areas spend most of their preparation waiting on the network, so the number of areas
   // prepared at once (not the CPU) bounds how fast DH workers can be fed. Scale it with the machine.
   private static final int MAX_IN_FLIGHT = intProperty(
      "tellus.dhPrepare.maxInFlight", Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors())), 1, 64
   );
   private static final int MAX_READY = intProperty("tellus.dhPrepare.maxReady", Math.max(16, MAX_IN_FLIGHT * 4), 1, 512);
   private static final int MAX_QUEUED = intProperty("tellus.dhPrepare.maxQueued", 512, 1, 4096);
   private static final long STALE_NANOS = TimeUnit.SECONDS.toNanos(30);
   private static final long TARGET_REFRESH_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
   private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();

   private final Object lock = new Object();
   private final ServerLevel level;
   private final Function<Request, CompletableFuture<Void>> starter;
   private final Map<Request, Entry> entries = new HashMap<>();
   private List<Target> targets = List.of();
   private boolean targetsInitialized;
   private boolean targetRefreshPending;
   private long nextTargetRefresh;
   private long nextSequence;
   private int inFlight;
   private int ready;
   private int queued;
   private ScheduledFuture<?> wakeup;
   private long wakeupAt;
   private long wakeupVersion;
   private boolean closed;

   LodPreparationQueue(Object wrappedLevel, Function<Request, CompletableFuture<Void>> starter) {
      this.level = wrappedLevel instanceof ServerLevel serverLevel ? serverLevel : null;
      this.targetsInitialized = this.level == null;
      this.starter = Objects.requireNonNull(starter, "starter");
   }

   void refreshTargets() {
      if (this.level == null) {
         return;
      }
      synchronized (this.lock) {
         if (this.closed || this.targetRefreshPending || System.nanoTime() < this.nextTargetRefresh) {
            return;
         }
         this.targetRefreshPending = true;
      }
      try {
         // Player lists and positions are only read on the server thread. DH's
         // availability callbacks and the source coordinator use this snapshot.
         this.level.getServer().execute(() -> {
            List<Target> snapshot = new ArrayList<>();
            for (ServerPlayer player : this.level.players()) {
               snapshot.add(new Target(player.getBlockX(), player.getBlockZ()));
            }
            synchronized (this.lock) {
               this.targetRefreshPending = false;
               this.nextTargetRefresh = System.nanoTime() + TARGET_REFRESH_NANOS;
               if (this.closed) {
                  return;
               }
               this.targets = List.copyOf(snapshot);
               this.targetsInitialized = !snapshot.isEmpty();
               for (Entry entry : this.entries.values()) {
                  entry.priority = this.priority(entry.request);
               }
               if (!this.entries.isEmpty()) {
                  this.schedule(2L);
               }
            }
         });
      } catch (RuntimeException error) {
         synchronized (this.lock) {
            this.targetRefreshPending = false;
            this.nextTargetRefresh = System.nanoTime() + TARGET_REFRESH_NANOS;
         }
         throw error;
      }
   }

   /** The latest player block positions (x, z), as last read on the server thread. */
   List<int[]> targetBlockPositions() {
      synchronized (this.lock) {
         List<int[]> positions = new ArrayList<>(this.targets.size());
         for (Target target : this.targets) {
            positions.add(new int[]{target.x(), target.z()});
         }
         return positions;
      }
   }

   /** True in singleplayer and LAN hosts, where DH's render distance is the player's own. */
   boolean isIntegratedServer() {
      return this.level != null && !this.level.getServer().isDedicatedServer();
   }

   boolean isReady(Request request) {
      synchronized (this.lock) {
         if (this.closed || !this.targetsInitialized) {
            return false;
         }
         long now = System.nanoTime();
         Entry existing = this.entries.get(request);
         if (existing != null) {
            existing.lastRequested = now;
            return existing.state == State.READY;
         }

         long priority = this.priority(request);
         if (this.queued >= MAX_QUEUED) {
            Entry farthest = null;
            for (Entry entry : this.entries.values()) {
               if (entry.state == State.QUEUED && (farthest == null || entry.priority > farthest.priority)) {
                  farthest = entry;
               }
            }
            if (farthest == null || priority >= farthest.priority) {
               return false;
            }
            this.remove(farthest);
         }
         this.entries.put(request, new Entry(request, priority, this.nextSequence++, now));
         this.queued++;
         // Collect the availability scan before selecting work: DH visits its
         // pending map in arbitrary order, so starting immediately favors distant tiles.
         this.schedule(2L);
         return false;
      }
   }

   CompletableFuture<Void> takeReady(Request request) {
      synchronized (this.lock) {
         Entry entry = this.entries.get(request);
         if (entry == null || entry.state != State.READY) {
            return null;
         }
         this.remove(entry);
         this.schedule(0L);
         return entry.future;
      }
   }

   private void drain(long version) {
      List<Entry> start = new ArrayList<>();
      List<CompletableFuture<Void>> cancel = new ArrayList<>();
      synchronized (this.lock) {
         if (this.closed || version != this.wakeupVersion) {
            return;
         }
         this.wakeup = null;
         long now = System.nanoTime();
         for (Entry entry : new ArrayList<>(this.entries.values())) {
            if (now - entry.lastRequested >= STALE_NANOS) {
               this.remove(entry);
               if (entry.future != null) {
                  cancel.add(entry.future);
               }
            }
         }
         if (this.targetsInitialized) {
            while (this.inFlight < MAX_IN_FLIGHT) {
               Entry closest = null;
               for (Entry entry : this.entries.values()) {
                  if (entry.state == State.QUEUED
                     && (closest == null || entry.priority < closest.priority
                        || entry.priority == closest.priority && entry.sequence < closest.sequence)) {
                     closest = entry;
                  }
               }
               if (closest == null) {
                  break;
               }
               if (this.ready + this.inFlight >= MAX_READY) {
                  Entry farthestReady = null;
                  for (Entry entry : this.entries.values()) {
                     if (entry.state == State.READY && (farthestReady == null || entry.priority > farthestReady.priority)) {
                        farthestReady = entry;
                     }
                  }
                  if (farthestReady == null || closest.priority >= farthestReady.priority) {
                     break;
                  }
                  // A teleport must not leave the new view behind a full buffer
                  // of distant ready entries. Their source tiles remain cached.
                  this.remove(farthestReady);
               }
               closest.state = State.RUNNING;
               this.queued--;
               this.inFlight++;
               start.add(closest);
            }
         }
         if (!this.entries.isEmpty()) {
            this.schedule(1000L);
         }
      }
      cancel.forEach(future -> future.cancel(true));
      for (Entry entry : start) {
         this.start(entry);
      }
   }

   private void start(Entry entry) {
      synchronized (this.lock) {
         if (this.closed || this.entries.get(entry.request) != entry) {
            return;
         }
      }
      CompletableFuture<Void> future;
      try {
         future = Objects.requireNonNull(this.starter.apply(entry.request), "prefetch future");
      } catch (Throwable error) {
         future = CompletableFuture.failedFuture(error);
      }
      boolean cancel;
      synchronized (this.lock) {
         entry.future = future;
         cancel = this.closed || this.entries.get(entry.request) != entry;
      }
      if (cancel) {
         future.cancel(true);
         return;
      }
      future.whenComplete((ignored, error) -> {
         synchronized (this.lock) {
            if (this.entries.get(entry.request) != entry) {
               return;
            }
            // Hand failures back to generateLod too, preserving its interruption
            // handling and existing source fallbacks instead of stranding a WAIT.
            entry.state = State.READY;
            this.inFlight--;
            this.ready++;
            this.schedule(0L);
         }
      });
   }

   private long priority(Request request) {
      long centerX = (long)request.chunkPosMinX() * 16L + ((long)request.widthInColumns() << request.detailLevel()) / 2L;
      long centerZ = (long)request.chunkPosMinZ() * 16L + ((long)request.widthInColumns() << request.detailLevel()) / 2L;
      long distance = Long.MAX_VALUE;
      for (Target target : this.targets) {
         distance = Math.min(distance, Math.max(Math.abs(centerX - target.x()), Math.abs(centerZ - target.z())));
      }
      // Match DH's distance/detail preference, considering the nearest player.
      return distance == Long.MAX_VALUE ? Long.MAX_VALUE : distance / (request.detailLevel() + 6L);
   }

   private void remove(Entry entry) {
      this.entries.remove(entry.request);
      switch (entry.state) {
         case QUEUED -> this.queued--;
         case RUNNING -> this.inFlight--;
         case READY -> this.ready--;
      }
   }

   /** Called with lock held; the coordinator only submits asynchronous source work. */
   private void schedule(long delayMillis) {
      if (this.closed) {
         return;
      }
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
      if (this.wakeup != null) {
         if (this.wakeupAt <= deadline) {
            return;
         }
         this.wakeup.cancel(false);
      }
      this.wakeupAt = deadline;
      long version = ++this.wakeupVersion;
      this.wakeup = SCHEDULER.schedule(() -> this.drain(version), delayMillis, TimeUnit.MILLISECONDS);
   }

   @Override
   public void close() {
      List<CompletableFuture<Void>> cancel = new ArrayList<>();
      synchronized (this.lock) {
         this.closed = true;
         if (this.wakeup != null) {
            this.wakeup.cancel(false);
            this.wakeup = null;
         }
         for (Entry entry : this.entries.values()) {
            if (entry.future != null) {
               cancel.add(entry.future);
            }
         }
         this.entries.clear();
         this.queued = this.inFlight = this.ready = 0;
      }
      cancel.forEach(future -> future.cancel(true));
   }

   private static ScheduledThreadPoolExecutor createScheduler() {
      ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
         Thread thread = new Thread(runnable, "tellus-dh-preparation");
         thread.setDaemon(true);
         return thread;
      });
      scheduler.setRemoveOnCancelPolicy(true);
      return scheduler;
   }

   private static int intProperty(String name, int fallback, int min, int max) {
      return Math.max(min, Math.min(max, Integer.getInteger(name, fallback)));
   }

   record Request(int chunkPosMinX, int chunkPosMinZ, int detailLevel, int widthInColumns) {
      static Request fromChunkArea(int chunkPosMinX, int chunkPosMinZ, int widthChunks, byte targetDetail) {
         int detail = Byte.toUnsignedInt(targetDetail);
         if (widthChunks <= 0 || detail > 24) {
            return null;
         }
         long widthBlocks = (long)widthChunks * 16L;
         long cellSize = 1L << detail;
         long columns = widthBlocks / cellSize;
         return widthBlocks % cellSize != 0L || columns <= 0L || columns > Integer.MAX_VALUE
            ? null : new Request(chunkPosMinX, chunkPosMinZ, detail, (int)columns);
      }
   }

   private record Target(int x, int z) {
   }

   private enum State { QUEUED, RUNNING, READY }

   private static final class Entry {
      private final Request request;
      private final long sequence;
      private long priority;
      private long lastRequested;
      private State state = State.QUEUED;
      private CompletableFuture<Void> future;

      private Entry(Request request, long priority, long sequence, long now) {
         this.request = request;
         this.priority = priority;
         this.sequence = sequence;
         this.lastRequested = now;
      }
   }
}
