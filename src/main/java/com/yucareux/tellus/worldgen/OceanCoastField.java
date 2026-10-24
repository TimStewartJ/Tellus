package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.util.Mth;

/**
 * Cached, Overture-only ocean mask and coastal distance field. The retained
 * 512x512 core is independent of the underlying MVT boundaries.
 *
 * <p>A field has a power-of-two cell size. Cell {@code c} is sampled at block
 * {@code c * cellSize + cellSize / 2}, so the block-resolution field (cell size 1)
 * samples every block and a coarse field samples exactly the block a distant LOD
 * column is centered on. Coarse fields cover proportionally larger areas per tile.</p>
 */
public final class OceanCoastField {
   static final int CORE_SIZE = 512;
   // Coarse fields serve one DH section (64 columns) per tile, so a LOD never waits on a larger shared build.
   private static final int COARSE_CORE_SIZE = 64;
   private static final int CARDINAL_COST = 10;
   private static final int DIAGONAL_COST = 14;
   private static final int[] DX = new int[]{1, 0, -1, 0, 1, 1, -1, -1};
   private static final int[] DZ = new int[]{0, 1, 0, -1, 1, -1, 1, -1};
   private static final int[] COST = new int[]{
      CARDINAL_COST,
      CARDINAL_COST,
      CARDINAL_COST,
      CARDINAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST,
      DIAGONAL_COST
   };
   // Block-field tiles (about 576 KB each) serve LOD cells up to 8 blocks and every coastal region solve. 64 tiles
   // were fewer than the ring those LODs cover around a coastal player (about 160 at Cancun), so tiles were evicted
   // and rebuilt (over 100 ms each at 1:1) while that ring generated.
   private static final int CACHE_TILES = intProperty("tellus.oceanCoastCacheTiles", 192, 4, 512);
   private static final int COARSE_CACHE_TILES = intProperty("tellus.oceanCoastCoarseCacheTiles", 1024, 16, 16384);
   private static final int RECENT_TILES = 32;
   // A block-field build at 1:1 pads its 512-block core with the 513-block transition: about 65 MB of scratch.
   // Few tiles are built at once (each serves a 512-block square), so a sixteenth of the heap is plenty.
   private static final ScratchPool<Scratch> SCRATCH = new ScratchPool<>(
      Scratch::new, ScratchPool.concurrency("tellus.oceanCoastBuildConcurrency", 65, 16), 2
   );

   private final TellusOsmWaterSource waterSource;
   private final double worldScale;
   private final int transitionBlocks;
   private final RawDepthSampler rawDepthSampler;
   private final int cellShift;
   private final int cellSize;
   private final int coreSize;
   private final Cache<Long, MacroTile> cache;
   // Complete tiles read recently, without the bounded cache's locks and access-order bookkeeping: coastal
   // solves and LOD columns sample the field once per block, and that bookkeeping cost more than the samples.
   private final AtomicReferenceArray<RecentTile> recent = new AtomicReferenceArray<>(RECENT_TILES);
   private final AtomicLong generation = new AtomicLong();
   private final MacroTile noOceanTile;

   public OceanCoastField(
      TellusOsmWaterSource waterSource,
      double worldScale,
      int transitionBlocks,
      RawDepthSampler rawDepthSampler
   ) {
      this(waterSource, worldScale, transitionBlocks, rawDepthSampler, 0);
   }

   public OceanCoastField(
      TellusOsmWaterSource waterSource,
      double worldScale,
      int transitionBlocks,
      RawDepthSampler rawDepthSampler,
      int cellShift
   ) {
      this.waterSource = waterSource;
      this.worldScale = worldScale;
      this.transitionBlocks = OceanFloorProfile.clampTransitionDistance(transitionBlocks);
      this.rawDepthSampler = rawDepthSampler;
      this.cellShift = Mth.clamp(cellShift, 0, 16);
      this.cellSize = 1 << this.cellShift;
      this.coreSize = this.cellShift == 0 ? CORE_SIZE : COARSE_CORE_SIZE;
      this.cache = CacheBuilder.newBuilder().maximumSize(this.cellShift == 0 ? CACHE_TILES : COARSE_CACHE_TILES).build();
      this.noOceanTile = MacroTile.noOcean(this.coreSize, this.transitionBlocks);
   }

