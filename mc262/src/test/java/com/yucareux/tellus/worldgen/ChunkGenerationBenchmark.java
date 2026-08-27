package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Headless full-chunk generation benchmark. Unlike {@link FastLodDataLoadingSimulation}, this
 * constructs the real {@link EarthChunkGenerator} against the vanilla datapack registries (loaded
 * the same way {@code WorldLoader} does, so tags are bound) and runs the Tellus-owned worker-thread
 * stages ({@code createBiomes} + {@code fillFromNoise}) on real {@link ProtoChunk}s, so the
 * per-chunk CPU and I/O cost of Tellus terrain can be measured without starting a Minecraft server.
 *
 * <p>Run with {@code ./gradlew :mc262:benchmarkChunkGeneration} (see {@code mc262/build.gradle}
 * for the {@code -Pbench*} knobs). Pass 1 on a fresh {@code benchGameDir} is network-cold; later
 * passes are in-memory warm. {@code --threads=N} emulates the worker pool, and
 * {@code -PbenchJvmArgs="-XX:StartFlightRecording=..."} attaches JFR.
 *
 * <p>Limitations: Increase Height worlds need the Tellus mixins (Fabric Loader), so this runs at
 * the standard height range. Stages that require a {@code WorldGenRegion} (structures, carvers,
 * biome decoration, lighting) are out of scope here and must be measured in-game or on a dedicated
 * server with {@code -Dtellus.debug.fullChunkPerf=true -Dtellus.chunkgen.timing=true}.
 */
public final class ChunkGenerationBenchmark {
   private ChunkGenerationBenchmark() {
   }

   public static void main(String[] args) throws Exception {
      Options options = Options.parse(args);
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();

      HolderLookup.Provider registries = loadServerRegistries();
      EarthGeneratorSettings settings = options.settings();
      EarthChunkGenerator generator = EarthChunkGenerator.create(registries, settings);
      RandomState randomState = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, options.seed());
      // createState() would resolve structure-set placements we do not exercise; we only need the seed.
      java.lang.reflect.Field worldSeed = EarthChunkGenerator.class.getDeclaredField("worldSeed");
      worldSeed.setAccessible(true);
      worldSeed.setLong(generator, options.seed());

      int minY = generator.getMinY();
      int height = generator.getGenDepth();
      LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(minY, height);
      PalettedContainerFactory containerFactory = PalettedContainerFactory.create((RegistryAccess)registries);
      StructureManager structures = new NoStructures();

      int centerX = (int)Math.round(options.longitude() * EarthProjection.blocksPerDegree(settings.worldScale()));
      int centerZ = (int)Math.round(EarthProjection.latToBlockZ(options.latitude(), settings.worldScale()));
      ChunkPos center = new ChunkPos(Math.floorDiv(centerX, 16), Math.floorDiv(centerZ, 16));
      List<ChunkPos> order = spiral(center, options.radius());

      System.out.printf(
         Locale.ROOT,
         "CHUNKGEN_BENCH profile=%s scale=%.4f experimentalHeight=%s minY=%d height=%d sections=%d center=%.5f,%.5f centerChunk=[%d,%d] radius=%d chunks=%d threads=%d passes=%d seed=%d%n",
         options.profile(),
         settings.worldScale(),
         settings.experimentalIncreaseHeight(),
         minY,
         height,
         height >> 4,
         options.latitude(),
         options.longitude(),
         center.x(),
         center.z(),
         options.radius(),
         order.size(),
         options.threads(),
         options.passes(),
         options.seed()
      );
      System.out.printf(
         Locale.ROOT,
         "CHUNKGEN_BENCH_FLAGS fastFullChunk=%s nonBlockingTerrainInputs=%s deferTerrainRefinement=%s legacyBlocking=%s fullChunkPerf=%s chunkgenTiming=%s availableProcessors=%d maxHeapMb=%d%n",
         System.getProperty("tellus.chunkgen.fastFullChunk", "true"),
         System.getProperty("tellus.chunkgen.nonBlockingTerrainInputs", "false"),
         System.getProperty("tellus.chunkgen.deferTerrainRefinement", "false"),
         System.getProperty("tellus.chunkdetail.legacyBlocking", "true"),
         System.getProperty("tellus.debug.fullChunkPerf", "false"),
         System.getProperty("tellus.chunkgen.timing", "false"),
         Runtime.getRuntime().availableProcessors(),
         Runtime.getRuntime().maxMemory() >> 20
      );

