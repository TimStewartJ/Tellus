package com.yucareux.tellus.worldgen;

/**
 * Regional context for badlands, shared by full chunks and Distant Horizons LODs.
 *
 * <p>Badlands paint the ground red, so they must read as landscapes, not pixels. Two fields keep
 * them coherent:</p>
 * <ul>
 *   <li>{@link #reliefMeters}: the canyon relief that promotes arid scrub to badlands. It was one
 *       value per square cell (800 m), so whole cells flipped at once and drew straight edges, 27
 *       blocks apart at 1:30. Values are now measured at cell centres and interpolated, and
 *       {@link #isCanyonRelief} compares them with a clustered, jittered threshold.</li>
 *   <li>{@link #support}: the share of dry land cover (shrub, grass, bare ground, lichen) and of bare
 *       ground alone in a few hundred metres around a point. Land-cover tables turn a bare pixel in
 *       an arid climate into badlands wherever it is, so vacant lots in a town, clearings in a forest
 *       and bare spots in steppe became red squares. {@link #keepsBadlands} requires most of the
 *       surroundings to be of the kind of land that is badlands there, and {@link Support} names the
 *       land cover the surroundings are made of instead.</li>
 * </ul>
 * Both are sampled on fixed lattices in block space with per-thread caches, so neighbouring columns,
 * chunks and fine LODs read the same nodes.
 */
public final class BadlandsRegionField {
   /** Land-cover class at a column, or {@link Integer#MIN_VALUE} when it is unavailable. */
   @FunctionalInterface
   public interface CoverSampler {
      int sample(int blockX, int blockZ, double resolutionMeters);
   }

   /** Elevation in metres at a column, or NaN when it is unavailable. */
   @FunctionalInterface
   public interface ElevationSampler {
      double sample(int blockX, int blockZ, double resolutionMeters);
   }

   /**
    * Land cover around a point. Shares are of the sampled land in [0, 1], or NaN without land samples;
    * classes are the most common other land cover, or -1 if there is none.
    *
    * @param dryShare shrub, grass, bare ground and lichen ({@link BadlandsTerrainPolicy#isDryCanyonCover})
    * @param bareShare bare ground and lichen alone
    * @param nonDryCoverClass most common cover that is not dry
    * @param nonBareCoverClass most common cover that is not bare
    */
   public record Support(double dryShare, double bareShare, int nonDryCoverClass, int nonBareCoverClass) {
      static final Support UNKNOWN = new Support(Double.NaN, Double.NaN, -1, -1);
   }

   static final double SUPPORT_SPACING_METERS = 80.0;
   static final double SUPPORT_SAMPLE_STEP_METERS = 60.0;
   static final int SUPPORT_SAMPLE_RADIUS = 2;
   static final double KEEP_SHARE = 0.5;
   static final double KEEP_SHARE_JITTER = 0.12;
   static final double RELIEF_JITTER = 0.2;
   private static final long KEEP_SALT = 0x5A17B0C3D2E9F481L;
   private static final long RELIEF_SALT = -0x3C9E5B7A1F2D8461L;
   private static final int NO_CLASS = -1;
   private static final int ESA_BARE = 60;
   private static final int ESA_MOSS_LICHEN = 100;

   private final int supportSpacing;
   private final int sampleStep;
   private final int reliefCell;
   private final int reliefOffset;
   private final ThreadLocal<NodeCache> supportNodes = ThreadLocal.withInitial(() -> new NodeCache(8));
   private final ThreadLocal<NodeCache> reliefNodes = ThreadLocal.withInitial(() -> new NodeCache(5));

   public BadlandsRegionField(double worldScale) {
      double scale = Double.isFinite(worldScale) && worldScale > 0.0 ? worldScale : 1.0;
      this.supportSpacing = Math.max(4, (int)Math.round(SUPPORT_SPACING_METERS / scale));
      this.sampleStep = Math.max(1, (int)Math.round(SUPPORT_SAMPLE_STEP_METERS / scale));
      this.reliefCell = BadlandsTerrainPolicy.regionalSampleCellBlocks(scale);
      this.reliefOffset = Math.max(4, (int)Math.ceil(BadlandsTerrainPolicy.CANYON_RELIEF_SAMPLE_METERS / scale));
   }

   static boolean isBareCover(int coverClass) {
      return coverClass == ESA_BARE || coverClass == ESA_MOSS_LICHEN;
   }

