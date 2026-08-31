package com.yucareux.tellus.worldgen.vegetation;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Registry-free deterministic planning for vegetation below the mature canopy.
 *
 * <p>Candidate positions ("anchors") are not laid out on a lattice. Every block column has a
 * deterministic priority hash per stratum, and a column is an anchor when its priority is the
 * strict maximum within the stratum's spacing radius. That guarantees a minimum distance between
 * plants of one stratum, has no periodic structure, and can be evaluated for any block without
 * knowing its neighbours' data. The expected anchor spacing equals the legacy lattice cell of the
 * stratum, so the tuned densities keep their meaning: a density of 1.0 still means one plant per
 * {@code cellSize x cellSize} blocks.
 *
 * <p>A low-frequency openness field carves connected corridors (game trails, understory gaps)
 * through the woody strata so dense forest stays traversable at ground level.
 */
public final class TellusVegetationPlanner {
   private static final long ANCHOR_SALT = 0x2C1B3C6D5E7F0911L;
   private static final long PRIORITY_SALT = 0x51E3A9C7B2D6F084L;
   private static final long REPRESENTATIVE_SALT = 0x6A1D4F7B9C2E8035L;
   private static final long OPENNESS_SALT = 0x3B9D7E1C5A2F8064L;
   private static final long PATCH_SALT = 0x7093A5C7E1B2D4F6L;
   private static final long STAND_SALT = 0x4D2F86A1B5C739E0L;
   private static final long VARIANT_SALT = 0x65B8E20D4A7193CFL;
   /**
    * Wavelength of the corridor network in blocks. Trails are a traversal affordance, so the field
    * is defined in block space and stays walkable at every world scale.
    */
   static final double OPENNESS_WAVELENGTH_BLOCKS = 46.0;
   /** Half-width of the corridor band in field units; see {@link #openness}. */
   static final double OPENNESS_BAND = 0.075;

   private TellusVegetationPlanner() {
   }