      if (options.prefetch()) {
         int minBlockX = (center.x() - options.radius()) << 4;
         int minBlockZ = (center.z() - options.radius()) << 4;
         int maxBlockX = ((center.x() + options.radius()) << 4) + 15;
         int maxBlockZ = ((center.z() + options.radius()) << 4) + 15;
         long prefetchStart = System.nanoTime();
         TellusWorldgenSources.prefetchForArea(
            minBlockX, minBlockZ, maxBlockX, maxBlockZ, settings, settings.enableRoads(), settings.enableBuildings(), settings.worldScale()
         ).join();
         System.out.printf(Locale.ROOT, "CHUNKGEN_BENCH_PREFETCH area_ms=%.1f%n", millis(System.nanoTime() - prefetchStart));
      }

      if (options.warmupRadius() >= 0) {
         // JIT warm-up on a distant area so pass 1 measures "memory-cold for this area" rather than
         // interpreter start-up; offset far enough that no DEM/cover tile or water region is shared.
         ChunkPos warmupCenter = new ChunkPos(center.x() + options.warmupOffsetChunks(), center.z());
         List<ChunkPos> warmupOrder = spiral(warmupCenter, options.warmupRadius());
         long warmupStart = System.nanoTime();
         runPass(0, options, generator, randomState, heightAccessor, containerFactory, structures, warmupOrder);
         System.out.printf(
            Locale.ROOT,
            "CHUNKGEN_BENCH_WARMUP chunks=%d centerChunk=[%d,%d] wall_s=%.2f%n",
            warmupOrder.size(),
            warmupCenter.x(),
            warmupCenter.z(),
            (System.nanoTime() - warmupStart) / 1.0e9
         );
      }