   /**
    * Land cover around a column. {@code columnBlocks} is the width the column stands for: LOD columns
    * at least one lattice step wide read their own position instead of interpolating.
    */
   public Support support(int blockX, int blockZ, double resolutionMeters, double columnBlocks, CoverSampler sampler) {
      if (columnBlocks >= this.supportSpacing) {
         return this.measureSupport(blockX, blockZ, resolutionMeters, sampler);
      }

      int spacing = this.supportSpacing;
      int nodeX = Math.floorDiv(blockX, spacing);
      int nodeZ = Math.floorDiv(blockZ, spacing);
      double fx = (blockX - (double)nodeX * spacing) / spacing;
      double fz = (blockZ - (double)nodeZ * spacing) / spacing;
      NodeCache cache = this.supportNodes.get();
      double dry = 0.0;
      double bare = 0.0;
      double weight = 0.0;
      double strongestNonDry = -1.0;
      double strongestNonBare = -1.0;
      int nonDry = NO_CLASS;
      int nonBare = NO_CLASS;
      for (int corner = 0; corner < 4; corner++) {
         int cx = nodeX + (corner & 1);
         int cz = nodeZ + (corner >> 1);
         double w = ((corner & 1) == 0 ? 1.0 - fx : fx) * ((corner >> 1) == 0 ? 1.0 - fz : fz);
         int slot = cache.find(cx, cz, resolutionMeters);
         if (slot < 0) {
            Support node = this.measureSupport(offset(cx, spacing), offset(cz, spacing), resolutionMeters, sampler);
            slot = cache.put(cx, cz, resolutionMeters, node.dryShare(), node.bareShare(), node.nonDryCoverClass(), node.nonBareCoverClass());
         }
         if (Double.isFinite(cache.values[slot]) && w > 0.0) {
            dry += cache.values[slot] * w;
            bare += cache.secondValues[slot] * w;
            weight += w;
         }
         if (cache.extras[slot] != NO_CLASS && w > strongestNonDry) {
            strongestNonDry = w;
            nonDry = cache.extras[slot];
         }
         if (cache.secondExtras[slot] != NO_CLASS && w > strongestNonBare) {
            strongestNonBare = w;
            nonBare = cache.secondExtras[slot];
         }
      }
      return weight > 0.0 ? new Support(dry / weight, bare / weight, nonDry, nonBare) : new Support(Double.NaN, Double.NaN, nonDry, nonBare);
   }

   /** Regional relief in metres around a column, smooth across the relief lattice. */
   public double reliefMeters(int blockX, int blockZ, double resolutionMeters, double columnBlocks, ElevationSampler sampler) {
      if (columnBlocks >= this.reliefCell) {
         return this.measureRelief(blockX, blockZ, resolutionMeters, sampler);
      }

      int cell = this.reliefCell;
      // Nodes sit at cell centres, where the former per-cell relief was measured.
      double gridX = (blockX - cell * 0.5) / cell;
      double gridZ = (blockZ - cell * 0.5) / cell;
      int nodeX = (int)Math.floor(gridX);
      int nodeZ = (int)Math.floor(gridZ);
      double fx = gridX - nodeX;
      double fz = gridZ - nodeZ;
      NodeCache cache = this.reliefNodes.get();
      double total = 0.0;
      double weight = 0.0;
      for (int corner = 0; corner < 4; corner++) {
         int cx = nodeX + (corner & 1);
         int cz = nodeZ + (corner >> 1);
         double w = ((corner & 1) == 0 ? 1.0 - fx : fx) * ((corner >> 1) == 0 ? 1.0 - fz : fz);
         int slot = cache.find(cx, cz, resolutionMeters);
         if (slot < 0) {
            double relief = this.measureRelief(cellCenter(cx, cell), cellCenter(cz, cell), resolutionMeters, sampler);
            slot = cache.put(cx, cz, resolutionMeters, relief, Double.NaN, NO_CLASS, NO_CLASS);
         }
         double relief = cache.values[slot];
         if (Double.isFinite(relief) && w > 0.0) {
            total += relief * w;
            weight += w;
         }
      }
      return weight > 0.0 ? total / weight : Double.NaN;
   }

   /**
    * Whether enough of a point's surroundings are badlands-like land, with a clustered, organic border.
    * An unknown share keeps the badlands the land-cover tables chose.
    */
   public static boolean keepsBadlands(double share, int blockX, int blockZ) {
      if (!Double.isFinite(share)) {
         return true;
      }
      double jitter = (SurfacePatchNoise.uniform(blockX, blockZ, KEEP_SALT) - 0.5) * 2.0 * KEEP_SHARE_JITTER;
      return share >= KEEP_SHARE + jitter;
   }

   /** Canyon relief test with a clustered threshold, so promoted badlands follow the relief organically. */
   public static boolean isCanyonRelief(double reliefMeters, int blockX, int blockZ) {
      if (!Double.isFinite(reliefMeters)) {
         return false;
      }
      double jitter = 1.0 + (SurfacePatchNoise.uniform(blockX, blockZ, RELIEF_SALT) - 0.5) * 2.0 * RELIEF_JITTER;
      return reliefMeters >= BadlandsTerrainPolicy.MIN_CANYON_RELIEF_METERS * jitter;
   }