   /**
    * Anchors of {@code stratum} inside the inclusive block rectangle, in row-major order
    * ({@code z} outer, {@code x} inner).
    */
   public static List<Anchor> anchorsIn(
      Stratum stratum, int minX, int minZ, int maxX, int maxZ, long worldSeed
   ) {
      Objects.requireNonNull(stratum, "stratum");
      if (maxX < minX || maxZ < minZ) {
         return List.of();
      }
      List<Anchor> anchors = new ArrayList<>();
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            if (isAnchor(stratum, x, z, worldSeed)) {
               anchors.add(new Anchor(x, z, anchorSeed(stratum, x, z, worldSeed)));
            }
         }
      }
      return anchors;
   }

   /** The anchor at the given block column, or {@code null} when the column is not one. */
   public static Anchor anchorAt(Stratum stratum, int worldX, int worldZ, long worldSeed) {
      Objects.requireNonNull(stratum, "stratum");
      if (!isAnchor(stratum, worldX, worldZ, worldSeed)) {
         return null;
      }
      return new Anchor(worldX, worldZ, anchorSeed(stratum, worldX, worldZ, worldSeed));
   }

   /**
    * Whether the block column's priority is the strict maximum of the stratum's spacing
    * neighbourhood. Two anchors of one stratum are therefore never closer than the stratum's
    * spacing radius.
    */
   public static boolean isAnchor(Stratum stratum, int worldX, int worldZ, long worldSeed) {
      Objects.requireNonNull(stratum, "stratum");
      long own = priority(stratum, worldX, worldZ, worldSeed);
      for (int[] offset : stratum.spacingOffsets()) {
         long other = priority(stratum, worldX + offset[0], worldZ + offset[1], worldSeed);
         if (other > own || other == own && (offset[1] < 0 || offset[1] == 0 && offset[0] < 0)) {
            return false;
         }
      }
      return true;
   }

   /**
    * One deterministic anchor standing in for the inclusive block rectangle, for coarse level-of-detail
    * columns that render a single representative plant: the highest-priority anchor inside a window
    * of the stratum's representative size placed at a hashed position in the rectangle, or
    * {@code null} when that window holds no anchor.
    */
   public static Anchor representativeAnchor(
      Stratum stratum, int minX, int minZ, int maxX, int maxZ, long worldSeed
   ) {
      Objects.requireNonNull(stratum, "stratum");
      if (maxX < minX || maxZ < minZ) {
         return null;
      }
      int width = maxX - minX + 1;
      int depth = maxZ - minZ + 1;
      int spanX = Math.min(stratum.representativeWindow(), width);
      int spanZ = Math.min(stratum.representativeWindow(), depth);
      long selection = mix(
         worldSeed
            ^ REPRESENTATIVE_SALT
            ^ stratum.salt()
            ^ (long)minX * 0x632BE59BD9B4E019L
            ^ (long)minZ * 0x94D049BB133111EBL
            ^ (long)width * 0x9E3779B97F4A7C15L
      );
      int startX = minX + (int)Math.floorMod(selection, (long)(width - spanX + 1));
      int startZ = minZ + (int)Math.floorMod(selection >>> 32, (long)(depth - spanZ + 1));
      Anchor best = null;
      long bestPriority = Long.MIN_VALUE;
      for (int z = startZ; z < startZ + spanZ; z++) {
         for (int x = startX; x < startX + spanX; x++) {
            if (!isAnchor(stratum, x, z, worldSeed)) {
               continue;
            }
            long candidatePriority = priority(stratum, x, z, worldSeed);
            if (best == null || candidatePriority > bestPriority) {
               best = new Anchor(x, z, anchorSeed(stratum, x, z, worldSeed));
               bestPriority = candidatePriority;
            }
         }
      }
      return best;
   }

   private static long priority(Stratum stratum, int worldX, int worldZ, long worldSeed) {
      return mix(
         worldSeed
            ^ PRIORITY_SALT
            ^ stratum.salt()
            ^ (long)worldX * 0x632BE59BD9B4E019L
            ^ (long)worldZ * 0x94D049BB133111EBL
      );
   }

   private static long anchorSeed(Stratum stratum, int worldX, int worldZ, long worldSeed) {
      return mix(
         worldSeed
            ^ ANCHOR_SALT
            ^ stratum.salt()
            ^ (long)worldX * 0x632BE59BD9B4E019L
            ^ (long)worldZ * 0x94D049BB133111EBL
      );
   }

   /**
    * Openness of the understory at a block column, from 0 (undisturbed) to 1 (centre of a
    * corridor). Corridors are the band around the zero level set of a smooth two-octave field, which
    * makes them connected curves rather than isolated gaps.
    */
   public static double openness(int worldX, int worldZ, long spatialSeed) {
      double x = worldX / OPENNESS_WAVELENGTH_BLOCKS;
      double z = worldZ / OPENNESS_WAVELENGTH_BLOCKS;
      double broad = valueNoise(x, z, spatialSeed ^ OPENNESS_SALT) * 2.0 - 1.0;
      double detail = (valueNoise(x * 2.7 + 5.0, z * 2.7 - 3.0, spatialSeed ^ (OPENNESS_SALT >>> 1)) * 2.0 - 1.0)
         * 0.35;
      double distance = Math.abs(broad + detail);
      if (distance >= OPENNESS_BAND) {
         return 0.0;
      }
      return smoothstep(1.0 - distance / OPENNESS_BAND);
   }

   static double opennessSuppression(Stratum stratum) {
      return switch (stratum) {
         case SUBCANOPY -> 0.92;
         case SHRUB -> 0.96;
         case DEADWOOD -> 0.85;
         case GROUND -> 0.40;
         case HERB -> 0.30;
      };
   }

   public static Placement plan(Stratum stratum, Anchor anchor, Environment environment) {
      Objects.requireNonNull(stratum, "stratum");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(environment, "environment");
      if (!environment.placeable()
         || stratum == Stratum.SUBCANOPY && environment.lowCanopy()
         || !supports(stratum, environment.coverClass(), environment.community())) {
         return null;
      }

      double density = baseDensity(stratum, environment.coverClass(), environment.community());
      density = applyEnvironment(stratum, density, environment);
      density *= representationFactor(stratum, environment.worldScale());
      if (!(density > 0.0)) {
         return null;
      }

      StandState stand = standState(
         anchor.worldX(), anchor.worldZ(), environment.spatialSeed(), environment.worldScale()
      );
      density *= standFactor(stratum, stand);
      double patch = patchValue(
         anchor.worldX(),
         anchor.worldZ(),
         environment.spatialSeed()
            ^ stratum.salt()
            ^ environment.community().ordinal() * 0x9E3779B97F4A7C15L,
         environment.worldScale(),
         stratum.patchScaleMeters()
      );
      density *= 0.48 + patch * 0.92;
      density *= 1.0 - openness(anchor.worldX(), anchor.worldZ(), environment.spatialSeed())
         * opennessSuppression(stratum);
      double roll = unitHash(anchor.seed() ^ stratum.salt(), anchor.worldX(), anchor.worldZ());
      if (roll >= stratum.anchorProbability(density)) {
         return null;
      }

      long variantSeed = mix(anchor.seed() ^ VARIANT_SALT ^ environment.community().ordinal());
      int variant = (int)Math.floorMod(variantSeed, stratum.variantCount());
      int size = size(stratum, environment, variantSeed);
      return new Placement(
         stratum,
         environment.community(),
         environment.treeProfile(),
         stand,
         anchor.worldX(),
         anchor.worldZ(),
         environment.expectedSurface(),
         size,
         variant,
         variantSeed
      );
   }

   public static StandState standState(int worldX, int worldZ, long seed, double worldScale) {
      double value = patchValue(worldX, worldZ, seed ^ STAND_SALT, worldScale, 112.0);
      if (value < 0.13) {
         return StandState.OPEN;
      } else if (value < 0.31) {
         return StandState.REGENERATING;
      } else if (value > 0.88) {
         return StandState.OLD_GROWTH;
      } else {
         return StandState.MATURE;
      }
   }

   private static boolean supports(Stratum stratum, int coverClass, VegetationCommunity community) {
      if (community == VegetationCommunity.NONE) {
         return false;
      }
      return switch (stratum) {
         case SUBCANOPY -> community.wooded()
            || community == VegetationCommunity.WETLAND
            || community == VegetationCommunity.MALLEE
            || community == VegetationCommunity.SUBARCTIC_SCRUB;
         case SHRUB -> coverClass != MountainSurfaceRules.ESA_CROPLAND
            && coverClass != MountainSurfaceRules.ESA_GRASSLAND
            || community == VegetationCommunity.SAVANNA;
         case HERB, GROUND -> true;
         case DEADWOOD -> community.wooded()
            || community.shrubDominated()
            || community == VegetationCommunity.WETLAND;
      };
   }

   private static double baseDensity(
      Stratum stratum, int coverClass, VegetationCommunity community
   ) {
      if (coverClass == MountainSurfaceRules.ESA_SHRUBLAND) {
         return switch (stratum) {
            case SUBCANOPY -> 0.08;
            case SHRUB -> 0.84;
            case HERB -> 0.46;
            case GROUND -> 0.38;
            case DEADWOOD -> 0.035;
         };
      }
      if (coverClass == MountainSurfaceRules.ESA_GRASSLAND) {
         return switch (stratum) {
            case SUBCANOPY -> 0.015;
            case SHRUB -> community == VegetationCommunity.SAVANNA ? 0.16 : 0.04;
            case HERB -> 0.88;
            case GROUND -> 0.28;
            case DEADWOOD -> 0.0;
         };
      }
      if (coverClass == MountainSurfaceRules.ESA_CROPLAND) {
         return switch (stratum) {
            case HERB -> 0.14;
            case GROUND -> 0.08;
            default -> 0.0;
         };
      }
      if (coverClass == MountainSurfaceRules.ESA_WETLAND
         || coverClass == MountainSurfaceRules.ESA_MANGROVES) {
         return switch (stratum) {
            case SUBCANOPY -> 0.12;
            case SHRUB -> 0.62;
            case HERB -> 0.90;
            case GROUND -> 0.54;
            case DEADWOOD -> 0.045;
         };
      }
      if (coverClass == MountainSurfaceRules.ESA_MOSS_LICHEN) {
         return switch (stratum) {
            case HERB -> 0.22;
            case GROUND -> 0.74;
            default -> 0.0;
         };
      }

      return switch (community) {
         case TROPICAL_MOIST_FOREST -> switch (stratum) {
            case SUBCANOPY -> 0.58;
            case SHRUB -> 0.70;
            case HERB -> 0.62;
            case GROUND -> 0.66;
            case DEADWOOD -> 0.06;
         };
         case BOREAL_FOREST -> switch (stratum) {
            case SUBCANOPY -> 0.34;
            case SHRUB -> 0.46;
            case HERB -> 0.42;
            case GROUND -> 0.78;
            case DEADWOOD -> 0.07;
         };
         case EUCALYPT_WOODLAND, SAVANNA -> switch (stratum) {
            case SUBCANOPY -> 0.10;
            case SHRUB -> 0.28;
            case HERB -> 0.72;
            case GROUND -> 0.28;
            case DEADWOOD -> 0.025;
         };
         case TROPICAL_DRY_FOREST, PINE_OAK_FOREST -> switch (stratum) {
            case SUBCANOPY -> 0.26;
            case SHRUB -> 0.48;
            case HERB -> 0.56;
            case GROUND -> 0.34;
            case DEADWOOD -> 0.045;
         };
         default -> switch (stratum) {
            case SUBCANOPY -> 0.30;
            case SHRUB -> 0.52;
            case HERB -> 0.54;
            case GROUND -> 0.62;
            case DEADWOOD -> 0.055;
         };
      };
   }

   private static double applyEnvironment(Stratum stratum, double density, Environment environment) {
      double shade = clamp01(environment.canopyShade());
      double edge = clamp01(environment.edgeStrength());
      if (stratum == Stratum.SUBCANOPY) {
         density *= 0.58 + (1.0 - shade) * 0.78;
         density *= 1.0 + edge * 0.38;
      } else if (stratum == Stratum.SHRUB) {
         density *= 0.72 + (1.0 - shade) * 0.48;
         density *= 1.0 + edge * 0.55;
         if (environment.distanceToWater() >= 2 && environment.distanceToWater() <= 7) {
            density *= 1.30;
         }
      } else if (stratum == Stratum.HERB) {
         boolean shadeFriendly = environment.community() == VegetationCommunity.BOREAL_FOREST
            || environment.community() == VegetationCommunity.TROPICAL_MOIST_FOREST
            || environment.community() == VegetationCommunity.WETLAND;
         density *= shadeFriendly ? 0.82 + shade * 0.30 : 1.08 - shade * 0.58;
         if (environment.distanceToWater() >= 1 && environment.distanceToWater() <= 5) {
            density *= 1.22;
         }
      } else if (stratum == Stratum.GROUND) {
         density *= 0.78 + shade * 0.42;
      }

      if (environment.slope() >= 5) {
         density *= stratum == Stratum.GROUND ? 0.55 : 0.18;
      } else if (environment.slope() >= 3) {
         density *= 0.62;
      }
      if (environment.snowCovered()) {
         density *= stratum == Stratum.GROUND ? 0.30 : 0.08;
      }
      return density;
   }

   static double representationFactor(Stratum stratum, double worldScale) {
      double scale = Double.isFinite(worldScale) && worldScale > 0.0 ? worldScale : 1.0;
      if (scale <= 2.0) {
         return 1.0;
      }
      double divisor = Math.sqrt(scale / 2.0);
      return switch (stratum) {
         case HERB -> Math.max(0.18, 1.0 / divisor);
         case GROUND -> Math.max(0.24, 1.0 / divisor);
         case SHRUB -> Math.max(0.32, Math.min(1.0, 1.25 / divisor));
         case SUBCANOPY, DEADWOOD -> Math.max(0.45, Math.min(1.0, 1.4 / divisor));
      };
   }

   private static double standFactor(Stratum stratum, StandState stand) {
      return switch (stand) {
         case OPEN -> switch (stratum) {
            case SUBCANOPY -> 0.58;
            case SHRUB -> 1.38;
            case HERB -> 1.42;
            case GROUND -> 0.84;
            case DEADWOOD -> 0.72;
         };
         case REGENERATING -> switch (stratum) {
            case SUBCANOPY -> 1.48;
            case SHRUB -> 1.28;
            case HERB -> 1.16;
            case GROUND -> 0.92;
            case DEADWOOD -> 0.88;
         };
         case MATURE -> 1.0;
         case OLD_GROWTH -> switch (stratum) {
            case SUBCANOPY -> 0.82;
            case SHRUB -> 0.88;
            case HERB -> 0.76;
            case GROUND -> 1.24;
            case DEADWOOD -> 1.72;
         };
      };
   }

   private static int size(Stratum stratum, Environment environment, long seed) {
      int roll = (int)Math.floorMod(seed >>> 17, 100);
      return switch (stratum) {
         case SUBCANOPY -> {
            int canopyLimit = environment.canopyHeightMeters() > 0.0
               ? Math.max(5, Math.min(12, (int)Math.round(environment.canopyHeightMeters() * 0.42)))
               : 9;
            yield Math.max(4, Math.min(canopyLimit, 4 + roll % 9));
         }
         case SHRUB -> environment.community().shrubDominated()
            ? 2 + roll % 2
            : 1 + (roll < 28 ? 1 : 0);
         case HERB, GROUND -> 1;
         case DEADWOOD -> 3 + roll % 4;
      };
   }

   private static double patchValue(
      int worldX, int worldZ, long seed, double worldScale, double scaleMeters
   ) {
      double metersPerBlock = Double.isFinite(worldScale) && worldScale > 0.0 ? worldScale : 1.0;
      double x = worldX * metersPerBlock / scaleMeters;
      double z = worldZ * metersPerBlock / scaleMeters;
      double broad = valueNoise(x, z, seed ^ PATCH_SALT);
      double detail = valueNoise(x * 2.13 + 17.0, z * 2.13 - 11.0, seed ^ (PATCH_SALT >>> 1));
      return clamp01(broad * 0.72 + detail * 0.28);
   }

   private static double valueNoise(double x, double z, long seed) {
      int x0 = floorToInt(x);
      int z0 = floorToInt(z);
      double fx = smoothstep(x - x0);
      double fz = smoothstep(z - z0);
      double v00 = lattice(seed, x0, z0);
      double v10 = lattice(seed, x0 + 1, z0);
      double v01 = lattice(seed, x0, z0 + 1);
      double v11 = lattice(seed, x0 + 1, z0 + 1);
      double a = lerp(v00, v10, fx);
      double b = lerp(v01, v11, fx);
      return lerp(a, b, fz);
   }

   private static int floorToInt(double value) {
      if (value <= Integer.MIN_VALUE) {
         return Integer.MIN_VALUE;
      }
      if (value >= Integer.MAX_VALUE) {
         return Integer.MAX_VALUE;
      }
      return (int)Math.floor(value);
   }

   private static double lattice(long seed, int x, int z) {
      return unitHash(
         seed
            ^ (long)x * 0x632BE59BD9B4E019L
            ^ (long)z * 0x94D049BB133111EBL,
         x,
         z
      );
   }

   private static double unitHash(long seed, int x, int z) {
      long value = seed
         ^ (long)x * 0x9E3779B97F4A7C15L
         ^ (long)z * 0xD1B54A32D192ED03L;
      return (mix(value) >>> 11) * 0x1.0p-53;
   }

   private static long mix(long value) {
      value ^= value >>> 30;
      value *= 0xBF58476D1CE4E5B9L;
      value ^= value >>> 27;
      value *= 0x94D049BB133111EBL;
      return value ^ value >>> 31;
   }

   private static double smoothstep(double value) {
      double t = clamp01(value);
      return t * t * (3.0 - 2.0 * t);
   }

   private static double lerp(double start, double end, double progress) {
      return start + (end - start) * progress;
   }

   private static double clamp01(double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }

   public enum Stratum {
      SUBCANOPY(7, 16, 11, 48.0, 5, 0x13B579D2468ACE01L),
      SHRUB(4, 4, 7, 22.0, 8, 0x2C4E6081A3B5D7F9L),
      HERB(2, 1, 5, 12.0, 12, 0x35A7C9E1F2046B8DL),
      GROUND(4, 2, 7, 18.0, 10, 0x4A6C8E103254769BL),
      DEADWOOD(16, 78, 23, 72.0, 4, 0x5D7F91B3C5E7092FL);

      private final int cellSize;
      private final int spacingRadiusSquared;
      private final int representativeWindow;
      private final double patchScaleMeters;
      private final int variantCount;
      private final long salt;
      private final int[][] spacingOffsets;
      private final double anchorProbabilityScale;

      Stratum(
         int cellSize,
         int spacingRadiusSquared,
         int representativeWindow,
         double patchScaleMeters,
         int variantCount,
         long salt
      ) {
         this.cellSize = cellSize;
         this.spacingRadiusSquared = spacingRadiusSquared;
         this.representativeWindow = representativeWindow;
         this.patchScaleMeters = patchScaleMeters;
         this.variantCount = variantCount;
         this.salt = salt;
         this.spacingOffsets = spacingOffsets(spacingRadiusSquared);
         // One anchor per (offsets + 1) blocks on average; density is tuned per cellSize² blocks.
         this.anchorProbabilityScale = (double)(this.spacingOffsets.length + 1) / ((double)cellSize * cellSize);
      }

      private static int[][] spacingOffsets(int radiusSquared) {
         int radius = (int)Math.floor(Math.sqrt(radiusSquared));
         List<int[]> offsets = new ArrayList<>();
         for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
               int distanceSquared = dx * dx + dz * dz;
               if (distanceSquared != 0 && distanceSquared <= radiusSquared) {
                  offsets.add(new int[]{dx, dz});
               }
            }
         }
         // Nearer neighbours first: a rejection usually comes from a nearby higher priority.
         offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
         return offsets.toArray(new int[0][]);
      }

      /**
       * Nominal spacing of the stratum in blocks. Densities are expressed as the probability of one
       * plant per {@code cellSize x cellSize} blocks; anchors themselves are gridless.
       */
      public int cellSize() {
         return this.cellSize;
      }

      /** Squared minimum distance between two anchors of this stratum. */
      public int spacingRadiusSquared() {
         return this.spacingRadiusSquared;
      }

      /** Blocks in the spacing neighbourhood including the centre; one anchor per this many on average. */
      public int neighborhoodSize() {
         return this.spacingOffsets.length + 1;
      }

      /** Side of the window scanned by {@link #representativeAnchor}. */
      public int representativeWindow() {
         return this.representativeWindow;
      }

      /**
       * Half-width of the block neighbourhood a caller has to scan so that a plant of this stratum
       * anchored inside it can still reach the centre column; matches the legacy 3x3-cell scan.
       */
      public int scanRadius() {
         return (3 * this.cellSize - 1) / 2;
      }

      /**
       * Probability that an anchor carries a plant, given the density tuned per
       * {@code cellSize x cellSize} blocks. Calibrated so the expected number of plants per block is
       * {@code min(density, 1) / cellSize²}, the same as the legacy lattice, whenever the spacing
       * neighbourhood is no larger than the cell.
       */
      double anchorProbability(double density) {
         return clamp01(clamp01(density) * this.anchorProbabilityScale);
      }

      int[][] spacingOffsets() {
         return this.spacingOffsets;
      }

      double patchScaleMeters() {
         return this.patchScaleMeters;
      }

      int variantCount() {
         return this.variantCount;
      }

      long salt() {
         return this.salt;
      }
   }

   public enum StandState {
      OPEN,
      REGENERATING,
      MATURE,
      OLD_GROWTH
   }

   public record Anchor(int worldX, int worldZ, long seed) {
   }

   public record Environment(
      int coverClass,
      VegetationCommunity community,
      TellusProceduralTreeGenerator.Profile treeProfile,
      long spatialSeed,
      double worldScale,
      int expectedSurface,
      double canopyHeightMeters,
      boolean lowCanopy,
      double canopyShade,
      double edgeStrength,
      int distanceToWater,
      int slope,
      boolean snowCovered,
      boolean placeable
   ) {
      public Environment {
         Objects.requireNonNull(community, "community");
         Objects.requireNonNull(treeProfile, "treeProfile");
      }
   }

   public record Placement(
      Stratum stratum,
      VegetationCommunity community,
      TellusProceduralTreeGenerator.Profile treeProfile,
      StandState standState,
      int worldX,
      int worldZ,
      int expectedSurface,
      int size,
      int variant,
      long seed
   ) {
      public Placement {
         Objects.requireNonNull(stratum, "stratum");
         Objects.requireNonNull(community, "community");
         Objects.requireNonNull(treeProfile, "treeProfile");
         Objects.requireNonNull(standState, "standState");
      }
   }
}