   int cellSize() {
      return this.cellSize;
   }

   /** The sample of any cell with no ocean within the transition distance. */
   OceanCoastSample inlandSample() {
      return new OceanCoastSample(false, this.transitionBlocks, false, TellusOsmWaterSource.CoverageStatus.COMPLETE);
   }

   public OceanCoastSample sample(int blockX, int blockZ) {
      int cellX = blockX >> this.cellShift;
      int cellZ = blockZ >> this.cellShift;
      int macroX = Math.floorDiv(cellX, this.coreSize);
      int macroZ = Math.floorDiv(cellZ, this.coreSize);
      MacroTile tile = this.tile(macroX, macroZ);

      int localX = Math.floorMod(cellX, this.coreSize);
      int localZ = Math.floorMod(cellZ, this.coreSize);
      int index = localZ * this.coreSize + localX;
      return new OceanCoastSample(
         bit(tile.ocean, index),
         Short.toUnsignedInt(tile.distance[index]),
         bit(tile.correction, index),
         tile.coverageStatus
      );
   }

   private MacroTile tile(int macroX, int macroZ) {
      long key = pack(macroX, macroZ);
      int slot = (int)(HydrologyBlockCache.mix(key) >>> 59);
      long generation = this.generation.get();
      RecentTile recent = this.recent.get(slot);
      if (recent != null && recent.macroX == macroX && recent.macroZ == macroZ && recent.generation == generation) {
         return recent.tile;
      }
      try {
         // Full chunks and LOD workers often request the same mask together.
         // Coalesce its construction without changing any coastal samples.
         MacroTile tile = this.cache.get(key, () -> this.build(macroX, macroZ));
         if (tile.coverageStatus != TellusOsmWaterSource.CoverageStatus.COMPLETE) {
            this.cache.invalidate(key);
         } else {
            this.recent.set(slot, new RecentTile(macroX, macroZ, generation, tile));
         }
         return tile;
      } catch (java.util.concurrent.ExecutionException | com.google.common.util.concurrent.UncheckedExecutionException error) {
         if (error.getCause() instanceof RuntimeException cause) throw cause;
         throw new IllegalStateException("Unable to read the ocean mask", error.getCause());
      }
   }

   /**
    * Visits every ocean block of a block-resolution field within an area, tile by tile. Tiles that are
    * incomplete or fail to build are skipped (each is requested once), so this never throws for coverage.
    */
   void forEachCompleteOcean(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, BlockVisitor visitor) {
      if (this.cellShift != 0) {
         throw new IllegalStateException("Block visits need a block-resolution coast field");
      }
      for (int tz = Math.floorDiv(minBlockZ, this.coreSize); tz <= Math.floorDiv(maxBlockZ, this.coreSize); tz++) {
         for (int tx = Math.floorDiv(minBlockX, this.coreSize); tx <= Math.floorDiv(maxBlockX, this.coreSize); tx++) {
            MacroTile tile;
            try {
               tile = this.tile(tx, tz);
            } catch (RuntimeException ignored) {
               continue;
            }
            if (tile.coverageStatus != TellusOsmWaterSource.CoverageStatus.COMPLETE || !tile.anyOcean) {
               continue;
            }
            int originX = tx * this.coreSize;
            int originZ = tz * this.coreSize;
            int lowX = Math.max(minBlockX, originX), highX = Math.min(maxBlockX, originX + this.coreSize - 1);
            int lowZ = Math.max(minBlockZ, originZ), highZ = Math.min(maxBlockZ, originZ + this.coreSize - 1);
            for (int z = lowZ; z <= highZ; z++) {
               int row = (z - originZ) * this.coreSize - originX;
               for (int x = lowX; x <= highX; x++) {
                  if (bit(tile.ocean, row + x)) {
                     visitor.visit(x, z);
                  }
               }
            }
         }
      }
   }

