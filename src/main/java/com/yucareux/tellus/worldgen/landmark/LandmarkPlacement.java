package com.yucareux.tellus.worldgen.landmark;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One landmark in one world: where its blocks go at this world scale. It is scaled like the map around it, by
 * Mercator's sec(latitude) over the world scale on every axis, and stands on the median ground of its base (or, for
 * sea-level anchored landmarks such as bridges, at its real elevation). The blocks are worked out on first use and
 * then shared by chunk generation and LODs.
 */
public final class LandmarkPlacement {
   private final LandmarkCatalog.Entry entry;
   private final LandmarkMesh mesh;
   private final double worldScale;
   private final double blocksPerMeter;
   private final double anchorX;
   private final double anchorZ;
   private final double cos;
   private final double sin;
   private final int minX;
   private final int minZ;
   private final int maxX;
   private final int maxZ;
   private final IntBinaryOperator surfaceSampler;
   private final TellusLandmarks.ElevationToY elevationToY;
   private volatile LandmarkVolume volume;
   private volatile boolean failed;
   private volatile int baseY = Integer.MIN_VALUE;
   private final Object baseLock = new Object();
   private volatile BlockState[] materialStates;

   LandmarkPlacement(
      LandmarkCatalog.Entry entry, LandmarkMesh mesh, double worldScale, IntBinaryOperator surfaceSampler, TellusLandmarks.ElevationToY elevationToY
   ) {
      this.entry = entry;
      this.mesh = mesh;
      LandmarkDefinition definition = entry.definition();
      this.worldScale = worldScale;
      this.blocksPerMeter = EarthProjection.heightScaleCorrectionAtLatitude(definition.latitude()) / worldScale;
      this.anchorX = EarthProjection.longitudeToBlockX(definition.longitude(), worldScale);
      this.anchorZ = EarthProjection.latToBlockZ(definition.latitude(), worldScale);
      this.cos = Math.cos(Math.toRadians(definition.rotationDegrees()));
      this.sin = Math.sin(Math.toRadians(definition.rotationDegrees()));
      double lowX = Double.MAX_VALUE, highX = -Double.MAX_VALUE, lowZ = Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
      for (int corner = 0; corner < 4; corner++) {
         double x = (corner & 1) == 0 ? mesh.min(0) : mesh.max(0);
         double z = (corner & 2) == 0 ? mesh.min(2) : mesh.max(2);
         double dx = (x * this.cos + z * this.sin) * this.blocksPerMeter;
         double dz = (z * this.cos - x * this.sin) * this.blocksPerMeter;
         lowX = Math.min(lowX, dx);
         highX = Math.max(highX, dx);
         lowZ = Math.min(lowZ, dz);
         highZ = Math.max(highZ, dz);
      }
      this.minX = (int)Math.floor(this.anchorX + lowX) - 1;
      this.maxX = (int)Math.ceil(this.anchorX + highX) + 1;
      this.minZ = (int)Math.floor(this.anchorZ + lowZ) - 1;
      this.maxZ = (int)Math.ceil(this.anchorZ + highZ) + 1;
      this.surfaceSampler = surfaceSampler;
      this.elevationToY = elevationToY;
   }

   public String id() {
      return this.entry.definition().id();
   }

   LandmarkDefinition definition() {
      return this.entry.definition();
   }

   double meshHeightMeters() {
      return this.mesh.height();
   }

   /** Distance from the landmark's centre to the farthest corner of its base, in metres. */
   double meshRadiusMeters() {
      double x = Math.max(-this.mesh.min(0), this.mesh.max(0));
      double z = Math.max(-this.mesh.min(2), this.mesh.max(2));
      return Math.sqrt(x * x + z * z);
   }

   /** Height of the landmark in blocks at this scale. */
   public double heightBlocks() {
      return this.mesh.height() * this.blocksPerMeter;
   }

