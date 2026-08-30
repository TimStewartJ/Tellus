package com.yucareux.tellus.world.data;

/**
 * Selects discrete categories from bilinear source weights using a coherent,
 * deterministic spatial field.
 */
public final class CategoricalTransition {
   private static final ThreadLocal<BlendScratch> BLEND_SCRATCH = ThreadLocal.withInitial(BlendScratch::new);

   private CategoricalTransition() {
   }

   public static int selectBilinear(
      int fallbackValue,
      int value00,
      int value10,
      int value01,
      int value11,
      double fractionX,
      double fractionZ,
      double transitionStrength,
      double blockX,
      double blockZ,
      double sourceCellBlocks,
      int minimumValue,
      int maximumValue,
      NoiseProfile profile
   ) {
      if (!(transitionStrength > 0.0)) {
         return fallbackValue;
      }
      if (minimumValue > maximumValue) {
         throw new IllegalArgumentException("Minimum category must not exceed maximum category");
      }

      double fx = clamp(fractionX, 0.0, 1.0);
      double fz = clamp(fractionZ, 0.0, 1.0);
      double strength = clamp(transitionStrength, 0.0, 1.0);
      double inverseX = 1.0 - fx;
      double inverseZ = 1.0 - fz;
      BlendScratch scratch = BLEND_SCRATCH.get();
      scratch.reset();
      scratch.add(fallbackValue, 1.0 - strength, minimumValue, maximumValue);
      scratch.add(value00, inverseX * inverseZ * strength, minimumValue, maximumValue);
      scratch.add(value10, fx * inverseZ * strength, minimumValue, maximumValue);
      scratch.add(value01, inverseX * fz * strength, minimumValue, maximumValue);
      scratch.add(value11, fx * fz * strength, minimumValue, maximumValue);
      return scratch.pick(fallbackValue, transitionThreshold(blockX, blockZ, sourceCellBlocks, profile));
   }

   static double transitionThreshold(
      double blockX, double blockZ, double sourceCellBlocks, NoiseProfile profile
   ) {
      double patchBlocks = profile.patchBlocks(sourceCellBlocks);
      double coarse = valueNoise(blockX / patchBlocks, blockZ / patchBlocks, profile.coarseNoiseSeed());
      double detail = valueNoise(
         blockX / (patchBlocks * profile.detailScale()) + 31.75,
         blockZ / (patchBlocks * profile.detailScale()) - 19.25,
         profile.detailNoiseSeed()
      );
      double field = clamp(
         profile.coarseWeight() * coarse + profile.detailWeight() * detail,
         0.0,
         1.0
      );
      field = clamp(0.5 + (field - 0.5) * profile.contrast(), 0.0, 1.0);
      field = field * field * (3.0 - 2.0 * field);
      double edgeDetail = hashToUnit(
         floorToLong(blockX), floorToLong(blockZ), profile.edgeDetailSeed()
      ) - 0.5;
      return clamp(
         field + edgeDetail * profile.edgeDetailStrength(),
         0.0,
         Math.nextDown(1.0)
      );
   }

   private static double valueNoise(double x, double z, long seed) {
      long x0 = floorToLong(x);
      long z0 = floorToLong(z);
      double fractionX = x - x0;
      double fractionZ = z - z0;
      double value00 = hashToUnit(x0, z0, seed);
      double value10 = hashToUnit(x0 + 1L, z0, seed);
      double value01 = hashToUnit(x0, z0 + 1L, seed);
      double value11 = hashToUnit(x0 + 1L, z0 + 1L, seed);
      double smoothX = smootherStep(fractionX);
      double smoothZ = smootherStep(fractionZ);
      double north = value00 + (value10 - value00) * smoothX;
      double south = value01 + (value11 - value01) * smoothX;
      return north + (south - north) * smoothZ;
   }

   private static double smootherStep(double value) {
      double clamped = clamp(value, 0.0, 1.0);
      return clamped * clamped * clamped * (clamped * (clamped * 6.0 - 15.0) + 10.0);
   }