   @FunctionalInterface
   interface BlockVisitor {
      void visit(int blockX, int blockZ);
   }

   /** Exact mask query used only to skip inland work; no coastline is approximated. */
   boolean intersectsOcean(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
      int minX = minBlockX >> this.cellShift, minZ = minBlockZ >> this.cellShift;
      int maxX = maxBlockX >> this.cellShift, maxZ = maxBlockZ >> this.cellShift;
      for (int tz = Math.floorDiv(minZ, this.coreSize); tz <= Math.floorDiv(maxZ, this.coreSize); tz++)
         for (int tx = Math.floorDiv(minX, this.coreSize); tx <= Math.floorDiv(maxX, this.coreSize); tx++) {
            MacroTile tile = this.tile(tx, tz);
            if (tile.coverageStatus != TellusOsmWaterSource.CoverageStatus.COMPLETE)
               throw new OceanCoverageUnavailableException(
                  tile.coverageStatus, tx * this.coreSize * this.cellSize, tz * this.coreSize * this.cellSize);
            if (!tile.anyOcean) continue;
            int lowX = Math.max(0, minX - tx * this.coreSize), highX = Math.min(this.coreSize - 1, maxX - tx * this.coreSize);
            int lowZ = Math.max(0, minZ - tz * this.coreSize), highZ = Math.min(this.coreSize - 1, maxZ - tz * this.coreSize);
            for (int z = lowZ; z <= highZ; z++) for (int x = lowX; x <= highX; x++)
               if (bit(tile.ocean, z * this.coreSize + x)) return true;
         }
      return false;
   }

   public void clear() {
      this.generation.incrementAndGet();
      for (int slot = 0; slot < RECENT_TILES; slot++) {
         this.recent.set(slot, null);
      }
      this.cache.invalidateAll();
      this.cache.cleanUp();
   }

   private MacroTile build(int macroX, int macroZ) {
      // Grid coordinates below are cells; with a cell size of 1 they are blocks.
      int halo = ceilDiv(this.transitionBlocks, this.cellSize) + 1;
      int size = this.coreSize + halo * 2;
      int area = Math.multiplyExact(size, size);
      int minX = macroX * this.coreSize - halo;
      int minZ = macroZ * this.coreSize - halo;
      int sampleOffset = this.cellSize >> 1;
      // Coarse fields read the tiles the LOD water raster of the same cell size reads.
      TellusOsmWaterSource.WaterQueryResult query = this.cellShift == 0
         ? this.waterSource.waterForAreaWithStatus(
            minX, minZ, minX + size - 1, minZ + size - 1, this.worldScale, 0, OsmQueryMode.BLOCKING
         )
         : this.waterSource.waterForAreaWithStatus(
            minX * this.cellSize + sampleOffset,
            minZ * this.cellSize + sampleOffset,
            (minX + size - 1) * this.cellSize + sampleOffset,
            (minZ + size - 1) * this.cellSize + sampleOffset,
            this.worldScale,
            0,
            OsmQueryMode.BLOCKING,
            DhLodWaterResolver.waterQueryTileBudget(this.cellSize),
            DhLodWaterResolver.waterQueryMinTileWidthBlocks(this.cellSize)
         );
      if (!query.complete()) {
         return MacroTile.incomplete(query.coverageStatus(), this.coreSize, this.transitionBlocks);
      }

      boolean anyOceanFeature = false;
      for (OsmWaterFeature feature : query.features()) {
         anyOceanFeature |= feature.oceanHint();
      }
      if (!anyOceanFeature) {
         // Without ocean cells there are no coast seeds: every distance saturates and
         // nothing is corrected. Skip clearing the padded scratch grid (millions of cells).
         return this.noOceanTile;
      }

      Scratch scratch = SCRATCH.acquire();
      try {
         return this.buildOcean(query, halo, size, area, minX, minZ, sampleOffset, scratch);
      } finally {
         SCRATCH.release(scratch);
      }
   }

