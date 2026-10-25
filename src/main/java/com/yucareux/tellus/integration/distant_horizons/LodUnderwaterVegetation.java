package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.TellusBlockReferences;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sea-floor vegetation of direct LOD columns (seagrass meadows, kelp forests, coral reefs), drawn at the size it
 * has in the world instead of as patches of whole cells.
 *
 * <p>Smooth habitat fields (value noise over tens to hundreds of blocks, faded with depth) give how much of the
 * floor around a column a meadow, a kelp forest or a reef covers. Cells of a few blocks show the plants
 * themselves: coral colonies a few blocks wide in their own colours, kelp stalks and seagrass tufts. A wider cell
 * holds many plants, so a share of such cells equal to the cover takes the colour the vegetation has from afar:
 * a reef of coral heads among sand turns dusty rose (mauve in its core), a kelp forest dark olive and a meadow
 * moss green. Distant Horizons shows the floor through deep water without fog, so a cell in coral colours would
 * stand out as a bright square tens of blocks wide.</p>
 *
 * <p>Every decision depends only on the column's position, cell size, habitat and depth, so adjacent tiles
 * agree at their seams and coarse cells keep the average colour of the finer ones below them.</p>
 *
 * <p>A decision is packed in an int: bits 0-3 hold the kind of the floor's top block ({@link #KIND_NONE} keeps
 * the terrain's own), bits 4-7 the kind of the block standing on the floor and bits 8-15 how many blocks it
 * stands.</p>
 */
final class LodUnderwaterVegetation {
   static final int HABITAT_NONE = 0;
   /** Warm ocean: coral reefs among seagrass meadows. */
   static final int HABITAT_WARM = 1;
   /** Lukewarm ocean: seagrass meadows, some kelp. */
   static final int HABITAT_LUKEWARM = 2;
   /** Temperate ocean: kelp forests and seagrass. */
   static final int HABITAT_TEMPERATE = 3;
   /** Cold ocean: kelp forests, little seagrass. */
   static final int HABITAT_COLD = 4;
   /** Rivers and lakes: water plants in the shallows. */
   static final int HABITAT_FRESH = 5;
   /** Swamps and mangroves. */
   static final int HABITAT_SWAMP = 6;

   static final int KIND_NONE = 0;
   /** A seagrass meadow (moss block). */
   static final int KIND_SEAGRASS = 1;
   /** Kelp, stalks or a forest floor seen from above (green terracotta). */
   static final int KIND_KELP = 2;
   /** A reef seen from afar, coral heads among sand (white terracotta). */
   static final int KIND_REEF = 3;
   /** The core of a reef, almost all coral (magenta terracotta). */
   static final int KIND_REEF_DENSE = 4;
   static final int KIND_TUBE_CORAL = 5;
   static final int KIND_BRAIN_CORAL = 6;
   static final int KIND_BUBBLE_CORAL = 7;
   static final int KIND_FIRE_CORAL = 8;
   static final int KIND_HORN_CORAL = 9;
   static final int KIND_COUNT = 10;

   // -Dtellus.dhWaterVegetation=false leaves LOD sea floors bare.
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.dhWaterVegetation"));

   // Cells up to this size show single colonies and stalks; wider ones the colour of the whole habitat.
   static final int PLANT_MAX_CELL = 4;
   private static final int KELP_STALK_MAX_CELL = 2;
   private static final int COLONY_GRID = 4;
   private static final double COLONY_MIN_RADIUS = 1.2;
   private static final double COLONY_MAX_RADIUS = 2.6;
   private static final double KELP_STALK_SHARE = 0.45;
   private static final int KELP_MIN_HEIGHT = 2;
   private static final int KELP_MAX_HEIGHT = 10;
   private static final double DENSE_REEF = 0.8;
   private static final double DENSE_REEF_SHARE = 0.4;
   // Habitat fields: a broad octave for the extent of meadows, forests and reefs, a finer one for their edges.
   private static final int MEADOW_PERIOD = 160;
   private static final int MEADOW_DETAIL_PERIOD = 48;
   private static final int KELP_PERIOD = 112;
   private static final int KELP_DETAIL_PERIOD = 32;
   private static final int REEF_PERIOD = 224;
   private static final int REEF_DETAIL_PERIOD = 56;
   private static final double BROAD_WEIGHT = 0.6;
   private static final long MEADOW_SALT = 0x6A09E667F3BCC908L;
   private static final long MEADOW_DETAIL_SALT = -0x4498517A7B3558C5L;
   private static final long KELP_SALT = 0x3C6EF372FE94F82BL;
   private static final long KELP_DETAIL_SALT = -0x5AB00AC5A0E2C90FL;
   private static final long REEF_SALT = 0x510E527FADE682D1L;
   private static final long REEF_DETAIL_SALT = -0x64FA9773D4C193E1L;
   private static final long COLONY_SALT = 0x1F83D9ABFB41BD6BL;
   private static final long CELL_SALT = 0x5BE0CD19137E2179L;

