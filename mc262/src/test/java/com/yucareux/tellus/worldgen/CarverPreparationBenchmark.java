package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.caves.TellusVanillaNoiseCaveSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntBinaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Measures the read-only vanilla-density work moved from Minecraft's serialized carver status into
 * Tellus's parallel fill status. The same chunks and immutable RandomState are used in each lane.
 */
public final class CarverPreparationBenchmark {
   private static final long SEED = 3258465013987465813L;
   private static final int CHUNK_AREA = 256;
   private static final int MIN_Y = -640;
   private static final int UNDERGROUND_DEPTH = 512;
   private static final int SEA_LEVEL = 64;

   private CarverPreparationBenchmark() {
   }

   public static void main(String[] args) throws Exception {
      int radius = intArg(args, "--radius=", 6);
      int threads = intArg(
         args, "--threads=", Math.max(1, Runtime.getRuntime().availableProcessors() - 2)
      );
      int rounds = intArg(args, "--rounds=", 3);
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      RegistryAccess.Frozen registries = ChunkGenerationBenchmark.loadServerRegistries();
      NoiseGeneratorSettings settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
         .getOrThrow(NoiseGeneratorSettings.OVERWORLD)
         .value();
      RandomState randomState = RandomState.create(
         settings, registries.lookupOrThrow(Registries.NOISE), SEED
      );
      TellusVanillaNoiseCaveSampler sampler = new TellusVanillaNoiseCaveSampler(settings);
      List<ChunkPos> chunks = square(radius);

      // Warm the density-function wiring and JIT away from measured chunks.
      runLane("warmup", sampler, randomState, List.of(new ChunkPos(4096, 4096)), 1);
      System.out.printf(
         Locale.ROOT,
         "CARVER_PREP_BENCH chunks=%d radius=%d threads=%d rounds=%d maxHeapMiB=%d%n",
         chunks.size(),
         radius,
         threads,
         rounds,
         Runtime.getRuntime().maxMemory() >> 20
      );
      for (int round = 1; round <= rounds; round++) {
         LaneResult serial = runLane("serialized", sampler, randomState, chunks, 1);
         LaneResult parallel = runLane("parallel-fill", sampler, randomState, chunks, threads);
         if (serial.checksum() != parallel.checksum()
            || serial.mutations() != parallel.mutations()) {
            throw new IllegalStateException(
               "Parallel cave preparation changed output in round " + round
            );
         }
         System.out.printf(
            Locale.ROOT,
            "CARVER_PREP_RESULT round=%d serial_ms=%.1f serial_ms_per_chunk=%.2f "
               + "parallel_ms=%.1f parallel_ms_per_chunk=%.2f speedup=%.2fx "
               + "mutations=%d avg_mutations=%.1f retained_mib=%.2f checksum=%d%n",
            round,
            serial.wallNanos() / 1.0e6,
            serial.wallNanos() / 1.0e6 / chunks.size(),
            parallel.wallNanos() / 1.0e6,
            parallel.wallNanos() / 1.0e6 / chunks.size(),
            (double)serial.wallNanos() / parallel.wallNanos(),
            serial.mutations(),
            serial.mutations() / (double)chunks.size(),
            serial.estimatedBytes() / 1024.0 / 1024.0,
            serial.checksum()
         );
      }
   }

   private static LaneResult runLane(
      String name,
      TellusVanillaNoiseCaveSampler sampler,
      RandomState randomState,
      List<ChunkPos> chunks,
      int threads
   ) throws Exception {
      long[] checksums = new long[chunks.size()];
      int[] mutations = new int[chunks.size()];
      int[] estimatedBytes = new int[chunks.size()];
      ExecutorService pool = threads > 1
         ? Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "carver-prep-bench-" + name);
            thread.setDaemon(true);
            return thread;
         })
         : null;
      long start = System.nanoTime();
      try {
         List<Future<?>> futures = new ArrayList<>(chunks.size());
         for (int index = 0; index < chunks.size(); index++) {
            int resultIndex = index;
            Runnable task = () -> {
               ChunkPos pos = chunks.get(resultIndex);
               int[] surfaces = surfaces(pos);
               int[] generationFloors = new int[CHUNK_AREA];
               int[] floodGuards = new int[CHUNK_AREA];
               java.util.Arrays.fill(floodGuards, Integer.MAX_VALUE);
               for (int i = 0; i < CHUNK_AREA; i++) {
                  generationFloors[i] = Math.max(
                     MIN_Y,
                     UndergroundGenerationDepthPolicy.generationFloorY(
                        surfaces[i], UNDERGROUND_DEPTH
                     )
                  );
               }
               IntBinaryOperator surfaceSampler =
                  CarverPreparationBenchmark::syntheticSurface;
               TellusVanillaNoiseCaveSampler.PreparedVanillaCavePlan plan =
                  sampler.prepare(
                     randomState,
                     pos,
                     MIN_Y,
                     SEA_LEVEL,
                     true,
                     true,
                     true,
                     true,
                     surfaces,
                     surfaceSampler,
                     floodGuards,
                     generationFloors
                  );
               checksums[resultIndex] = plan.checksum();
               mutations[resultIndex] = plan.mutationCount();
               estimatedBytes[resultIndex] = plan.estimatedBytes();
            };
            if (pool == null) {
               task.run();
            } else {
               futures.add(pool.submit(task));
            }
         }
         for (Future<?> future : futures) {
            future.get();
         }
      } finally {
         if (pool != null) {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
         }
      }

      long checksum = 1L;
      long mutationCount = 0L;
      long byteCount = 0L;
      for (int index = 0; index < chunks.size(); index++) {
         checksum = checksum * 31L + checksums[index];
         mutationCount += mutations[index];
         byteCount += estimatedBytes[index];
      }
      return new LaneResult(
         System.nanoTime() - start, mutationCount, byteCount, checksum
      );
   }

   private static int[] surfaces(ChunkPos pos) {
      int[] surfaces = new int[CHUNK_AREA];
      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localX = 0; localX < 16; localX++) {
            surfaces[localZ * 16 + localX] = syntheticSurface(
               pos.getMinBlockX() + localX, pos.getMinBlockZ() + localZ
            );
         }
      }
      return surfaces;
   }

   private static int syntheticSurface(int worldX, int worldZ) {
      long mixed = worldX * 341873128712L ^ worldZ * 132897987541L;
      return 96 + (int)Math.floorMod(mixed ^ mixed >>> 29, 96L);
   }

   private static List<ChunkPos> square(int radius) {
      List<ChunkPos> chunks = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
      for (int z = -radius; z <= radius; z++) {
         for (int x = -radius; x <= radius; x++) {
            chunks.add(new ChunkPos(x, z));
         }
      }
      return chunks;
   }

   private static int intArg(String[] args, String prefix, int defaultValue) {
      for (String arg : args) {
         if (arg.startsWith(prefix)) {
            return Integer.parseInt(arg.substring(prefix.length()));
         }
      }
      return defaultValue;
   }

   private record LaneResult(
      long wallNanos, long mutations, long estimatedBytes, long checksum
   ) {
   }
}
