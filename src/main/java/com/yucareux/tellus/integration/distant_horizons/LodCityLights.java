package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Night lights of built-up land in direct LOD columns, at every detail level, so a city keeps its lights out
 * to the edge of the render distance instead of only where street-lamp fixtures are drawn (cells of one or
 * two blocks).
 *
 * <p>Every column decides from what it already has (its land cover, the road and building masks and a hash
 * of its position), so nothing is downloaded and adjacent tiles agree at their seams. Lit surfaces (streets,
 * the yards and squares of built-up land, the ground around lamps) keep their block and only carry block
 * light: DH lights them with the block-light colour, shader packs with their own, and daylight hides it.
 * Light sources (lamp heads and, in coarse cells, the brightest spots of a district) are light-emitting
 * blocks, which DH marks as illuminated so shader packs such as Sundial Lite make them glow. Lit windows are
 * chosen with the buildings ({@code TellusBuildingLighting.resolveLodWindowGlow}). Only worlds with roads or
 * buildings light their towns ({@link #lightsTowns}), and only within the world's LOD city lights distance of
 * the players ({@link #reach}).</p>
 *
 * <p>Sources stand on their surface like lamp heads. DH widens cells with distance, so a cell is about the
 * same size on screen at every detail, but the ground itself is seen ever more edge-on: from a hill a few
 * hundred blocks above a city, a flat light more than a few kilometres away is a fraction of a pixel tall
 * and vanishes. A source as tall as a quarter of its cell keeps about a pixel of height at any distance.</p>
 *
 * <p>A column's decision is packed in an int: bits 0-3 hold the block light of its lit surface, bits 4-5 the
 * light source ({@link #SOURCE_NONE}, {@link #SOURCE_LED}, {@link #SOURCE_SODIUM}) and bits 6-13 how many
 * blocks the source stands on the surface, shining at {@link #SOURCE_LIGHT}.</p>
 */
final class LodCityLights {
   static final int ESA_BUILT_UP = 50;
   static final int SOURCE_LIGHT = 15;
   static final int SOURCE_NONE = 0;
   /** White street lighting: the sea lantern of Tellus's own street lamps. */
   static final int SOURCE_LED = 1;
   /** Orange high-pressure sodium lighting. */
   static final int SOURCE_SODIUM = 2;
   // -Dtellus.dhCityLights=false leaves LOD columns unlit, as before.
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.dhCityLights"));
   private static final int HEIGHT_SHIFT = 6;
   private static final int MAX_SOURCE_HEIGHT = 255;
   private static final double SOURCE_HEIGHT_PER_CELL = 0.25;
   // Below this cell size each lamp is its own point and streets are lit only around it; from here on a cell
   // holds enough of a street that the lights of a whole block blend into one glow.
   private static final double STREET_GLOW_MIN_CELL_METERS = 4.0;
   // Built-up land between the streets glows once cells are this wide.
   private static final double AREA_GLOW_MIN_CELL_METERS = 16.0;
   private static final double AREA_SPARKLE_MIN_CELL_METERS = 32.0;
   private static final double LAMP_SPACING_METERS = 35.0;
   private static final double STREET_WIDTH_METERS = 10.0;
   private static final double MAX_LAMP_SHARE = 0.4;
   // Built-up land within this distance makes a paved road a lit street.
   private static final double URBAN_REACH_METERS = 60.0;
   private static final int MAX_URBAN_REACH_CELLS = 8;
   private static final double DISTRICT_METERS = 1536.0;
   private static final long LAMP_SALT = 0x3C6EF372FE94F82BL;
   private static final long SPARKLE_SALT = -0x5AB0B9D2A35E5E6FL;
   private static final long SPARKLE_SOURCE_SALT = 0x510E527FADE682D1L;
   private static final long AREA_JITTER_SALT = 0x1F83D9ABFB41BD6BL;
   private static final long DISTRICT_SALT = 0x5BE0CD19137E2179L;
   private static final long DISTRICT_KIND_SALT = -0x2B0A1F6C9E3779B9L;
   private static final long REACH_SALT = 0x6A09E667F3BCC909L;
   /** Coarsest world scale (metres per block) with roads and buildings, as the world settings allow. */
   static final double MAX_FEATURE_WORLD_SCALE = 15.0;
   /** Lights thin out over this last share of the city lights distance instead of ending at a hard circle. */
   private static final double REACH_FADE_SHARE = 0.125;

   private LodCityLights() {
   }

   static boolean enabled() {
      return ENABLED;
   }

   /**
    * Whether a world's towns light up in LODs. The lights stand for the street lamps of its roads and the lit
    * windows of its buildings, so a world with both switched off keeps its towns dark, as its chunks are; so
    * does a world coarser than 1:15, where Tellus builds neither.
    */
   static boolean lightsTowns(boolean roads, boolean buildings, double worldScale) {
      return ENABLED && (roads || buildings) && worldScale > 0.0 && worldScale <= MAX_FEATURE_WORLD_SCALE;
   }

   /**
    * Where a section's columns may hold city lights under the world's LOD city lights distance: within that
    * many chunks of the nearest player when DH generates the section, thinning out over the last
    * {@link #REACH_FADE_SHARE} of it. {@code distanceChunks <= 0} means no limit, and so do unknown player
    * positions (DH generates the nearest sections first).
    */
   static Reach reach(int distanceChunks, List<int[]> players, int minX, int minZ, int maxX, int maxZ) {
      if (distanceChunks <= 0 || players.isEmpty()) {
         return Reach.ALL;
      }
      double limit = distanceChunks * 16.0;
      double fadeStart = limit * (1.0 - REACH_FADE_SHARE);
      int[] xs = new int[players.size()];
      int[] zs = new int[players.size()];
      boolean anyInReach = false;
      for (int i = 0; i < xs.length; i++) {
         int[] player = players.get(i);
         xs[i] = player[0];
         zs[i] = player[1];
         long nearX = Math.max(0L, Math.max((long)minX - player[0], (long)player[0] - maxX));
         long nearZ = Math.max(0L, Math.max((long)minZ - player[1], (long)player[1] - maxZ));
         long farX = Math.max(Math.abs((long)minX - player[0]), Math.abs((long)maxX - player[0]));
         long farZ = Math.max(Math.abs((long)minZ - player[1]), Math.abs((long)maxZ - player[1]));
         if (farX * farX + farZ * farZ <= fadeStart * fadeStart) {
            return Reach.ALL;
         }
         anyInReach |= nearX * nearX + nearZ * nearZ <= limit * limit;
      }
      return anyInReach ? new Reach(xs, zs, limit, fadeStart) : Reach.NONE;
   }

   /** The columns of one section that may hold city lights; see {@link #reach}. */
   static final class Reach {
      static final Reach ALL = new Reach(null, null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
      static final Reach NONE = new Reach(null, null, -1.0, -1.0);
      private final int[] xs;
      private final int[] zs;
      private final double limit;
      private final double fadeStart;

      private Reach(int[] xs, int[] zs, double limit, double fadeStart) {
         this.xs = xs;
         this.zs = zs;
         this.limit = limit;
         this.fadeStart = fadeStart;
      }

      /** False when no column of the section may hold lights, so the section can skip them. */
      boolean any() {
         return this != NONE;
      }

      boolean covers(int worldX, int worldZ) {
         if (this.xs == null) {
            return this == ALL;
         }
         double nearest = Double.POSITIVE_INFINITY;
         for (int i = 0; i < this.xs.length; i++) {
            double dx = (double)worldX - this.xs[i];
            double dz = (double)worldZ - this.zs[i];
            nearest = Math.min(nearest, dx * dx + dz * dz);
         }
         if (nearest <= this.fadeStart * this.fadeStart) {
            return true;
         }
         if (nearest > this.limit * this.limit) {
            return false;
         }
         double keep = (this.limit - Math.sqrt(nearest)) / (this.limit - this.fadeStart);
         return unit(hash(worldX, worldZ, REACH_SALT)) < keep;
      }

      String describe() {
         return this == ALL ? "all" : this == NONE ? "none" : "partial";
      }
   }

   static double cellMeters(int cellSize, double worldScale) {
      return cellSize * (worldScale > 0.0 ? worldScale : 1.0);
   }

   static int light(int packed) {
      return packed & 15;
   }

   static int source(int packed) {
      return packed >>> 4 & 3;
   }

   /** Blocks the light source stands on its surface. */
   static int sourceHeight(int packed) {
      return packed >>> HEIGHT_SHIFT & MAX_SOURCE_HEIGHT;
   }

   private static int withSource(int glow, int source, double cellMeters, double worldScale) {
      double cellBlocks = cellMeters / (worldScale > 0.0 ? worldScale : 1.0);
      int height = (int)Math.max(1L, Math.min(MAX_SOURCE_HEIGHT, Math.round(cellBlocks * SOURCE_HEIGHT_PER_CELL)));
      return glow | source << 4 | height << HEIGHT_SHIFT;
   }

   static BlockState sourceBlock(int source) {
      return source == SOURCE_SODIUM ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
   }

   /** Paved streets light up; tracks, gravel and earth roads stay dark, as in {@code StreetLightPlanner}. */
   static boolean isLitRoadType(int roadClassId, byte style) {
      if (roadClassId != 1 && roadClassId != 2) {
         return false;
      }
      return switch (style) {
         case RoadSurfaceStyle.STYLE_GRAVEL, RoadSurfaceStyle.STYLE_DIRT, RoadSurfaceStyle.STYLE_SAND, RoadSurfaceStyle.STYLE_WOOD -> false;
         default -> true;
      };
   }

   /**
    * The road cells that are lit streets: lit road types with built-up land in the cell or within about
    * {@link #URBAN_REACH_METERS} (at least the neighbouring cells), so towns light their streets and
    * country roads stay dark. Null when no road cell is lit.
    */
   static boolean[] litStreets(byte[] roadClass, byte[] roadStyle, int[] coverClasses, int size, double cellMeters) {
      if (!ENABLED || roadClass == null || coverClasses == null || size <= 0) {
         return null;
      }
      int reach = Math.max(1, Math.min(MAX_URBAN_REACH_CELLS, (int)Math.ceil(URBAN_REACH_METERS / cellMeters)));
      int stride = size + 1;
      int[] builtUp = new int[stride * stride];
      for (int z = 0; z < size; z++) {
         int rowSum = 0;
         for (int x = 0; x < size; x++) {
            rowSum += coverClasses[z * size + x] == ESA_BUILT_UP ? 1 : 0;
            builtUp[(z + 1) * stride + x + 1] = builtUp[z * stride + x + 1] + rowSum;
         }
      }
      boolean[] lit = null;
      for (int z = 0; z < size; z++) {
         int z0 = Math.max(0, z - reach);
         int z1 = Math.min(size, z + reach + 1);
         for (int x = 0; x < size; x++) {
            int index = z * size + x;
            if (!isLitRoadType(roadClass[index], roadStyle == null ? 0 : roadStyle[index])) {
               continue;
            }
            int x0 = Math.max(0, x - reach);
            int x1 = Math.min(size, x + reach + 1);
            int count = builtUp[z1 * stride + x1] - builtUp[z0 * stride + x1] - builtUp[z1 * stride + x0] + builtUp[z0 * stride + x0];
            if (count > 0) {
               if (lit == null) {
                  lit = new boolean[size * size];
               }
               lit[index] = true;
            }
         }
      }
      return lit;
   }

   /**
    * Block light that the street-lamp fixtures of fine cells (see {@code StreetLightPlanner}) cast on the
    * surrounding ground, falling off one level per block like vanilla light, or null without lamps. A lamp
    * head stands {@code poleCount + 2} blocks above its ground, so the air over the ground beside it sits
    * {@code poleCount + 1} blocks below the head.
    */
   static byte[] lampPools(int[] lampBaseY, byte[] lampPoleCount, int size, int cellSize) {
      if (!ENABLED || lampBaseY == null || size <= 0 || cellSize <= 0) {
         return null;
      }
      byte[] pools = null;
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            int index = z * size + x;
            if (lampBaseY[index] == Integer.MIN_VALUE) {
               continue;
            }
            int poleCount = lampPoleCount == null ? 4 : Byte.toUnsignedInt(lampPoleCount[index]);
            int groundLight = SOURCE_LIGHT - (poleCount + 1);
            if (groundLight <= 0) {
               continue;
            }
            if (pools == null) {
               pools = new byte[size * size];
            }
            int reach = groundLight / cellSize;
            for (int dz = -reach; dz <= reach; dz++) {
               int pz = z + dz;
               if (pz < 0 || pz >= size) {
                  continue;
               }
               for (int dx = -reach; dx <= reach; dx++) {
                  int px = x + dx;
                  int level = groundLight - (Math.abs(dx) + Math.abs(dz)) * cellSize;
                  if (px >= 0 && px < size && level > pools[pz * size + px]) {
                     pools[pz * size + px] = (byte)level;
                  }
               }
            }
         }
      }
      return pools;
   }

   /**
    * A lit street cell: the street glows and some cells hold a white lamp head, about one per
    * {@link #LAMP_SPACING_METERS} of street (at most {@link #MAX_LAMP_SHARE} of the cells, so wide cells
    * still read as a row of lights). Where fixtures are drawn ({@code fixtures}), the street keeps only the
    * light they cast ({@code poolLight}).
    */
   static int street(int worldX, int worldZ, double cellMeters, double worldScale, boolean fixtures, int poolLight) {
      if (!ENABLED || fixtures || cellMeters < STREET_GLOW_MIN_CELL_METERS) {
         return Math.max(0, poolLight);
      }
      double district = district(worldX, worldZ, worldScale);
      int glow = (cellMeters < AREA_GLOW_MIN_CELL_METERS ? 12 : 13) + (district > 0.45 ? 1 : district < -0.45 ? -1 : 0);
      double lampShare = Math.min(MAX_LAMP_SHARE, cellMeters * cellMeters / (LAMP_SPACING_METERS * Math.max(cellMeters, STREET_WIDTH_METERS)));
      if (unit(hash(worldX, worldZ, LAMP_SALT)) >= lampShare) {
         return glow;
      }
      return withSource(glow, SOURCE_LED, cellMeters, worldScale);
   }

   /**
    * Land that is neither street nor building. Built-up cells wide enough to hold several streets glow
    * with the lights around them (brighter in some districts than others), and a share of them, growing
    * with the cell width, hold one of the district's bright spots, white or sodium orange. Other land keeps
    * the light that lamp fixtures cast on it.
    */
   static int ground(int worldX, int worldZ, int coverClass, double cellMeters, double worldScale, int poolLight) {
      if (!ENABLED || coverClass != ESA_BUILT_UP || cellMeters < AREA_GLOW_MIN_CELL_METERS) {
         return Math.max(0, poolLight);
      }
      double district = district(worldX, worldZ, worldScale);
      int glow = cellMeters < 32.0 ? 11 : cellMeters < 64.0 ? 12 : 13;
      glow += district > 0.45 ? 1 : district < -0.45 ? -1 : 0;
      if ((hash(worldX, worldZ, AREA_JITTER_SALT) & 3L) == 0L) {
         glow++;
      }
      glow = Math.max(poolLight, Math.min(14, glow));
      // Wider cells blend more lights each and lie farther away, where fewer screen pixels show them.
      double sparkleShare = (cellMeters < AREA_SPARKLE_MIN_CELL_METERS ? 0.04 : cellMeters < 64.0 ? 0.1 : cellMeters < 128.0 ? 0.2 : 0.28)
         * (1.0 + 0.5 * district);
      if (unit(hash(worldX, worldZ, SPARKLE_SALT)) >= sparkleShare) {
         return glow;
      }
      boolean led = unit(hash(worldX, worldZ, SPARKLE_SOURCE_SALT)) < ledShare(worldX, worldZ, worldScale);
      return withSource(glow, led ? SOURCE_LED : SOURCE_SODIUM, cellMeters, worldScale);
   }

   /** Smooth value noise in [-1, 1] over districts about {@link #DISTRICT_METERS} wide. */
   static double district(int worldX, int worldZ, double worldScale) {
      double period = districtBlocks(worldScale);
      double fx = worldX / period;
      double fz = worldZ / period;
      int x0 = (int)Math.floor(fx);
      int z0 = (int)Math.floor(fz);
      double tx = smooth(fx - x0);
      double tz = smooth(fz - z0);
      double north = lerp(tx, signed(x0, z0), signed(x0 + 1, z0));
      double south = lerp(tx, signed(x0, z0 + 1), signed(x0 + 1, z0 + 1));
      return lerp(tz, north, south);
   }

   /** Share of white (LED) lights among a district's bright spots: most districts are converted, some still burn sodium. */
   private static double ledShare(int worldX, int worldZ, double worldScale) {
      double period = districtBlocks(worldScale);
      long kind = hash((int)Math.floor(worldX / period), (int)Math.floor(worldZ / period), DISTRICT_KIND_SALT);
      return unit(kind) < 0.6 ? 0.8 : 0.25;
   }

   private static double districtBlocks(double worldScale) {
      return Math.max(48.0, DISTRICT_METERS / (worldScale > 0.0 ? worldScale : 1.0));
   }

   private static double signed(int x, int z) {
      return unit(hash(x, z, DISTRICT_SALT)) * 2.0 - 1.0;
   }

   private static double smooth(double t) {
      return t * t * (3.0 - 2.0 * t);
   }

   private static double lerp(double t, double a, double b) {
      return a + (b - a) * t;
   }

   static long hash(int x, int z, long salt) {
      long value = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
      value = (value ^ value >>> 33) * 0xFF51AFD7ED558CCDL;
      value = (value ^ value >>> 33) * 0xC4CEB9FE1A85EC53L;
      return value ^ value >>> 33;
   }

   static double unit(long hash) {
      return (hash >>> 11) * 0x1.0p-53;
   }
}
