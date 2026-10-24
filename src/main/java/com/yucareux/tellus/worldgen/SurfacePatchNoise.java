package com.yucareux.tellus.worldgen;

import java.util.Arrays;

/**
 * Clustered, coordinate-stable selection for natural surface mixes.
 *
 * <p>A per-block hash turns a 65/35 material mix into salt-and-pepper speckle,
 * and because every Distant Horizons detail level samples different columns,
 * that speckle reshuffles whenever a LOD refines. Three octaves of value noise
 * (96, 23 and 7 blocks) form patches with ragged edges instead, so neighbouring
 * columns, real chunks and coarser LOD samples agree. The field is mapped
 * through its own distribution: asking for {@code p} percent still covers
 * {@code p} percent of the ground.</p>
 */
public final class SurfacePatchNoise {
   private static final int BROAD_CELL_BLOCKS = 96;
   private static final int PATCH_CELL_BLOCKS = 23;
   private static final int EDGE_CELL_BLOCKS = 7;
   private static final double BROAD_WEIGHT = 0.5;
   private static final double PATCH_WEIGHT = 0.33;
   private static final double EDGE_WEIGHT = 0.17;
   private static final long PATCH_OCTAVE_SALT = -7493989779944505343L;
   private static final long EDGE_OCTAVE_SALT = 4354685564936845355L;
   private static final int QUANTILE_STEPS = 1024;
   private static final int UNIFORM_TABLE_STEPS = 4096;
   private static final int DISTRIBUTION_SAMPLES_PER_AXIS = 384;
   private static final long DISTRIBUTION_SALT = 6364136223846793005L;
   private static final double MAX_UNIFORM = Math.nextDown(1.0);
   private static final double[] QUANTILES = buildQuantiles();
   private static final double TABLE_MIN = QUANTILES[0];
   private static final double TABLE_SCALE = UNIFORM_TABLE_STEPS / (QUANTILES[QUANTILE_STEPS] - QUANTILES[0]);
   // The quantile inversion sampled on an even grid of field values, for a constant-time lookup.
   private static final double[] UNIFORM_BY_FIELD = buildUniformTable();

   private SurfacePatchNoise() {
   }

   /** Uniformly distributed, spatially clustered value in [0, 1). */
   public static double uniform(int worldX, int worldZ, long salt) {
      double position = (field(worldX, worldZ, salt) - TABLE_MIN) * TABLE_SCALE;
      if (!(position > 0.0)) {
         return 0.0;
      } else if (!(position < UNIFORM_TABLE_STEPS)) {
         return MAX_UNIFORM;
      }

      int index = (int)position;
      double fraction = position - index;
      double lower = UNIFORM_BY_FIELD[index];
      return Math.min(MAX_UNIFORM, lower + fraction * (UNIFORM_BY_FIELD[index + 1] - lower));
   }

   /** Clustered roll in [0, 99], a drop-in replacement for a per-block percentage roll. */
   public static int percent(int worldX, int worldZ, long salt) {
      return Math.min(99, (int)(uniform(worldX, worldZ, salt) * 100.0));
   }

   /** Selects {@code coveragePercent} of the ground as connected patches. */
   public static boolean covers(int worldX, int worldZ, long salt, double coveragePercent) {
      if (!(coveragePercent > 0.0)) {
         return false;
      } else if (coveragePercent >= 100.0) {
         return true;
      }

      return uniform(worldX, worldZ, salt) * 100.0 < coveragePercent;
   }

   static double field(int worldX, int worldZ, long salt) {
      return valueNoise(worldX, worldZ, BROAD_CELL_BLOCKS, salt) * BROAD_WEIGHT
         + valueNoise(worldX + 37, worldZ - 53, PATCH_CELL_BLOCKS, salt ^ PATCH_OCTAVE_SALT) * PATCH_WEIGHT
         + valueNoise(worldX - 71, worldZ + 19, EDGE_CELL_BLOCKS, salt ^ EDGE_OCTAVE_SALT) * EDGE_WEIGHT;
   }

   private static double toUniform(double value) {
      if (!(value > QUANTILES[0])) {
         return 0.0;
      } else if (!(value < QUANTILES[QUANTILE_STEPS])) {
         return MAX_UNIFORM;
      }

      int index = Arrays.binarySearch(QUANTILES, value);
      int upper = index >= 0 ? index : -index - 1;
      int lower = Math.max(0, upper - 1);
      double lowerValue = QUANTILES[lower];
      double upperValue = QUANTILES[upper];
      double fraction = upperValue > lowerValue ? (value - lowerValue) / (upperValue - lowerValue) : 0.0;
      return Math.min(MAX_UNIFORM, (lower + fraction) / QUANTILE_STEPS);
   }

   private static double[] buildQuantiles() {
      // The field's distribution does not depend on the salt, so one sampled
      // region gives the quantiles for every caller.
      int axis = DISTRIBUTION_SAMPLES_PER_AXIS;
      double[] samples = new double[axis * axis];
      for (int z = 0; z < axis; z++) {
         for (int x = 0; x < axis; x++) {
            samples[z * axis + x] = field(x * 11 + 5, z * 13 + 3, DISTRIBUTION_SALT);
         }
      }

      Arrays.sort(samples);
      double[] quantiles = new double[QUANTILE_STEPS + 1];
      for (int i = 0; i <= QUANTILE_STEPS; i++) {
         quantiles[i] = samples[(int)((long)i * (samples.length - 1) / QUANTILE_STEPS)];
      }
      return quantiles;
   }

   private static double[] buildUniformTable() {
      double[] table = new double[UNIFORM_TABLE_STEPS + 1];
      for (int i = 0; i <= UNIFORM_TABLE_STEPS; i++) {
         table[i] = toUniform(TABLE_MIN + i / TABLE_SCALE);
      }
      return table;
   }

   private static double valueNoise(int worldX, int worldZ, int cellSize, long salt) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      double fractionX = fade((double)Math.floorMod(worldX, cellSize) / (double)cellSize);
      double fractionZ = fade((double)Math.floorMod(worldZ, cellSize) / (double)cellSize);
      double northWest = cellValue(cellX, cellZ, salt);
      double northEast = cellValue(cellX + 1, cellZ, salt);
      double southWest = cellValue(cellX, cellZ + 1, salt);
      double southEast = cellValue(cellX + 1, cellZ + 1, salt);
      double north = northWest + fractionX * (northEast - northWest);
      double south = southWest + fractionX * (southEast - southWest);
      return north + fractionZ * (south - north);
   }

   private static double cellValue(int cellX, int cellZ, long salt) {
      long mixed = mix64(salt ^ (long)cellX * 341873128712L ^ (long)cellZ * 132897987541L);
      return (double)(mixed >>> 11) * 0x1.0p-53;
   }

   private static double fade(double value) {
      return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
   }

   private static long mix64(long value) {
      value ^= value >>> 33;
      value *= -49064778989728563L;
      value ^= value >>> 33;
      value *= -4265267296055464877L;
      return value ^ value >>> 33;
   }
}