   private LodUnderwaterVegetation() {
   }

   static boolean enabled() {
      return ENABLED;
   }

   static int floorKind(int packed) {
      return packed & 15;
   }

   static int raisedKind(int packed) {
      return packed >>> 4 & 15;
   }

   static int raisedHeight(int packed) {
      return packed >>> 8 & 255;
   }

   private static int floor(int kind) {
      return kind;
   }

   private static int raised(int kind, int height) {
      return kind << 4 | Math.min(255, height) << 8;
   }

   static BlockState block(int kind) {
      return switch (kind) {
         case KIND_SEAGRASS -> Blocks.MOSS_BLOCK.defaultBlockState();
         case KIND_KELP -> TellusBlockReferences.terracottaState("GREEN");
         case KIND_REEF -> TellusBlockReferences.terracottaState("WHITE");
         case KIND_REEF_DENSE -> TellusBlockReferences.terracottaState("MAGENTA");
         case KIND_TUBE_CORAL -> Blocks.TUBE_CORAL_BLOCK.defaultBlockState();
         case KIND_BRAIN_CORAL -> Blocks.BRAIN_CORAL_BLOCK.defaultBlockState();
         case KIND_BUBBLE_CORAL -> Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState();
         case KIND_FIRE_CORAL -> Blocks.FIRE_CORAL_BLOCK.defaultBlockState();
         case KIND_HORN_CORAL -> Blocks.HORN_CORAL_BLOCK.defaultBlockState();
         default -> Blocks.AIR.defaultBlockState();
      };
   }

   /** Water depth in metres of a column {@code depthBlocks} deep, taken at the middle of its deepest block. */
   static double depthMeters(int depthBlocks, double metersPerBlock) {
      return (depthBlocks - 0.5) * Math.max(1.0E-3, metersPerBlock);
   }

   /**
    * The vegetation of an underwater column whose cell is {@code cellSize} blocks wide and whose floor lies
    * {@code depthBlocks} under the surface ({@code depthMeters} deep in the real world), or 0 for bare floor.
    */
   static int resolve(int habitat, int worldX, int worldZ, int cellSize, int depthBlocks, double depthMeters) {
      if (habitat == HABITAT_NONE || depthBlocks < 1) {
         return 0;
      }
      double meadow = meadowCover(habitat, depthMeters, cellSize);
      double reef = habitat == HABITAT_WARM ? fade(depthMeters, 25.0, 50.0) : 0.0;
      double kelp = kelpCover(habitat, depthMeters);
      if (meadow <= 0.0 && reef <= 0.0 && kelp <= 0.0) {
         return 0;
      }

      // Each roll is checked against the most its habitat could cover before the field is sampled.
      long cell = hash(worldX, worldZ, CELL_SALT);
      if (reef > 0.0) {
         if (cellSize <= PLANT_MAX_CELL) {
            int colony = coralColony(worldX, worldZ, cellSize, reef, depthBlocks);
            if (colony != 0) {
               return colony;
            }
         } else {
            double roll = unit(cell);
            if (roll < reef) {
               double density = reefDensity(worldX, worldZ, cellSize, reef);
               if (roll < density) {
                  boolean core = density > DENSE_REEF && unit(Long.rotateLeft(cell, 17)) < DENSE_REEF_SHARE;
                  return floor(core ? KIND_REEF_DENSE : KIND_REEF);
               }
            }
         }
      }
      if (kelp > 0.0) {
         double roll = unit(Long.rotateLeft(cell, 29));
         if (roll < kelp * KELP_STALK_SHARE
            && roll < kelp * KELP_STALK_SHARE * smoothstep(0.46, 0.6, field(worldX, worldZ, cellSize, KELP_PERIOD, KELP_SALT, KELP_DETAIL_PERIOD, KELP_DETAIL_SALT))) {
            int height = Math.min(KELP_MIN_HEIGHT + (int)((cell >>> 3 & 0xFFFF) % (KELP_MAX_HEIGHT - KELP_MIN_HEIGHT + 1)), depthBlocks - 2);
            return cellSize <= KELP_STALK_MAX_CELL && height >= 1 ? raised(KIND_KELP, height) : floor(KIND_KELP);
         }
      }
      if (meadow > 0.0) {
         double roll = unit(Long.rotateLeft(cell, 43));
         if (roll < meadow
            && roll < meadow * smoothstep(0.46, 0.6, field(worldX, worldZ, cellSize, MEADOW_PERIOD, MEADOW_SALT, MEADOW_DETAIL_PERIOD, MEADOW_DETAIL_SALT))) {
            return floor(KIND_SEAGRASS);
         }
      }
      return 0;
   }