   /** Whether the landmark stands on the ground around it (its base follows the terrain), not at a fixed elevation. */
   public boolean standsOnTerrain() {
      return !this.entry.definition().seaLevelAnchor();
   }

   public boolean intersects(int minX, int minZ, int maxX, int maxZ) {
      return maxX >= this.minX && minX <= this.maxX && maxZ >= this.minZ && minZ <= this.maxZ;
   }

   public int minX() {
      return this.minX;
   }

   public int minZ() {
      return this.minZ;
   }

   public int maxX() {
      return this.maxX;
   }

   public int maxZ() {
      return this.maxZ;
   }

   /** Whether the centre of this block column lies within the landmark's base rectangle grown by {@code marginBlocks}. */
   public boolean insideBase(int worldX, int worldZ, double marginBlocks) {
      if (worldX < this.minX - marginBlocks || worldX > this.maxX + marginBlocks || worldZ < this.minZ - marginBlocks || worldZ > this.maxZ + marginBlocks) {
         return false;
      }
      double dx = worldX + 0.5 - this.anchorX;
      double dz = worldZ + 0.5 - this.anchorZ;
      double margin = marginBlocks / this.blocksPerMeter;
      double x = (dx * this.cos - dz * this.sin) / this.blocksPerMeter;
      double z = (dx * this.sin + dz * this.cos) / this.blocksPerMeter;
      return x >= this.mesh.min(0) - margin && x <= this.mesh.max(0) + margin && z >= this.mesh.min(2) - margin && z <= this.mesh.max(2) + margin;
   }

   /** Whether trees are kept off this column: it lies a few blocks or less from one of the landmark's columns. */
   public boolean suppressesTree(int worldX, int worldZ) {
      if (worldX < this.minX - LandmarkVolume.TREE_CLEARANCE || worldX > this.maxX + LandmarkVolume.TREE_CLEARANCE
         || worldZ < this.minZ - LandmarkVolume.TREE_CLEARANCE || worldZ > this.maxZ + LandmarkVolume.TREE_CLEARANCE) {
         return false;
      }
      LandmarkVolume built = this.volume();
      return built != null && built.nearColumn(worldX, worldZ);
   }

   /** The levelled ground under the landmark at this column, or {@link Integer#MIN_VALUE} where the ground is left alone. */
   public int flattenedSurface(int worldX, int worldZ) {
      if (!this.entry.definition().flattenGround() || !this.insideBase(worldX, worldZ, 0.0)) {
         return Integer.MIN_VALUE;
      }
      int base = this.baseY();
      return base == Integer.MIN_VALUE ? Integer.MIN_VALUE : base - 1;
   }

   /**
    * The lowest layer of the landmark: one above the median ground of its base, or, for a sea-level anchored landmark,
    * one above where the world puts ground at the base's real elevation.
    */
   public int baseY() {
      int base = this.baseY;
      if (base == Integer.MIN_VALUE) {
         synchronized (this.baseLock) {
            base = this.baseY;
            if (base == Integer.MIN_VALUE) {
               base = this.entry.definition().seaLevelAnchor() ? this.baseY(this.elevationToY) : this.medianGroundY() + 1;
               this.baseY = base;
            }
         }
      }
      return base;
   }

   /**
    * The base of a sea-level anchored landmark under an elevation mapping (the world's, or a map preview's): the
    * world's own sea level, then the landmark's own scale down or up to its base. Only sea level comes from the
    * mapping, so ocean depth settings (which reshape the sea floor) cannot sink or lift the landmark.
    */
   public int baseY(TellusLandmarks.ElevationToY mapping) {
      return mapping.surfaceY(0.0, this.anchorZ) + 1 + (int)Math.round(this.entry.definition().baseElevationMeters() * this.blocksPerMeter);
   }