   private MacroTile buildOcean(
      TellusOsmWaterSource.WaterQueryResult query, int halo, int size, int area, int minX, int minZ, int sampleOffset, Scratch scratch
   ) {
      scratch.ensureCapacity(area);
      boolean[] ocean = scratch.ocean;
      Arrays.fill(ocean, 0, area, false);
      int oceanCells = 0;
      for (OsmWaterFeature feature : query.features()) {
         if (feature.oceanHint()) {
            oceanCells += this.rasterize(feature, minX, minZ, size, ocean);
         }
      }
      if (oceanCells == 0) {
         return this.noOceanTile;
      }

      int maxCost = ceilDiv(this.transitionBlocks, this.cellSize) * CARDINAL_COST;
      int[] distance = scratch.distance;
      int[] nearestSeed = scratch.nearestSeed;
      Arrays.fill(distance, 0, area, Integer.MAX_VALUE);
      Arrays.fill(nearestSeed, 0, area, -1);
      scratch.heap.reset(area);

      for (int z = 1; z < size - 1; z++) {
         int row = z * size;
         for (int x = 1; x < size - 1; x++) {
            int index = row + x;
            if (ocean[index] && isCoastCell(ocean, index, size)) {
               distance[index] = 0;
               nearestSeed[index] = index;
               scratch.heap.addOrDecrease(index, distance);
            }
         }
      }

      propagate(ocean, true, size, maxCost, distance, nearestSeed, scratch.heap);
      boolean[] correctedSeed = scratch.correctedSeed;
      boolean[] validDepth = scratch.validDepth;
      int[] rawDepth = scratch.rawDepth;
      Arrays.fill(correctedSeed, 0, area, false);
      Arrays.fill(validDepth, 0, area, false);
      Arrays.fill(rawDepth, 0, area, 0);
      int triggerBlocks = OceanFloorProfile.scaleBlockDistance(OceanFloorProfile.TRIGGER_SCAN_BLOCKS, this.worldScale);
      int triggerCost = Math.min(maxCost, ceilDiv(triggerBlocks, this.cellSize) * CARDINAL_COST);

      if (this.rawDepthSampler != null && triggerCost >= 0) {
         for (int z = 1; z < size - 1; z++) {
            int row = z * size;
            for (int x = 1; x < size - 1; x++) {
               int index = row + x;
               if (ocean[index] && distance[index] <= triggerCost) {
                  BathymetrySample sample = this.rawDepthSampler.sample(
                     (minX + x) * this.cellSize + sampleOffset, (minZ + z) * this.cellSize + sampleOffset
                  );
                  rawDepth[index] = Math.max(OceanFloorProfile.MIN_DEPTH, sample.depth());
                  validDepth[index] = sample.valid();
               }
            }
         }

         for (int z = 1; z < size - 1; z++) {
            int row = z * size;
            for (int x = 1; x < size - 1; x++) {
               int index = row + x;
               if (!ocean[index] || distance[index] > triggerCost) {
                  continue;
               }

               int coastwardDepth = 0;
               for (int n = 0; n < DX.length; n++) {
                  int neighbor = (z + DZ[n]) * size + x + DX[n];
                  if (ocean[neighbor] && distance[neighbor] < distance[index] && rawDepth[neighbor] > 0) {
                     coastwardDepth = coastwardDepth == 0
                        ? rawDepth[neighbor]
                        : Math.min(coastwardDepth, rawDepth[neighbor]);
                  }
               }

               if (OceanFloorProfile.shouldCorrect(
                  validDepth[index], rawDepth[index], distance[index] * (double)this.cellSize / CARDINAL_COST, coastwardDepth, this.cellSize
               )) {
                  int seed = nearestSeed[index];
                  if (seed >= 0) {
                     correctedSeed[seed] = true;
                  }
               }
            }
         }
      }

      this.dilateCorrectedSeeds(correctedSeed, distance, nearestSeed, size, area, scratch);
      long[] coreOcean = new long[bitWords(this.coreSize * this.coreSize)];
      short[] coreDistance = new short[this.coreSize * this.coreSize];
      long[] coreCorrection = new long[bitWords(this.coreSize * this.coreSize)];
      for (int z = 0; z < this.coreSize; z++) {
         int paddedRow = (z + halo) * size + halo;
         int coreRow = z * this.coreSize;
         for (int x = 0; x < this.coreSize; x++) {
            int padded = paddedRow + x;
            int core = coreRow + x;
            if (ocean[padded]) {
               setBit(coreOcean, core);
            }
            int cost = distance[padded];
            int blocks = cost == Integer.MAX_VALUE
               ? this.transitionBlocks
               : (int)Math.min(this.transitionBlocks, ((long)cost * this.cellSize + CARDINAL_COST / 2) / CARDINAL_COST);
            coreDistance[core] = (short)blocks;
            int seed = nearestSeed[padded];
            if (seed >= 0 && correctedSeed[seed]) {
               setBit(coreCorrection, core);
            }
         }
      }

      return new MacroTile(coreOcean, coreDistance, coreCorrection, TellusOsmWaterSource.CoverageStatus.COMPLETE);
   }