   private Support measureSupport(int blockX, int blockZ, double resolutionMeters, CoverSampler sampler) {
      int side = 2 * SUPPORT_SAMPLE_RADIUS + 1;
      int[] classes = new int[side * side];
      int[] counts = new int[side * side];
      int distinct = 0;
      int land = 0;
      int dry = 0;
      int bare = 0;
      for (int dz = -SUPPORT_SAMPLE_RADIUS; dz <= SUPPORT_SAMPLE_RADIUS; dz++) {
         for (int dx = -SUPPORT_SAMPLE_RADIUS; dx <= SUPPORT_SAMPLE_RADIUS; dx++) {
            int coverClass = sampler.sample(
               clampToInt((long)blockX + (long)dx * this.sampleStep), clampToInt((long)blockZ + (long)dz * this.sampleStep), resolutionMeters
            );
            if (coverClass == Integer.MIN_VALUE || MountainSurfaceRules.isWaterLikeCoverClass(coverClass)) {
               continue;
            }
            land++;
            if (BadlandsTerrainPolicy.isDryCanyonCover(coverClass)) {
               dry++;
            }
            if (isBareCover(coverClass)) {
               bare++;
            }
            int index = 0;
            while (index < distinct && classes[index] != coverClass) {
               index++;
            }
            if (index == distinct) {
               classes[distinct++] = coverClass;
            }
            counts[index]++;
         }
      }
      if (land == 0) {
         return Support.UNKNOWN;
      }
      int nonDry = NO_CLASS;
      int nonBare = NO_CLASS;
      int bestNonDry = 0;
      int bestNonBare = 0;
      for (int index = 0; index < distinct; index++) {
         int coverClass = classes[index];
         if (!BadlandsTerrainPolicy.isDryCanyonCover(coverClass) && counts[index] > bestNonDry) {
            bestNonDry = counts[index];
            nonDry = coverClass;
         }
         if (!isBareCover(coverClass) && counts[index] > bestNonBare) {
            bestNonBare = counts[index];
            nonBare = coverClass;
         }
      }
      return new Support(dry / (double)land, bare / (double)land, nonDry, nonBare);
   }

   private double measureRelief(int blockX, int blockZ, double resolutionMeters, ElevationSampler sampler) {
      int offset = this.reliefOffset;
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      int samples = 0;
      for (int index = 0; index < 5; index++) {
         int x = index == 1 ? clampToInt((long)blockX + offset) : index == 2 ? clampToInt((long)blockX - offset) : blockX;
         int z = index == 3 ? clampToInt((long)blockZ + offset) : index == 4 ? clampToInt((long)blockZ - offset) : blockZ;
         double elevation = sampler.sample(x, z, resolutionMeters);
         if (Double.isFinite(elevation)) {
            min = Math.min(min, elevation);
            max = Math.max(max, elevation);
            samples++;
         }
      }
      return samples >= 3 ? max - min : Double.NaN;
   }

   private static int offset(int node, int spacing) {
      return clampToInt((long)node * spacing);
   }

   private static int cellCenter(int cell, int cellSize) {
      return clampToInt((long)cell * cellSize + cellSize / 2L);
   }

   private static int clampToInt(long value) {
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
   }

   /** Direct-mapped per-thread node cache; a miss only costs a recomputation. */
   private static final class NodeCache {
      private final int mask;
      private final long[] keys;
      private final double[] resolutions;
      private final boolean[] filled;
      final double[] values;
      final double[] secondValues;
      final int[] extras;
      final int[] secondExtras;

      NodeCache(int bits) {
         int size = 1 << bits;
         this.mask = size - 1;
         this.keys = new long[size];
         this.resolutions = new double[size];
         this.filled = new boolean[size];
         this.values = new double[size];
         this.secondValues = new double[size];
         this.extras = new int[size];
         this.secondExtras = new int[size];
      }

      int find(int nodeX, int nodeZ, double resolutionMeters) {
         long key = key(nodeX, nodeZ);
         int slot = this.slot(key);
         return this.filled[slot] && this.keys[slot] == key && Double.compare(this.resolutions[slot], resolutionMeters) == 0 ? slot : -1;
      }

      int put(int nodeX, int nodeZ, double resolutionMeters, double value, double secondValue, int extra, int secondExtra) {
         long key = key(nodeX, nodeZ);
         int slot = this.slot(key);
         this.keys[slot] = key;
         this.resolutions[slot] = resolutionMeters;
         this.values[slot] = value;
         this.secondValues[slot] = secondValue;
         this.extras[slot] = extra;
         this.secondExtras[slot] = secondExtra;
         this.filled[slot] = true;
         return slot;
      }

      private int slot(long key) {
         long mixed = key * -7046029254386353131L;
         return (int)(mixed ^ mixed >>> 29) & this.mask;
      }

      private static long key(int nodeX, int nodeZ) {
         return (long)nodeX << 32 | nodeZ & 0xFFFFFFFFL;
      }
   }
}