   private static double reefDensity(int worldX, int worldZ, int cellSize, double reef) {
      return reef * smoothstep(0.52, 0.64, field(worldX, worldZ, cellSize, REEF_PERIOD, REEF_SALT, REEF_DETAIL_PERIOD, REEF_DETAIL_SALT));
   }

   /** Most seagrass grows in water less than about 12 m deep, in rivers and lakes only along shallow banks. */
   private static double meadowCover(int habitat, double depthMeters, int cellSize) {
      return switch (habitat) {
         case HABITAT_WARM -> 0.8 * fade(depthMeters, 12.0, 30.0);
         case HABITAT_LUKEWARM -> 0.85 * fade(depthMeters, 12.0, 30.0);
         case HABITAT_TEMPERATE -> 0.6 * fade(depthMeters, 10.0, 25.0);
         case HABITAT_COLD -> 0.35 * fade(depthMeters, 8.0, 20.0);
         // Narrow rivers and lakes would turn whole wide cells green.
         case HABITAT_FRESH -> cellSize <= PLANT_MAX_CELL ? 0.45 * fade(depthMeters, 1.5, 4.0) : 0.0;
         case HABITAT_SWAMP -> cellSize <= 2 * PLANT_MAX_CELL ? 0.6 * fade(depthMeters, 3.0, 8.0) : 0.0;
         default -> 0.0;
      };
   }

   /** Kelp needs a few metres of water to stand in and thins out below about 25 m. */
   private static double kelpCover(int habitat, double depthMeters) {
      double share = switch (habitat) {
         case HABITAT_LUKEWARM -> 0.5;
         case HABITAT_TEMPERATE, HABITAT_COLD -> 1.0;
         default -> 0.0;
      };
      return share <= 0.0 ? 0.0 : share * smoothstep(2.0, 5.0, depthMeters) * fade(depthMeters, 25.0, 45.0);
   }