   private void dilateCorrectedSeeds(
      boolean[] correctedSeed,
      int[] coastDistance,
      int[] nearestSeed,
      int size,
      int area,
      Scratch scratch
   ) {
      int[] dilationDistance = scratch.dilationDistance;
      Arrays.fill(dilationDistance, 0, area, Integer.MAX_VALUE);
      scratch.heap.reset(area);
      for (int index = 0; index < area; index++) {
         if (correctedSeed[index]) {
            dilationDistance[index] = 0;
            scratch.heap.addOrDecrease(index, dilationDistance);
         }
      }

      if (scratch.heap.isEmpty()) {
         return;
      }

      int dilationBlocks = OceanFloorProfile.scaleBlockDistance(OceanFloorProfile.CORRECTION_DILATION_BLOCKS, this.worldScale);
      int dilationCost = ceilDiv(dilationBlocks, this.cellSize) * CARDINAL_COST;
      propagate(null, false, size, dilationCost, dilationDistance, null, scratch.heap);
      for (int index = 0; index < area; index++) {
         if (coastDistance[index] == 0 && nearestSeed[index] == index && dilationDistance[index] <= dilationCost) {
            correctedSeed[index] = true;
         }
      }
   }

   private static void propagate(
      boolean[] allowed,
      boolean restrictToAllowed,
      int size,
      int maxCost,
      int[] distance,
      int[] nearestSeed,
      MinHeap heap
   ) {
      while (!heap.isEmpty()) {
         int index = heap.removeMin(distance);
         int current = distance[index];
         if (current >= maxCost) {
            continue;
         }

         int x = index % size;
         int z = index / size;
         for (int n = 0; n < DX.length; n++) {
            int nx = x + DX[n];
            int nz = z + DZ[n];
            if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
               continue;
            }
            int neighbor = nz * size + nx;
            if (restrictToAllowed && !allowed[neighbor]) {
               continue;
            }
            int candidate = current + COST[n];
            if (candidate <= maxCost && candidate < distance[neighbor]) {
               distance[neighbor] = candidate;
               if (nearestSeed != null) {
                  nearestSeed[neighbor] = nearestSeed[index];
               }
               heap.addOrDecrease(neighbor, distance);
            }
         }
      }
   }

   /** Fills the cells whose sample block lies inside the polygon; returns the number of fills. */
   private int rasterize(OsmWaterFeature feature, int gridMinX, int gridMinZ, int gridSize, boolean[] ocean) {
      int partCount = feature.partCount();
      double[][] partXs = new double[partCount][];
      double[][] partZs = new double[partCount][];
      int minWorldX = Integer.MAX_VALUE;
      int maxWorldX = Integer.MIN_VALUE;
      int minWorldZ = Integer.MAX_VALUE;
      int maxWorldZ = Integer.MIN_VALUE;
      double blocksPerDegree = EarthProjection.blocksPerDegree(this.worldScale);
      // Map block coordinates to cell coordinates so integer cell c is its sample block
      // c * cellSize + cellSize / 2. Division by a power of two is exact; cell size 1 is the identity.
      double sampleOffset = this.cellSize >> 1;
      double cellSize = this.cellSize;
      for (int part = 0; part < partCount; part++) {
         int count = feature.pointCount(part);
         partXs[part] = new double[count];
         partZs[part] = new double[count];
         for (int point = 0; point < count; point++) {
            double worldX = (feature.lonAt(part, point) * blocksPerDegree - sampleOffset) / cellSize;
            double worldZ = (EarthProjection.latToBlockZ(feature.latAt(part, point), this.worldScale) - sampleOffset) / cellSize;
            partXs[part][point] = worldX;
            partZs[part][point] = worldZ;
            minWorldX = Math.min(minWorldX, Mth.floor(worldX));
            maxWorldX = Math.max(maxWorldX, Mth.ceil(worldX));
            minWorldZ = Math.min(minWorldZ, Mth.floor(worldZ));
            maxWorldZ = Math.max(maxWorldZ, Mth.ceil(worldZ));
         }
      }

      int gridMaxX = gridMinX + gridSize - 1;
      int gridMaxZ = gridMinZ + gridSize - 1;
      int fillMinX = Math.max(gridMinX, minWorldX);
      int fillMaxX = Math.min(gridMaxX, maxWorldX);
      int fillMinZ = Math.max(gridMinZ, minWorldZ);
      int fillMaxZ = Math.min(gridMaxZ, maxWorldZ);
      int[] filled = new int[1];
      if (fillMaxX >= fillMinX && fillMaxZ >= fillMinZ && !feature.lineGeometry()) {
         ScanlinePolygonRasterizer.fill(
            partXs,
            partZs,
            fillMinX,
            fillMinZ,
            fillMaxX,
            fillMaxZ,
            (worldX, worldZ) -> {
               ocean[(worldZ - gridMinZ) * gridSize + worldX - gridMinX] = true;
               filled[0]++;
            }
         );
      }
      return filled[0];
   }

   private static int ceilDiv(int value, int divisor) {
      return Math.floorDiv(value + divisor - 1, divisor);
   }

   // Cached tiles keep their masks as bitsets: an eighth of the memory of boolean arrays, so twice as
   // many shoreline tiles stay cached for the same footprint.
   private static int bitWords(int bits) {
      return (bits + 63) >>> 6;
   }

   private static boolean bit(long[] bits, int index) {
      return (bits[index >>> 6] & 1L << (index & 63)) != 0L;
   }

   private static void setBit(long[] bits, int index) {
      bits[index >>> 6] |= 1L << (index & 63);
   }

   private static boolean isCoastCell(boolean[] ocean, int index, int size) {
      return !ocean[index - 1] || !ocean[index + 1] || !ocean[index - size] || !ocean[index + size];
   }

   private static long pack(int x, int z) {
      return (long)x & 4294967295L | ((long)z & 4294967295L) << 32;
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }
      try {
         return Mth.clamp(Integer.parseInt(value), min, max);
      } catch (NumberFormatException ignored) {
         return defaultValue;
      }
   }

   @FunctionalInterface
   public interface RawDepthSampler {
      BathymetrySample sample(int blockX, int blockZ);
   }

   public record BathymetrySample(boolean valid, int depth) {
   }

   private record RecentTile(int macroX, int macroZ, long generation, MacroTile tile) {
   }

   private record MacroTile(
      long[] ocean,
      short[] distance,
      long[] correction,
      TellusOsmWaterSource.CoverageStatus coverageStatus,
      boolean anyOcean
   ) {
      MacroTile(long[] ocean, short[] distance, long[] correction, TellusOsmWaterSource.CoverageStatus coverageStatus) {
         this(ocean, distance, correction, coverageStatus, any(ocean));
      }
      private static boolean any(long[] mask) { for (long value : mask) if (value != 0L) return true; return false; }
      private static MacroTile noOcean(int coreSize, int transitionBlocks) {
         short[] distance = new short[coreSize * coreSize];
         Arrays.fill(distance, (short)transitionBlocks);
         return new MacroTile(
            new long[bitWords(coreSize * coreSize)], distance, new long[bitWords(coreSize * coreSize)],
            TellusOsmWaterSource.CoverageStatus.COMPLETE, false
         );
      }
      private static MacroTile incomplete(TellusOsmWaterSource.CoverageStatus status, int coreSize, int transitionBlocks) {
         short[] distance = new short[coreSize * coreSize];
         Arrays.fill(distance, (short)transitionBlocks);
         return new MacroTile(new long[bitWords(coreSize * coreSize)], distance, new long[bitWords(coreSize * coreSize)], status);
      }
   }

   private static final class Scratch {
      private boolean[] ocean = new boolean[0];
      private boolean[] correctedSeed = new boolean[0];
      private boolean[] validDepth = new boolean[0];
      private int[] distance = new int[0];
      private int[] dilationDistance = new int[0];
      private int[] nearestSeed = new int[0];
      private int[] rawDepth = new int[0];
      private final MinHeap heap = new MinHeap();

      private void ensureCapacity(int area) {
         if (this.ocean.length < area) {
            this.ocean = new boolean[area];
            this.correctedSeed = new boolean[area];
            this.validDepth = new boolean[area];
            this.distance = new int[area];
            this.dilationDistance = new int[area];
            this.nearestSeed = new int[area];
            this.rawDepth = new int[area];
         }
      }
   }

   private static final class MinHeap {
      private int[] nodes = new int[0];
      private int[] positions = new int[0];
      private int size;

      private void reset(int capacity) {
         if (this.nodes.length < capacity) {
            this.nodes = new int[capacity];
            this.positions = new int[capacity];
         }
         Arrays.fill(this.positions, 0, capacity, -1);
         this.size = 0;
      }

      private boolean isEmpty() {
         return this.size == 0;
      }

      private void addOrDecrease(int node, int[] priorities) {
         int position = this.positions[node];
         if (position < 0) {
            position = this.size++;
            this.nodes[position] = node;
            this.positions[node] = position;
         }
         this.siftUp(position, priorities);
      }

      private int removeMin(int[] priorities) {
         int result = this.nodes[0];
         this.positions[result] = -1;
         int last = this.nodes[--this.size];
         if (this.size > 0) {
            this.nodes[0] = last;
            this.positions[last] = 0;
            this.siftDown(0, priorities);
         }
         return result;
      }

      private void siftUp(int position, int[] priorities) {
         int node = this.nodes[position];
         while (position > 0) {
            int parent = (position - 1) >>> 1;
            int parentNode = this.nodes[parent];
            if (priorities[parentNode] <= priorities[node]) {
               break;
            }
            this.nodes[position] = parentNode;
            this.positions[parentNode] = position;
            position = parent;
         }
         this.nodes[position] = node;
         this.positions[node] = position;
      }

      private void siftDown(int position, int[] priorities) {
         int node = this.nodes[position];
         while (true) {
            int left = position * 2 + 1;
            if (left >= this.size) {
               break;
            }
            int right = left + 1;
            int best = right < this.size && priorities[this.nodes[right]] < priorities[this.nodes[left]] ? right : left;
            if (priorities[node] <= priorities[this.nodes[best]]) {
               break;
            }
            int child = this.nodes[best];
            this.nodes[position] = child;
            this.positions[child] = position;
            position = best;
         }
         this.nodes[position] = node;
         this.positions[node] = position;
      }
   }
}