   private int medianGroundY() {
      int samplesPerSide = 9;
      int[] samples = new int[samplesPerSide * samplesPerSide];
      int count = 0;
      for (int a = 0; a < samplesPerSide; a++) {
         for (int b = 0; b < samplesPerSide; b++) {
            double x = this.mesh.min(0) + (this.mesh.max(0) - this.mesh.min(0)) * (a + 0.5) / samplesPerSide;
            double z = this.mesh.min(2) + (this.mesh.max(2) - this.mesh.min(2)) * (b + 0.5) / samplesPerSide;
            int worldX = (int)Math.floor(this.anchorX + (x * this.cos + z * this.sin) * this.blocksPerMeter);
            int worldZ = (int)Math.floor(this.anchorZ + (z * this.cos - x * this.sin) * this.blocksPerMeter);
            samples[count++] = this.surfaceSampler.applyAsInt(worldX, worldZ);
         }
      }
      Arrays.sort(samples, 0, count);
      return samples[count / 2];
   }

   /** The landmark's blocks, worked out on first use; null if that failed. */
   public LandmarkVolume volume() {
      LandmarkVolume built = this.volume;
      if (built == null && !this.failed) {
         synchronized (this) {
            built = this.volume;
            if (built == null && !this.failed) {
               try {
                  long started = System.nanoTime();
                  built = LandmarkVolume.voxelize(this.mesh, this.entry.definition(), this.anchorX, this.anchorZ, this.blocksPerMeter);
                  this.volume = built;
                  Tellus.LOGGER.info(
                     "Tellus landmark {} at 1:{}: {} blocks tall, {} spans, built in {} ms",
                     this.id(),
                     String.format(Locale.ROOT, "%.2f", this.worldScale),
                     built.height(),
                     built.spanCount(),
                     (System.nanoTime() - started) / 1_000_000L
                  );
               } catch (RuntimeException | OutOfMemoryError error) {
                  this.failed = true;
                  Tellus.LOGGER.warn("Failed to build Tellus landmark {}", this.id(), error);
               }
            }
         }
      }
      return built;
   }

   /** The block of a span material: the mesh's own palette block, or the definition's height band. */
   public BlockState materialState(int material) {
      BlockState[] states = this.materialStates;
      if (states == null) {
         String[] blocks = this.mesh.hasBlocks() ? this.mesh.blocks() : this.entry.definition().bandBlocks();
         states = new BlockState[blocks.length];
         for (int i = 0; i < blocks.length; i++) {
            states[i] = MinecraftRelease.block(blocks[i]).defaultBlockState();
         }
         this.materialStates = states;
      }
      return states[Math.max(0, Math.min(states.length - 1, material))];
   }

   /** The highest layer of the landmark in a square of columns, or -1 where it has none; for map previews. */
   public int highestLayer(int minX, int minZ, int size) {
      LandmarkVolume built = this.volume();
      if (built == null || !this.intersects(minX, minZ, minX + size - 1, minZ + size - 1)) {
         return -1;
      }
      int highest = -1;
      for (int z = Math.max(minZ, built.minZ()), lastZ = Math.min(minZ + size - 1, built.maxZ()); z <= lastZ; z++) {
         for (int x = Math.max(minX, built.minX()), lastX = Math.min(minX + size - 1, built.maxX()); x <= lastX; x++) {
            int column = built.column(x, z);
            if (column < 0) {
               continue;
            }
            int end = built.endSpan(column);
            if (end > built.firstSpan(column)) {
               highest = Math.max(highest, built.spanTop(end - 1));
            }
         }
      }
      return highest;
   }

   /** Receives one landmark block run: every block from {@code bottomY} to {@code topY} inclusive. */
   @FunctionalInterface
   public interface SpanSink {
      void accept(int bottomY, int topY, BlockState state);
   }