      for (int pass = 1; pass <= options.passes(); pass++) {
         runPass(
            pass,
            options,
            generator,
            randomState,
            heightAccessor,
            containerFactory,
            structures,
            order
         );
      }
      System.out.println("CHUNKGEN_BENCH_COMPLETE");
      System.exit(0);
   }

   private static void runPass(
      int pass,
      Options options,
      EarthChunkGenerator generator,
      RandomState randomState,
      LevelHeightAccessor heightAccessor,
      PalettedContainerFactory containerFactory,
      StructureManager structures,
      List<ChunkPos> order
   ) throws Exception {
      int count = order.size();
      long[] biomesNs = new long[count];
      long[] fillNs = new long[count];
      long[] totalNs = new long[count];
      long[] cpuNs = new long[count];
      int[] nonEmptySections = new int[count];
      long[] checksums = new long[count];
      ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
      boolean cpuTime = threadMx.isCurrentThreadCpuTimeSupported();

      ExecutorService pool = options.threads() > 1 ? Executors.newFixedThreadPool(options.threads(), runnable -> {
         Thread thread = new Thread(runnable, "bench-worker");
         thread.setDaemon(true);
         return thread;
      }) : null;

      Runtime runtime = Runtime.getRuntime();
      System.gc();
      long heapBefore = runtime.totalMemory() - runtime.freeMemory();
      long gcTimeBefore = totalGcMillis();
      long gcCountBefore = totalGcCount();
      long passStart = System.nanoTime();
      try {
         List<Future<?>> futures = new ArrayList<>(count);
         for (int i = 0; i < count; i++) {
            int index = i;
            Runnable task = () -> {
               ChunkPos pos = order.get(index);
               ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, heightAccessor, containerFactory, null);
               long cpuStart = cpuTime ? threadMx.getCurrentThreadCpuTime() : 0L;
               long start = System.nanoTime();
               generator.createBiomes(randomState, Blender.empty(), structures, chunk).join();
               long afterBiomes = System.nanoTime();
               generator.fillFromNoise(Blender.empty(), randomState, structures, chunk).join();
               long end = System.nanoTime();
               cpuNs[index] = cpuTime ? threadMx.getCurrentThreadCpuTime() - cpuStart : 0L;
               biomesNs[index] = afterBiomes - start;
               fillNs[index] = end - afterBiomes;
               totalNs[index] = end - start;
               int sections = 0;
               long checksum = 17L;
               LevelChunkSection[] chunkSections = chunk.getSections();
               for (int s = 0; s < chunkSections.length; s++) {
                  if (!chunkSections[s].hasOnlyAir()) {
                     sections++;
                     checksum = checksum * 31L + s;
                  }
               }
               for (int z = 0; z < 16; z++) {
                  for (int x = 0; x < 16; x++) {
                     checksum = checksum * 31L + chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                     checksum = checksum * 31L + chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
                  }
               }
               nonEmptySections[index] = sections;
               checksums[index] = checksum;
            };
            if (pool != null) {
               futures.add(pool.submit(task));
            } else {
               task.run();
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
      long passNs = System.nanoTime() - passStart;
      long heapAfter = runtime.totalMemory() - runtime.freeMemory();
      long gcMillis = totalGcMillis() - gcTimeBefore;
      long gcCount = totalGcCount() - gcCountBefore;

      Stats biomes = Stats.of(biomesNs);
      Stats fill = Stats.of(fillNs);
      Stats total = Stats.of(totalNs);
      Stats cpu = Stats.of(cpuNs);
      double wallSeconds = passNs / 1.0e9;
      double chunksPerSecond = count / wallSeconds;
      double avgSections = Arrays.stream(nonEmptySections).average().orElse(0.0);
      long sumCpuNs = Arrays.stream(cpuNs).sum();
      long sumWallNs = Arrays.stream(totalNs).sum();
      long checksum = 0L;
      for (long c : checksums) {
         checksum = checksum * 31L + c;
      }

      System.out.printf(
         Locale.ROOT,
         "CHUNKGEN_PASS pass=%d chunks=%d threads=%d wall_s=%.2f chunks_per_s=%.2f ms_per_chunk_wall=%.1f "
            + "total{mean=%.1f p50=%.1f p90=%.1f p99=%.1f max=%.1f} biomes{mean=%.1f p50=%.1f p90=%.1f max=%.1f} "
            + "fill{mean=%.1f p50=%.1f p90=%.1f p99=%.1f max=%.1f} cpu{mean=%.1f p50=%.1f p90=%.1f max=%.1f} "
            + "cpu_over_wall=%.2f sum_cpu_s=%.2f gc_ms=%d gc_count=%d avg_nonempty_sections=%.1f heap_delta_mb=%d checksum=%d%n",
         pass,
         count,
         options.threads(),
         wallSeconds,
         chunksPerSecond,
         passNs / 1.0e6 / count,
         total.mean(), total.p50(), total.p90(), total.p99(), total.max(),
         biomes.mean(), biomes.p50(), biomes.p90(), biomes.max(),
         fill.mean(), fill.p50(), fill.p90(), fill.p99(), fill.max(),
         cpu.mean(), cpu.p50(), cpu.p90(), cpu.max(),
         sumWallNs == 0L ? 0.0 : (double)sumCpuNs / sumWallNs,
         sumCpuNs / 1.0e9,
         gcMillis,
         gcCount,
         avgSections,
         (heapAfter - heapBefore) >> 20,
         checksum
      );

      if (options.perChunk() && pass > 0) {
         for (int i = 0; i < count; i++) {
            ChunkPos pos = order.get(i);
            System.out.printf(
               Locale.ROOT,
               "CHUNKGEN_CHUNK pass=%d idx=%d chunk=[%d,%d] total_ms=%.2f biomes_ms=%.2f fill_ms=%.2f cpu_ms=%.2f sections=%d%n",
               pass, i, pos.x(), pos.z(), millis(totalNs[i]), millis(biomesNs[i]), millis(fillNs[i]), millis(cpuNs[i]), nonEmptySections[i]
            );
         }
      }
   }

   private static long totalGcMillis() {
      long total = 0L;
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
         long time = gc.getCollectionTime();
         if (time > 0L) {
            total += time;
         }
      }
      return total;
   }

   private static long totalGcCount() {
      long total = 0L;
      for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
         long count = gc.getCollectionCount();
         if (count > 0L) {
            total += count;
         }
      }
      return total;
   }

   /**
    * Loads the static + worldgen registries from the vanilla datapack exactly as
    * {@code WorldLoader.load} does, so biome/block tags are bound (the datagen
    * {@code VanillaRegistries.createLookup()} leaves tags unbound, which the
    * surface palette code dereferences).
    */
   private static RegistryAccess.Frozen loadServerRegistries() {
      VanillaPackResources vanilla = ServerPacksSource.createVanillaPackSource();
      CloseableResourceManager resourceManager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla));
      LayeredRegistryAccess<RegistryLayer> layered = RegistryLayer.createRegistryAccess();
      List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(
         resourceManager, layered.getLayer(RegistryLayer.STATIC)
      );
      List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(
         layered.getAccessForLoading(RegistryLayer.WORLDGEN), pendingTags
      );
      RegistryAccess.Frozen worldgen = RegistryDataLoader.load(
         resourceManager, lookups, RegistryDataLoader.WORLDGEN_REGISTRIES, Util.backgroundExecutor()
      ).join();
      layered = layered.replaceFrom(RegistryLayer.WORLDGEN, worldgen);
      pendingTags.forEach(Registry.PendingTags::apply);
      return layered.compositeAccess();
   }

   private static List<ChunkPos> spiral(ChunkPos center, int radius) {
      List<ChunkPos> order = new ArrayList<>();
      order.add(center);
      for (int ring = 1; ring <= radius; ring++) {
         for (int dx = -ring; dx <= ring; dx++) {
            order.add(new ChunkPos(center.x() + dx, center.z() - ring));
            order.add(new ChunkPos(center.x() + dx, center.z() + ring));
         }
         for (int dz = -ring + 1; dz <= ring - 1; dz++) {
            order.add(new ChunkPos(center.x() - ring, center.z() + dz));
            order.add(new ChunkPos(center.x() + ring, center.z() + dz));
         }
      }
      return order;
   }

   private static double millis(long nanos) {
      return nanos / 1_000_000.0;
   }

   /** Stub that reports no structure starts so {@code fillFromNoise} can run without a level. */
   private static final class NoStructures extends StructureManager {
      private NoStructures() {
         super(null, null, null);
      }

      @Override
      public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
         return List.of();
      }

      @Override
      public boolean shouldGenerateStructures() {
         return false;
      }
   }

   private record Stats(double mean, double p50, double p90, double p99, double max) {
      static Stats of(long[] nanos) {
         long[] sorted = nanos.clone();
         Arrays.sort(sorted);
         double sum = 0.0;
         for (long value : sorted) {
            sum += value;
         }
         int n = sorted.length;
         return new Stats(
            millis((long)(sum / Math.max(1, n))),
            millis(sorted[Math.min(n - 1, (int)(n * 0.50))]),
            millis(sorted[Math.min(n - 1, (int)(n * 0.90))]),
            millis(sorted[Math.min(n - 1, (int)(n * 0.99))]),
            millis(sorted[n - 1])
         );
      }
   }

   private record Options(
      double latitude,
      double longitude,
      int radius,
      int passes,
      int threads,
      long seed,
      String profile,
      double scale,
      boolean experimentalHeight,
      boolean prefetch,
      boolean perChunk,
      int warmupRadius,
      int warmupOffsetChunks
   ) {
      private static Options parse(String[] args) {
         // Defaults reproduce the user's Yosemite world (1:1, experimental height, caves+ores on, roads/buildings off).
         double latitude = 37.75641472133222;
         double longitude = -119.59291934967041;
         int radius = 4;
         int passes = 2;
         int threads = 1;
         long seed = 3258465013987465813L;
         String profile = "yosemite";
         double scale = 1.0;
         boolean experimentalHeight = true;
         boolean prefetch = false;
         boolean perChunk = false;
         int warmupRadius = -1;
         int warmupOffsetChunks = 256;
         for (String arg : args) {
            if (arg.startsWith("--latitude=")) {
               latitude = Double.parseDouble(value(arg));
            } else if (arg.startsWith("--longitude=")) {
               longitude = Double.parseDouble(value(arg));
            } else if (arg.startsWith("--radius=")) {
               radius = Integer.parseInt(value(arg));
            } else if (arg.startsWith("--passes=")) {
               passes = Integer.parseInt(value(arg));
            } else if (arg.startsWith("--threads=")) {
               threads = Integer.parseInt(value(arg));
            } else if (arg.startsWith("--seed=")) {
               seed = Long.parseLong(value(arg));
            } else if (arg.startsWith("--profile=")) {
               profile = value(arg);
            } else if (arg.startsWith("--scale=")) {
               scale = Double.parseDouble(value(arg));
            } else if (arg.startsWith("--experimentalHeight=")) {
               experimentalHeight = Boolean.parseBoolean(value(arg));
            } else if (arg.startsWith("--prefetch=")) {
               prefetch = Boolean.parseBoolean(value(arg));
            } else if (arg.startsWith("--perChunk=")) {
               perChunk = Boolean.parseBoolean(value(arg));
            } else if (arg.startsWith("--warmupRadius=")) {
               warmupRadius = Integer.parseInt(value(arg));
            } else if (arg.startsWith("--warmupOffsetChunks=")) {
               warmupOffsetChunks = Integer.parseInt(value(arg));
            } else {
               throw new IllegalArgumentException("Unknown benchmark option: " + arg);
            }
         }
         if (radius < 0 || radius > 64) {
            throw new IllegalArgumentException("Radius must be between 0 and 64 chunks");
         }
         if (threads < 1 || threads > 256) {
            throw new IllegalArgumentException("Threads must be between 1 and 256");
         }
         if (warmupRadius > 64) {
            throw new IllegalArgumentException("Warm-up radius must be at most 64 chunks");
         }
         return new Options(
            latitude, longitude, radius, passes, threads, seed, profile, scale, experimentalHeight, prefetch, perChunk, warmupRadius, warmupOffsetChunks
         );
      }

      private static String value(String arg) {
         return arg.substring(arg.indexOf('=') + 1);
      }

      EarthGeneratorSettings settings() {
         AtomicReference<String> error = new AtomicReference<>();
         JsonElement encoded = EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, EarthGeneratorSettings.DEFAULT)
            .resultOrPartial(error::set)
            .orElseThrow(() -> new IllegalStateException("Unable to encode benchmark settings: " + error.get()));
         JsonObject object = encoded.getAsJsonObject();
         object.addProperty("world_scale", this.scale);
         object.addProperty("spawn_latitude", this.latitude);
         object.addProperty("spawn_longitude", this.longitude);
         object.addProperty("experimental_increase_height", this.experimentalHeight);
         if (this.experimentalHeight) {
            object.addProperty("experimental_height_coordinate_profile", HighYPackedCoordinateProfile.PROFILE_ID);
         }
         object.addProperty("distant_horizons_render_mode", EarthGeneratorSettings.DistantHorizonsRenderMode.FAST.id());
         switch (this.profile) {
            case "yosemite" -> {
               // Mirrors saves/Yosemite/data/minecraft/world_gen_settings.dat from the Tellus-main Prism instance.
               object.addProperty("cave_generation", true);
               object.addProperty("ore_distribution", true);
               object.addProperty("geological_stone_patches", true);
               object.addProperty("lava_pools", true);
               object.addProperty("underground_depth", 256);
               object.addProperty("thin_shell_terrain", false);
               object.addProperty("custom_trees", true);
               object.addProperty("enable_water", true);
               object.addProperty("enable_roads", false);
               object.addProperty("enable_buildings", false);
               object.addProperty("distant_horizons_water_resolver", true);
               object.addProperty("automatic_height_scaling", true);
               object.addProperty("climate_based_built_up_terrain", true);
               for (String structure : List.of(
                  "add_strongholds", "add_villages", "add_mineshafts", "add_ocean_monuments", "add_woodland_mansions",
                  "add_desert_temples", "add_jungle_temples", "add_pillager_outposts", "add_ruined_portals", "add_shipwrecks",
                  "add_ocean_ruins", "add_buried_treasure", "add_igloos", "add_witch_huts", "add_ancient_cities",
                  "add_trial_chambers", "add_trail_ruins", "deep_dark", "geodes"
               )) {
                  object.addProperty(structure, false);
               }
            }
            case "osm" -> {
               object.addProperty("enable_roads", true);
               object.addProperty("enable_buildings", true);
               object.addProperty("enable_water", true);
            }
            case "default" -> {
            }
            default -> throw new IllegalArgumentException("Unknown profile: " + this.profile);
         }
         error.set(null);
         return EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, object)
            .resultOrPartial(error::set)
            .orElseThrow(() -> new IllegalStateException("Unable to create benchmark settings: " + error.get()));
      }
   }
}
