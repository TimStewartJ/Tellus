package com.yucareux.tellus.integration.geotp;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.compat.TellusMinecraftCompat;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class GeoTeleportManager {
   private static final int THREADS = intProperty("tellus.geotp.threads", 2, 1, 8);
   private static final int QUEUE_CAPACITY = intProperty("tellus.geotp.queueCapacity", 8, 1, 64);
   private static final int TIMEOUT_SECONDS = intProperty("tellus.geotp.timeoutSeconds", 45, 5, 300);
   private final Object executorLock = new Object();
   private final AtomicLong requestIds = new AtomicLong();
   private final ConcurrentMap<UUID, Request> requests = new ConcurrentHashMap<>();
   private volatile ThreadPoolExecutor executor;
   private volatile ScheduledExecutorService timeoutExecutor;

   public void request(
      MinecraftServer server,
      ServerPlayer player,
      ServerLevel level,
      EarthChunkGenerator generator,
      double latitude,
      double longitude
   ) {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(generator, "generator");
      UUID playerId = player.getUUID();
      ResourceKey<Level> dimension = level.dimension();
      long id = this.requestIds.incrementAndGet();
      Request request = new Request(id, player);
      int minY = generator.getMinY();
      int maxYExclusive = minY + generator.getGenDepth();
      FutureTask<Void> task = new FutureTask<>(() -> {
         long startNanos = System.nanoTime();
         try {
            BlockPos target = generator.getSurfacePosition(minY, maxYExclusive, latitude, longitude);
            this.complete(server, playerId, dimension, generator, request, target, null, startNanos);
         } catch (Throwable error) {
            this.complete(server, playerId, dimension, generator, request, null, error, startNanos);
         }
         return null;
      });
      request.task = task;
      Request previous = this.requests.put(playerId, request);
      if (previous != null) {
         this.cancelRequest(previous);
      }
      player.sendSystemMessage(Component.translatable("tellus.command.geotp.resolving"));

      try {
         this.ensureExecutor().execute(task);
         request.timeout = this.ensureTimeoutExecutor().schedule(
            () -> this.timeout(server, playerId, request), TIMEOUT_SECONDS, TimeUnit.SECONDS
         );
      } catch (RejectedExecutionException error) {
         this.requests.remove(playerId, request);
         this.cancelRequest(request);
         Tellus.LOGGER.warn("GeoTP executor rejected request {} for player {}", id, playerId, error);
         player.sendSystemMessage(Component.translatable("tellus.command.geotp.busy"));
      }
   }

   public void onServerStopping() {
      this.requests.values().forEach(this::cancelRequest);
      this.requests.clear();
      synchronized (this.executorLock) {
         if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
         }
         if (this.timeoutExecutor != null) {
            this.timeoutExecutor.shutdownNow();
            this.timeoutExecutor = null;
         }
      }
   }

   public void onPlayerDisconnect(ServerPlayer player) {
      Request request = this.requests.remove(player.getUUID());
      if (request != null) {
         this.cancelRequest(request);
      }
   }

   private void complete(
      MinecraftServer server,
      UUID playerId,
      ResourceKey<Level> dimension,
      EarthChunkGenerator generator,
      Request request,
      BlockPos target,
      Throwable error,
      long startNanos
   ) {
      server.execute(() -> {
         if (this.requests.get(playerId) != request) {
            return;
         }
         ServerPlayer player = server.getPlayerList().getPlayer(playerId);
         if (player == null || player != request.player) {
            this.finishRequest(playerId, request);
            return;
         }
         if (error != null) {
            this.failRequest(playerId, request, player, error);
            return;
         }

         ServerLevel currentLevel = MinecraftVersionCompat.serverLevel(player);
         if (!dimension.equals(currentLevel.dimension())
            || currentLevel.getChunkSource().getGenerator() != generator) {
            this.finishRequest(playerId, request);
            player.sendSystemMessage(Component.translatable("tellus.command.geotp.world_changed"));
            return;
         }
         if (!TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack())) {
            this.finishRequest(playerId, request);
            player.sendSystemMessage(Component.translatable("tellus.command.geotp.no_permission"));
            return;
         }

         GeoTeleportVersionCompat.requestFullChunk(
            currentLevel,
            Math.floorDiv(target.getX(), 16),
            Math.floorDiv(target.getZ(), 16),
            (ready, chunkError) -> server.execute(
               () -> this.finishTeleport(
                  server, playerId, dimension, generator, request, target, ready, chunkError, startNanos
               )
            )
         );
      });
   }

   private void finishTeleport(
      MinecraftServer server,
      UUID playerId,
      ResourceKey<Level> dimension,
      EarthChunkGenerator generator,
      Request request,
      BlockPos target,
      boolean chunkReady,
      Throwable error,
      long startNanos
   ) {
      if (!this.requests.remove(playerId, request)) {
         return;
      }
      request.cancelTimeout();
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null || player != request.player) {
         return;
      }
      if (error != null || !chunkReady) {
         Throwable failure = error == null ? new IllegalStateException("Destination chunk did not reach FULL status") : error;
         Tellus.LOGGER.warn("GeoTP request {} failed while loading the destination chunk for player {}", request.id, playerId, failure);
         player.sendSystemMessage(Component.translatable("tellus.command.geotp.failed"));
         return;
      }
      ServerLevel currentLevel = MinecraftVersionCompat.serverLevel(player);
      if (!dimension.equals(currentLevel.dimension())
         || currentLevel.getChunkSource().getGenerator() != generator) {
         player.sendSystemMessage(Component.translatable("tellus.command.geotp.world_changed"));
         return;
      }
      if (!TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack())) {
         player.sendSystemMessage(Component.translatable("tellus.command.geotp.no_permission"));
         return;
      }

      player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
      Tellus.LOGGER.info(
         "GeoTP request {} resolved and loaded for player {} in {} ms",
         request.id,
         playerId,
         TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
      );
   }

   private void failRequest(UUID playerId, Request request, ServerPlayer player, Throwable error) {
      this.finishRequest(playerId, request);
      Tellus.LOGGER.warn("GeoTP request {} failed for player {}", request.id, playerId, error);
      player.sendSystemMessage(Component.translatable("tellus.command.geotp.failed"));
   }

   private void finishRequest(UUID playerId, Request request) {
      if (this.requests.remove(playerId, request)) {
         request.cancelTimeout();
      }
   }

   private void timeout(MinecraftServer server, UUID playerId, Request request) {
      if (!this.requests.remove(playerId, request)) {
         return;
      }
      this.cancelRequest(request);
      server.execute(() -> {
         ServerPlayer player = server.getPlayerList().getPlayer(playerId);
         if (player != null) {
            player.sendSystemMessage(Component.translatable("tellus.command.geotp.timeout"));
         }
      });
   }

   private ThreadPoolExecutor ensureExecutor() {
      ThreadPoolExecutor current = this.executor;
      if (current != null && !current.isShutdown()) {
         return current;
      }
      synchronized (this.executorLock) {
         current = this.executor;
         if (current == null || current.isShutdown()) {
            current = new ThreadPoolExecutor(
               THREADS,
               THREADS,
               0L,
               TimeUnit.MILLISECONDS,
               new ArrayBlockingQueue<>(QUEUE_CAPACITY),
               daemonFactory("tellus-geotp-"),
               new ThreadPoolExecutor.AbortPolicy()
            );
            this.executor = current;
         }
         return current;
      }
   }

   private ScheduledExecutorService ensureTimeoutExecutor() {
      ScheduledExecutorService current = this.timeoutExecutor;
      if (current != null && !current.isShutdown()) {
         return current;
      }
      synchronized (this.executorLock) {
         current = this.timeoutExecutor;
         if (current == null || current.isShutdown()) {
            current = new ScheduledThreadPoolExecutor(1, daemonFactory("tellus-geotp-timeout-"));
            this.timeoutExecutor = current;
         }
         return current;
      }
   }

   private void cancelRequest(Request request) {
      request.cancel();
      ThreadPoolExecutor current = this.executor;
      FutureTask<Void> task = request.task;
      if (current != null && task != null) {
         current.remove(task);
         current.purge();
      }
   }

   private static ThreadFactory daemonFactory(String prefix) {
      return new ThreadFactory() {
         private final AtomicInteger index = new AtomicInteger();

         @Override
         public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + this.index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
         }
      };
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      return Math.max(min, Math.min(max, Integer.getInteger(key, defaultValue)));
   }

   private static final class Request {
      private final long id;
      private final ServerPlayer player;
      private volatile FutureTask<Void> task;
      private volatile ScheduledFuture<?> timeout;

      private Request(long id, ServerPlayer player) {
         this.id = id;
         this.player = player;
      }

      private void cancel() {
         FutureTask<Void> currentTask = this.task;
         if (currentTask != null) {
            currentTask.cancel(true);
         }
         this.cancelTimeout();
      }

      private void cancelTimeout() {
         ScheduledFuture<?> currentTimeout = this.timeout;
         if (currentTimeout != null) {
            currentTimeout.cancel(false);
         }
      }
   }
}