   /** The landmark's runs in one column, clipped to [minY, maxY]. */
   public void columnSpans(int worldX, int worldZ, int minY, int maxY, SpanSink sink) {
      LandmarkVolume built = this.volume();
      if (built == null) {
         return;
      }
      int column = built.column(worldX, worldZ);
      if (column < 0) {
         return;
      }
      int base = this.baseY();
      for (int span = built.firstSpan(column), end = built.endSpan(column); span < end; span++) {
         int bottom = Math.max(minY, base + built.spanBottom(span));
         int top = Math.min(maxY, base + built.spanTop(span));
         if (bottom <= top) {
            sink.accept(bottom, top, this.materialState(built.spanMaterial(span)));
         }
      }
   }

   /**
    * Whether a sparse layer of a cell belongs to a slanted line (a main cable, a diagonal brace): a few layers above and
    * below it, the edges of the cell's blocks have moved sideways, at least a block per four layers. Vertical lines
    * (suspenders) do not move, so a sparse row of them still fades out instead of filling in as a wall.
    */
   private static boolean slantedLine(int[] extent, int layer, int lowLayer, int highLayer, int cellSize) {
      int reach = Math.min(4, Math.max(1, cellSize / 2));
      int below = layer;
      int above = layer;
      for (int l = Math.max(lowLayer, layer - reach); l < layer; l++) {
         if (extent[l * 4] <= extent[l * 4 + 1]) {
            below = l;
            break;
         }
      }
      for (int l = Math.min(highLayer, layer + reach); l > layer; l--) {
         if (extent[l * 4] <= extent[l * 4 + 1]) {
            above = l;
            break;
         }
      }
      if (above == below) {
         return false;
      }
      int drift = 0;
      for (int edge = 0; edge < 4; edge++) {
         drift = Math.max(drift, Math.abs(extent[above * 4 + edge] - extent[below * 4 + edge]));
      }
      return drift >= Math.max(1, (above - below) / 4);
   }

   /** Per-thread scratch for {@link #lodCellSpans}: per layer, coverage changes, material votes and the blocks' extent. */
   private static final ThreadLocal<int[][]> LOD_SCRATCH = ThreadLocal.withInitial(() -> new int[][]{new int[0], new int[0], new int[0]});

