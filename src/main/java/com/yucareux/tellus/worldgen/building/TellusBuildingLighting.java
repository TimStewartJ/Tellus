package com.yucareux.tellus.worldgen.building;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class TellusBuildingLighting {
   public static final byte BUILDING_LIGHT_LEVEL = 15;
   public static final int LOD_WINDOW_DARK = 0;
   public static final int LOD_WINDOW_WARM = 1;
   public static final int LOD_WINDOW_COOL = 2;

   private TellusBuildingLighting() {
   }

   public static boolean shouldPlaceInteriorLight(
      BuildingBlueprint blueprint, int boundaryDistance, int worldX, int worldZ, int floorIndex
   ) {
      FloorLightingGeometry geometry = floorGeometry(blueprint, boundaryDistance, floorIndex);
      if (!geometry.hasInterior()) {
         return false;
      }

      int localX = worldX - geometry.innerMinX();
      int localZ = worldZ - geometry.innerMinZ();
      if (localX < 0 || localX >= geometry.innerWidth() || localZ < 0 || localZ >= geometry.innerDepth()) {
         return false;
      }

      if (localX == geometry.anchorX() && localZ == geometry.anchorZ()) {
         return true;
      }

      if (shouldPlaceFacadeStripLight(blueprint, geometry, localX, localZ, floorIndex)) {
         return true;
      }

      return geometry.denseGrid()
         && Math.floorMod(localX - geometry.gridPhaseX(), geometry.gridSpacingX()) == 0
         && Math.floorMod(localZ - geometry.gridPhaseZ(), geometry.gridSpacingZ()) == 0;
   }

   /**
    * Night state of a window in a distant-horizons LOD facade: dark, or lit in warm (homes) or cool (offices)
    * light. Rooms are lit or dark as a whole, so the decision is made once per window bay (once per cell when
    * cells are wider) and floor, at the share of windows a building of that kind shows lit at night.
    */
   public static int resolveLodWindowGlow(BuildingBlueprint blueprint, int worldX, int worldZ, int floorIndex, int cellSize) {
      BuildingProfile.BuildingCategory category = blueprint.profile().category();
      double litShare = nightLitWindowShare(category, floorIndex);
      if (litShare <= 0.0) {
         return LOD_WINDOW_DARK;
      }

      int bay = Math.max(Math.max(1, cellSize), blueprint.profile().windowSpacing());
      long roll = mixFloorSeed(blueprint.blueprintSeed(), floorIndex)
         ^ Math.floorDiv(worldX, bay) * -7046029254386353131L
         ^ Math.floorDiv(worldZ, bay) * -4417276706812531889L;
      roll = (roll ^ roll >>> 33) * -49064778989728563L;
      roll = (roll ^ roll >>> 33) * -4265267296055464877L;
      roll ^= roll >>> 33;
      if (unitRoll(roll) >= litShare) {
         return LOD_WINDOW_DARK;
      }
      return unitRoll(Long.rotateLeft(roll, 29)) < warmWindowShare(category) ? LOD_WINDOW_WARM : LOD_WINDOW_COOL;
   }

   /**
    * The light-emitting block a lit LOD window shows: glowstone's amber for warm light, the sea lantern of
    * Tellus's interior lights for cool light. DH marks both as illuminated, so shader packs make them glow.
    */
   public static BlockState lodWindowLightBlock(int glow) {
      return glow == LOD_WINDOW_WARM ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.SEA_LANTERN.defaultBlockState();
   }

   private static double nightLitWindowShare(BuildingProfile.BuildingCategory category, int floorIndex) {
      boolean groundFloor = floorIndex == 0;
      return switch (category) {
         case HOUSE -> 0.32;
         case RESIDENTIAL -> 0.4;
         case HOTEL -> groundFloor ? 0.8 : 0.5;
         case COMMERCIAL -> groundFloor ? 0.8 : 0.4;
         case OFFICE -> groundFloor ? 0.5 : 0.3;
         case HOSPITAL -> 0.6;
         case TALL_BUILDING, MODERN_SKYSCRAPER -> 0.4;
         case GLASSY_SKYSCRAPER -> 0.45;
         case TOWER -> 0.25;
         case INDUSTRIAL, HISTORIC -> 0.2;
         case FARM, RELIGIOUS -> 0.15;
         case GARAGE, WAREHOUSE -> 0.08;
         case SCHOOL -> 0.06;
         case GREENHOUSE -> 0.05;
         case SHED -> 0.03;
         case GENERIC -> 0.3;
      };
   }

   private static double warmWindowShare(BuildingProfile.BuildingCategory category) {
      return switch (category) {
         case HOUSE, RESIDENTIAL, HOTEL, FARM, SHED, RELIGIOUS, HISTORIC, GENERIC -> 0.85;
         case COMMERCIAL -> 0.5;
         default -> 0.2;
      };
   }

   private static double unitRoll(long roll) {
      return (roll >>> 11) * 0x1.0p-53;
   }

   // Put some ceiling lights directly behind facades so night windows read as lit,
   // without flooding every floor with emitters.
   private static boolean shouldPlaceFacadeStripLight(
      BuildingBlueprint blueprint, FloorLightingGeometry geometry, int localX, int localZ, int floorIndex
   ) {
      int spacing = facadeStripSpacing(blueprint);
      if (spacing <= 0) {
         return false;
      }

      boolean onWestEastStrip = localX == 0 || localX == geometry.innerWidth() - 1;
      boolean onNorthSouthStrip = localZ == 0 || localZ == geometry.innerDepth() - 1;
      if (!onWestEastStrip && !onNorthSouthStrip) {
         return false;
      }

      long floorSeed = mixFloorSeed(blueprint.blueprintSeed(), floorIndex);
      int phaseX = positiveFloorMod(floorSeed ^ geometry.innerWidth() * 31L, spacing);
      int phaseZ = positiveFloorMod(Long.rotateLeft(floorSeed, 7) ^ geometry.innerDepth() * 17L, spacing);
      boolean westEastLit = onWestEastStrip && Math.floorMod(localZ - phaseZ, spacing) == 0;
      boolean northSouthLit = onNorthSouthStrip && Math.floorMod(localX - phaseX, spacing) == 0;
      return westEastLit || northSouthLit;
   }

   private static FloorLightingGeometry floorGeometry(BuildingBlueprint blueprint, int boundaryDistance, int floorIndex) {
      int setback = blueprint.setbackForFloor(floorIndex);
      int innerMinX = blueprint.minWorldX() + setback + 1;
      int innerMaxX = blueprint.maxWorldX() - setback - 1;
      int innerMinZ = blueprint.minWorldZ() + setback + 1;
      int innerMaxZ = blueprint.maxWorldZ() - setback - 1;
      int innerWidth = innerMaxX - innerMinX + 1;
      int innerDepth = innerMaxZ - innerMinZ + 1;
      if (boundaryDistance < setback + 1 || innerWidth <= 0 || innerDepth <= 0) {
         return new FloorLightingGeometry(innerMinX, innerMinZ, innerWidth, innerDepth, 0, 0, 1, 1, 0, 0, false);
      }

      long floorSeed = mixFloorSeed(blueprint.blueprintSeed(), floorIndex);
      int centerX = innerWidth / 2;
      int centerZ = innerDepth / 2;
      int anchorSpreadX = Math.max(0, innerWidth / 6);
      int anchorSpreadZ = Math.max(0, innerDepth / 6);
      int anchorX = Mth.clamp(centerX + signedOffset(floorSeed, anchorSpreadX), 0, innerWidth - 1);
      int anchorZ = Mth.clamp(centerZ + signedOffset(Long.rotateLeft(floorSeed, 19), anchorSpreadZ), 0, innerDepth - 1);
      int gridSpacingX = switch (blueprint.profile().archetype()) {
         case HOUSE -> 5;
         case APARTMENT -> 7;
         case COMMERCIAL -> 6;
         case INDUSTRIAL -> 8;
         case TOWER -> 8;
         case GENERIC -> 7;
      };
      int gridSpacingZ = switch (blueprint.profile().archetype()) {
         case HOUSE -> 5;
         case APARTMENT -> 6;
         case COMMERCIAL -> 6;
         case INDUSTRIAL -> 7;
         case TOWER -> 8;
         case GENERIC -> 7;
      };
      int gridPhaseX = positiveFloorMod(floorSeed, gridSpacingX);
      int gridPhaseZ = positiveFloorMod(Long.rotateLeft(floorSeed, 11), gridSpacingZ);
      boolean denseGrid = innerWidth >= gridSpacingX * 2 || innerDepth >= gridSpacingZ * 2;
      return new FloorLightingGeometry(innerMinX, innerMinZ, innerWidth, innerDepth, anchorX, anchorZ, gridSpacingX, gridSpacingZ, gridPhaseX, gridPhaseZ, denseGrid);
   }

   private static long mixFloorSeed(long blueprintSeed, int floorIndex) {
      return Long.rotateLeft(blueprintSeed ^ 0x9E3779B97F4A7C15L, floorIndex + 1) + floorIndex * 0x632BE59BD9B4E019L;
   }

   private static int facadeStripSpacing(BuildingBlueprint blueprint) {
      return switch (blueprint.profile().archetype()) {
         case HOUSE -> 0;
         case APARTMENT -> 5;
         case COMMERCIAL -> 4;
         case INDUSTRIAL -> 5;
         case TOWER -> 4;
         case GENERIC -> 5;
      };
   }

   private static int signedOffset(long seed, int magnitude) {
      if (magnitude <= 0) {
         return 0;
      }

      int width = magnitude * 2 + 1;
      return positiveFloorMod(seed, width) - magnitude;
   }

   private static int positiveFloorMod(long value, int modulus) {
      return Math.floorMod((int)(value ^ value >>> 32), modulus);
   }

   private record FloorLightingGeometry(
      int innerMinX,
      int innerMinZ,
      int innerWidth,
      int innerDepth,
      int anchorX,
      int anchorZ,
      int gridSpacingX,
      int gridSpacingZ,
      int gridPhaseX,
      int gridPhaseZ,
      boolean denseGrid
   ) {
      private boolean hasInterior() {
         return this.innerWidth > 0 && this.innerDepth > 0;
      }
   }
}