   private static double hashToUnit(long x, long z, long seed) {
      long hash = seed ^ x * -7046029254386353131L;
      hash ^= z * -4417276706812531889L;
      hash = mix64(hash);
      return (hash >>> 11) * 0x1.0p-53;
   }

   private static long mix64(long value) {
      long mixed = (value ^ value >>> 33) * -49064778989728563L;
      mixed = (mixed ^ mixed >>> 33) * -4265267296055464877L;
      return mixed ^ mixed >>> 33;
   }

   private static long floorToLong(double value) {
      if (value <= Long.MIN_VALUE) {
         return Long.MIN_VALUE;
      }
      if (value >= Long.MAX_VALUE) {
         return Long.MAX_VALUE;
      }
      return (long)Math.floor(value);
   }

   private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   public record NoiseProfile(
      double relativePatchScale,
      double minimumPatchBlocks,
      double maximumPatchBlocks,
      double detailScale,
      double coarseWeight,
      double detailWeight,
      double contrast,
      double edgeDetailStrength,
      long coarseNoiseSeed,
      long detailNoiseSeed,
      long edgeDetailSeed
   ) {
      public NoiseProfile {
         requirePositiveFinite(relativePatchScale, "relativePatchScale");
         requirePositiveFinite(minimumPatchBlocks, "minimumPatchBlocks");
         requirePositiveFinite(maximumPatchBlocks, "maximumPatchBlocks");
         requirePositiveFinite(detailScale, "detailScale");
         requirePositiveFinite(contrast, "contrast");
         if (maximumPatchBlocks < minimumPatchBlocks) {
            throw new IllegalArgumentException("Maximum patch size must not be smaller than minimum patch size");
         }
         if (!(Double.isFinite(coarseWeight) && coarseWeight >= 0.0)
            || !(Double.isFinite(detailWeight) && detailWeight >= 0.0)
            || !(coarseWeight + detailWeight > 0.0)) {
            throw new IllegalArgumentException("Noise weights must be finite, non-negative, and non-zero");
         }
         if (!(Double.isFinite(edgeDetailStrength) && edgeDetailStrength >= 0.0)) {
            throw new IllegalArgumentException("Edge detail strength must be finite and non-negative");
         }
      }

      public double patchBlocks(double sourceCellBlocks) {
         double cellBlocks = Double.isFinite(sourceCellBlocks) && sourceCellBlocks > 0.0
            ? sourceCellBlocks
            : 1.0;
         return clamp(
            cellBlocks * this.relativePatchScale,
            this.minimumPatchBlocks,
            this.maximumPatchBlocks
         );
      }

      private static void requirePositiveFinite(double value, String name) {
         if (!(Double.isFinite(value) && value > 0.0)) {
            throw new IllegalArgumentException(name + " must be finite and positive");
         }
      }
   }

   private static final class BlendScratch {
      private final int[] values = new int[5];
      private final double[] weights = new double[5];
      private int usedCount;

      private void reset() {
         this.usedCount = 0;
      }

      private void add(int value, double weight, int minimumValue, int maximumValue) {
         if (!(weight > 0.0) || value < minimumValue || value > maximumValue) {
            return;
         }
         for (int index = 0; index < this.usedCount; index++) {
            if (this.values[index] == value) {
               this.weights[index] += weight;
               return;
            }
         }
         this.values[this.usedCount] = value;
         this.weights[this.usedCount] = weight;
         this.usedCount++;
      }

      private int pick(int fallbackValue, double threshold) {
         if (this.usedCount == 0) {
            return fallbackValue;
         }
         double total = 0.0;
         for (int index = 0; index < this.usedCount; index++) {
            total += this.weights[index];
         }
         if (!(total > 0.0)) {
            return fallbackValue;
         }

         double target = clamp(threshold, 0.0, Math.nextDown(1.0)) * total;
         double cumulative = 0.0;
         int selected = fallbackValue;
         for (int index = 0; index < this.usedCount; index++) {
            selected = this.values[index];
            cumulative += this.weights[index];
            if (target < cumulative) {
               break;
            }
         }
         return selected;
      }
   }
}