   /**
    * The landmark's runs for one LOD cell of {@code cellSize} blocks starting at ({@code cellMinX}, {@code cellMinZ}).
    * A layer wider than a cell is drawn where it fills at least a cell-wide line of the cell's columns, so walls and
    * lattices keep their outline at every LOD size. A layer narrower than a cell is drawn by the one cell holding its
    * centre, so spires stay one cell wide instead of doubling where they cross a cell border. Thinner layers still
    * show where they are part of a slanted line, so cables keep their curve. Each layer takes the material most of its
    * blocks in the cell have.
    */
   public void lodCellSpans(int cellMinX, int cellMinZ, int cellSize, int minY, int maxY, SpanSink sink) {
      if (cellSize <= 1) {
         this.columnSpans(cellMinX, cellMinZ, minY, maxY, sink);
         return;
      }
      int cellMaxX = cellMinX + cellSize - 1;
      int cellMaxZ = cellMinZ + cellSize - 1;
      if (!this.intersects(cellMinX, cellMinZ, cellMaxX, cellMaxZ)) {
         return;
      }
      LandmarkVolume built = this.volume();
      if (built == null) {
         return;
      }
      int height = built.height();
      int materials = this.mesh.hasBlocks() ? this.mesh.blocks().length : this.entry.definition().bandBlocks().length;
      int[][] scratch = LOD_SCRATCH.get();
      if (scratch[0].length < height + 1) {
         scratch[0] = new int[height + 1];
      }
      if (scratch[1].length < (height + 1) * materials) {
         scratch[1] = new int[(height + 1) * materials];
      }
      if (scratch[2].length < (height + 1) * 4) {
         scratch[2] = new int[(height + 1) * 4];
      }
      int[] counts = scratch[0];
      int[] votes = scratch[1];
      int[] extent = scratch[2];
      int fromZ = Math.max(cellMinZ, built.minZ()), toZ = Math.min(cellMaxZ, built.maxZ());
      int fromX = Math.max(cellMinX, built.minX()), toX = Math.min(cellMaxX, built.maxX());
      // First the layers the cell's runs reach, then (with those cleared) coverage changes and material votes.
      int lowLayer = Integer.MAX_VALUE;
      int highLayer = -1;
      for (int z = fromZ; z <= toZ; z++) {
         for (int x = fromX; x <= toX; x++) {
            int column = built.column(x, z);
            if (column >= 0) {
               for (int span = built.firstSpan(column), end = built.endSpan(column); span < end; span++) {
                  lowLayer = Math.min(lowLayer, built.spanBottom(span));
                  highLayer = Math.max(highLayer, built.spanTop(span));
               }
            }
         }
      }
      if (highLayer < 0) {
         return;
      }
      Arrays.fill(counts, lowLayer, highLayer + 2, 0);
      Arrays.fill(votes, lowLayer * materials, (highLayer + 1) * materials, 0);
      for (int layer = lowLayer; layer <= highLayer; layer++) {
         extent[layer * 4] = Integer.MAX_VALUE;
         extent[layer * 4 + 1] = Integer.MIN_VALUE;
         extent[layer * 4 + 2] = Integer.MAX_VALUE;
         extent[layer * 4 + 3] = Integer.MIN_VALUE;
      }
      for (int z = fromZ; z <= toZ; z++) {
         for (int x = fromX; x <= toX; x++) {
            int column = built.column(x, z);
            if (column < 0) {
               continue;
            }
            for (int span = built.firstSpan(column), end = built.endSpan(column); span < end; span++) {
               int bottom = built.spanBottom(span);
               int top = built.spanTop(span);
               counts[bottom]++;
               counts[top + 1]--;
               int material = built.spanMaterial(span);
               for (int layer = bottom; layer <= top; layer++) {
                  votes[layer * materials + material]++;
                  extent[layer * 4] = Math.min(extent[layer * 4], x);
                  extent[layer * 4 + 1] = Math.max(extent[layer * 4 + 1], x);
                  extent[layer * 4 + 2] = Math.min(extent[layer * 4 + 2], z);
                  extent[layer * 4 + 3] = Math.max(extent[layer * 4 + 3], z);
               }
            }
         }
      }
      int base = this.baseY();
      int runStart = -1;
      int runMaterial = -1;
      int covered = 0;
      for (int layer = lowLayer; layer <= highLayer + 1; layer++) {
         covered += layer <= highLayer ? counts[layer] : 0;
         boolean solid;
         if (layer > highLayer || covered <= 0) {
            solid = false;
         } else if (built.layerExtent(layer) <= cellSize) {
            // Narrower than a cell (a spire): one cell, the one holding the layer's centre, draws it.
            int centerX = built.layerCenterX(layer);
            int centerZ = built.layerCenterZ(layer);
            solid = centerX >= cellMinX && centerX <= cellMaxX && centerZ >= cellMinZ && centerZ <= cellMaxZ;
         } else {
            solid = covered >= cellSize || slantedLine(extent, layer, lowLayer, highLayer, cellSize);
         }
         int material = -1;
         if (solid) {
            int best = -1;
            for (int m = 0; m < materials; m++) {
               int vote = votes[layer * materials + m];
               if (vote > best) {
                  best = vote;
                  material = m;
               }
            }
         }
         if (runStart >= 0 && (!solid || material != runMaterial)) {
            int bottom = Math.max(minY, base + runStart);
            int top = Math.min(maxY, base + layer - 1);
            if (bottom <= top) {
               sink.accept(bottom, top, this.materialState(runMaterial));
            }
            runStart = -1;
         }
         if (solid && runStart < 0) {
            runStart = layer;
            runMaterial = material;
         }
      }
   }
}