   /**
    * A coral colony around the column: colonies sit on a jittered grid of {@link #COLONY_GRID} blocks, one per
    * grid cell with the probability the reef field gives (at most {@code reef}), each a dome of one species up
    * to three blocks high. Where the water is too shallow to stand in, the colony lies flat as the floor.
    */
   private static int coralColony(int worldX, int worldZ, int cellSize, double reef, int depthBlocks) {
      double px = worldX + 0.5;
      double pz = worldZ + 0.5;
      int gx0 = Math.floorDiv((int)Math.floor(px - COLONY_MAX_RADIUS), COLONY_GRID);
      int gx1 = Math.floorDiv((int)Math.floor(px + COLONY_MAX_RADIUS), COLONY_GRID);
      int gz0 = Math.floorDiv((int)Math.floor(pz - COLONY_MAX_RADIUS), COLONY_GRID);
      int gz1 = Math.floorDiv((int)Math.floor(pz + COLONY_MAX_RADIUS), COLONY_GRID);
      double density = -1.0;
      double best = Double.MAX_VALUE;
      long bestColony = 0L;
      double bestRadius = 0.0;
      for (int gz = gz0; gz <= gz1; gz++) {
         for (int gx = gx0; gx <= gx1; gx++) {
            // Bits 44-63 place the colony, 0-23 its centre and size, 24-40 its species and height.
            long colony = hash(gx, gz, COLONY_SALT);
            double presence = (colony >>> 44) * 0x1.0p-20;
            if (presence >= reef) {
               continue;
            }
            double cx = gx * COLONY_GRID + 0.5 + (colony & 0xFF) / 255.0 * (COLONY_GRID - 1);
            double cz = gz * COLONY_GRID + 0.5 + (colony >>> 8 & 0xFF) / 255.0 * (COLONY_GRID - 1);
            double radius = COLONY_MIN_RADIUS + (colony >>> 16 & 0xFF) / 255.0 * (COLONY_MAX_RADIUS - COLONY_MIN_RADIUS);
            double dx = px - cx;
            double dz = pz - cz;
            double distance = dx * dx + dz * dz;
            if (distance >= radius * radius || distance >= best) {
               continue;
            }
            if (density < 0.0) {
               density = reefDensity(worldX, worldZ, cellSize, reef);
            }
            if (presence < density) {
               best = distance;
               bestColony = colony;
               bestRadius = radius;
            }
         }
      }
      if (best == Double.MAX_VALUE) {
         return 0;
      }
      int kind = KIND_TUBE_CORAL + (int)((bestColony >>> 24 & 0xFFFF) % 5);
      int peak = 1 + (int)(bestColony >>> 40 & 1L) + (bestRadius > 2.0 ? 1 : 0);
      int height = Math.max(1, (int)Math.ceil(peak * (1.0 - best / (bestRadius * bestRadius))));
      height = Math.min(height, depthBlocks - 1);
      return height >= 1 ? raised(kind, height) : floor(kind);
   }

   /**
    * A habitat field in [0, 1]: smooth value noise over {@code period} blocks plus a finer octave for ragged
    * edges, which cells too wide to resolve it replace with its mean.
    */
   private static double field(int worldX, int worldZ, int cellSize, int period, long salt, int detailPeriod, long detailSalt) {
      double detail = detailPeriod >= 2 * cellSize ? valueNoise(worldX, worldZ, detailPeriod, detailSalt) : 0.5;
      return BROAD_WEIGHT * valueNoise(worldX, worldZ, period, salt) + (1.0 - BROAD_WEIGHT) * detail;
   }

   private static double valueNoise(int worldX, int worldZ, int period, long salt) {
      int x0 = Math.floorDiv(worldX, period);
      int z0 = Math.floorDiv(worldZ, period);
      double tx = smooth((worldX - x0 * period) / (double)period);
      double tz = smooth((worldZ - z0 * period) / (double)period);
      double north = lerp(tx, unit(hash(x0, z0, salt)), unit(hash(x0 + 1, z0, salt)));
      double south = lerp(tx, unit(hash(x0, z0 + 1, salt)), unit(hash(x0 + 1, z0 + 1, salt)));
      return lerp(tz, north, south);
   }

   /** 1 up to {@code full}, falling smoothly to 0 at {@code none}. */
   private static double fade(double value, double full, double none) {
      return 1.0 - smoothstep(full, none, value);
   }

   private static double smoothstep(double edge0, double edge1, double value) {
      return smooth(Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0))));
   }

   private static double smooth(double t) {
      return t * t * (3.0 - 2.0 * t);
   }

   private static double lerp(double t, double a, double b) {
      return a + (b - a) * t;
   }

   private static long hash(int x, int z, long salt) {
      long value = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
      value = (value ^ value >>> 33) * 0xFF51AFD7ED558CCDL;
      value = (value ^ value >>> 33) * 0xC4CEB9FE1A85EC53L;
      return value ^ value >>> 33;
   }

   private static double unit(long hash) {
      return (hash >>> 11) * 0x1.0p-53;
   }
}
