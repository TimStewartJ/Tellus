package com.yucareux.tellus.worldgen.vegetation;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.compat.VegetationVersionCompat;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Plans and places deterministic vegetation strata around the mature tree layer.
 */
public final class TellusVegetationGenerator {
   private static final int PLACEMENT_FLAGS = 260;
   private static final boolean DEBUG = Boolean.getBoolean("tellus.debug.vegetation");
   private static final Comparator<TellusVegetationPlanner.Placement> PLACEMENT_ORDER = Comparator
      .comparingInt((TellusVegetationPlanner.Placement placement) -> placementOrder(placement.stratum()))
      .thenComparingInt(TellusVegetationPlanner.Placement::worldZ)
      .thenComparingInt(TellusVegetationPlanner.Placement::worldX);

   private TellusVegetationGenerator() {
   }

   public static List<TellusVegetationPlanner.Placement> planChunk(
      int chunkMinX,
      int chunkMinZ,
      long worldSeed,
      TellusVegetationGenerator.EnvironmentSampler sampler
   ) {
      Objects.requireNonNull(sampler, "sampler");
      List<TellusVegetationPlanner.Placement> placements = new ArrayList<>(96);
      int chunkMaxX = chunkMinX + 15;
      int chunkMaxZ = chunkMinZ + 15;
      for (TellusVegetationPlanner.Stratum stratum : TellusVegetationPlanner.Stratum.values()) {
         int cellSize = stratum.cellSize();
         int minCellX = Math.floorDiv(chunkMinX, cellSize);
         int maxCellX = Math.floorDiv(chunkMaxX, cellSize);
         int minCellZ = Math.floorDiv(chunkMinZ, cellSize);
         int maxCellZ = Math.floorDiv(chunkMaxZ, cellSize);
         for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
               TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
                  stratum, cellX, cellZ, worldSeed
               );
               if (anchor.worldX() < chunkMinX
                  || anchor.worldX() > chunkMaxX
                  || anchor.worldZ() < chunkMinZ
                  || anchor.worldZ() > chunkMaxZ) {
                  continue;
               }
               TellusVegetationPlanner.Environment environment = sampler.sample(
                  stratum, anchor.worldX(), anchor.worldZ(), anchor.seed()
               );
               TellusVegetationPlanner.Placement placement = TellusVegetationPlanner.plan(
                  stratum, anchor, environment
               );
               if (placement != null) {
                  placements.add(placement);
               }
            }
         }
      }
      if (placements.isEmpty()) {
         return List.of();
      }
      placements.sort(PLACEMENT_ORDER);
      List<TellusVegetationPlanner.Placement> result = List.copyOf(placements);
      if (DEBUG) {
         Tellus.LOGGER.info(
            "Ecological vegetation plan chunk=[{}, {}]: {}",
            Math.floorDiv(chunkMinX, 16),
            Math.floorDiv(chunkMinZ, 16),
            placementSummary(result)
         );
      }
      return result;
   }

   public static int placeAll(
      WorldGenLevel level, List<TellusVegetationPlanner.Placement> placements
   ) {
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(placements, "placements");
      int placed = 0;
      for (TellusVegetationPlanner.Placement placement : placements) {
         if (place(level, placement)) {
            placed++;
         }
      }
      if (DEBUG && !placements.isEmpty()) {
         TellusVegetationPlanner.Placement first = placements.get(0);
         Tellus.LOGGER.info(
            "Ecological vegetation applied chunk=[{}, {}]: placed={}/{}, {}",
            Math.floorDiv(first.worldX(), 16),
            Math.floorDiv(first.worldZ(), 16),
            placed,
            placements.size(),
            placementSummary(placements)
         );
      }
      return placed;
   }

   private static String placementSummary(
      List<TellusVegetationPlanner.Placement> placements
   ) {
      int[] counts = new int[TellusVegetationPlanner.Stratum.values().length];
      for (TellusVegetationPlanner.Placement placement : placements) {
         counts[placement.stratum().ordinal()]++;
      }
      StringBuilder summary = new StringBuilder();
      for (TellusVegetationPlanner.Stratum stratum : TellusVegetationPlanner.Stratum.values()) {
         if (!summary.isEmpty()) {
            summary.append(", ");
         }
         summary.append(stratum.name().toLowerCase(java.util.Locale.ROOT))
            .append('=')
            .append(counts[stratum.ordinal()]);
      }
      return summary.toString();
   }

   private static boolean place(
      WorldGenLevel level, TellusVegetationPlanner.Placement placement
   ) {
      int topY = level.getHeight(
         net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
         placement.worldX(),
         placement.worldZ()
      ) - 1;
      BlockPos ground = new BlockPos(placement.worldX(), topY, placement.worldZ());
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, ground)
         || !level.ensureCanWrite(ground)
         || !level.getFluidState(ground.above()).isEmpty()) {
         return false;
      }

      BlockState surface = level.getBlockState(ground);
      if (!supportsVegetation(surface)) {
         return false;
      }
      return switch (placement.stratum()) {
         case SUBCANOPY -> placeSubcanopy(level, ground, placement);
         case SHRUB -> placeShrub(level, ground, placement);
         case HERB -> placeHerb(level, ground, placement);
         case GROUND -> placeGroundCover(level, ground, placement);
         case DEADWOOD -> placeDeadwood(level, ground, placement);
      };
   }

   private static boolean placeSubcanopy(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement
   ) {
      TellusProceduralTreeGenerator.TreePlan plan = TellusProceduralTreeGenerator
         .plan(placement.treeProfile(), null, placement.seed())
         .withHeight(placement.size());
      return TellusProceduralTreeGenerator.place(level, ground, plan, placement.seed());
   }

   private static boolean placeShrub(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement
   ) {
      BlockState leaves = TellusProceduralTreeGenerator.leavesState(
         placement.treeProfile(), placement.seed()
      );
      BlockState log = TellusProceduralTreeGenerator.logState(
         placement.treeProfile(), placement.seed()
      );
      return switch (placement.community()) {
         case MALLEE -> placeMalleeShrub(level, ground, placement, log, leaves);
         case SUBARCTIC_SCRUB -> placeWindShrub(level, ground, placement, log, leaves);
         case MEDITERRANEAN_SCRUB, XERIC_SCRUB, SAVANNA ->
            placeOpenShrub(level, ground, placement, log, leaves);
         default -> placeRoundedShrub(level, ground, placement, log, leaves);
      };
   }

   private static boolean placeRoundedShrub(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement,
      BlockState log,
      BlockState leaves
   ) {
      int radius = placement.size();
      if (!setLog(level, ground.above(), axis(log, Direction.Axis.Y))) {
         return false;
      }
      placeLeafEllipsoid(
         level,
         ground.getX(),
         ground.getY() + 2,
         ground.getZ(),
         radius,
         Math.max(1, radius - 1),
         leaves,
         placement.seed(),
         0.68
      );
      return true;
   }

   private static boolean placeOpenShrub(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement,
      BlockState log,
      BlockState leaves
   ) {
      int radius = placement.size();
      if (!setLog(level, ground.above(), axis(log, Direction.Axis.Y))) {
         return false;
      }
      int lobes = radius >= 3 ? 4 : 3;
      for (int lobe = 0; lobe < lobes; lobe++) {
         double angle = Math.PI * 2.0 * lobe / lobes + unitHash(placement.seed(), lobe, 0) * 0.8;
         int x = ground.getX() + (int)Math.round(Math.cos(angle) * Math.max(1, radius - 1));
         int z = ground.getZ() + (int)Math.round(Math.sin(angle) * Math.max(1, radius - 1));
         placeLeafEllipsoid(
            level,
            x,
            ground.getY() + 2 + (lobe & 1),
            z,
            Math.max(1, radius - 1),
            1,
            leaves,
            placement.seed() + lobe * 67L,
            0.58
         );
      }
      return true;
   }

   private static boolean placeMalleeShrub(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement,
      BlockState log,
      BlockState leaves
   ) {
      int stems = 3 + placement.variant() % 3;
      boolean placed = false;
      for (int stem = 0; stem < stems; stem++) {
         double angle = Math.PI * 2.0 * stem / stems + unitHash(placement.seed(), stem, 1) * 0.55;
         int x = ground.getX() + (int)Math.round(Math.cos(angle));
         int z = ground.getZ() + (int)Math.round(Math.sin(angle));
         int height = 2 + (int)Math.floor(unitHash(placement.seed(), stem, 2) * 2.0);
         for (int y = 1; y <= height; y++) {
            placed |= setLog(level, new BlockPos(x, ground.getY() + y, z), axis(log, Direction.Axis.Y));
         }
         placeLeafEllipsoid(
            level,
            x,
            ground.getY() + height,
            z,
            Math.max(1, placement.size() - 1),
            1,
            leaves,
            placement.seed() + stem * 97L,
            0.62
         );
      }
      return placed;
   }

   private static boolean placeWindShrub(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement,
      BlockState log,
      BlockState leaves
   ) {
      int direction = placement.variant() & 3;
      int dx = direction == 0 ? 1 : direction == 1 ? -1 : 0;
      int dz = direction == 2 ? 1 : direction == 3 ? -1 : 0;
      if (!setLog(level, ground.above(), axis(log, Direction.Axis.Y))) {
         return false;
      }
      placeLeafEllipsoid(
         level,
         ground.getX() + dx,
         ground.getY() + 2,
         ground.getZ() + dz,
         placement.size(),
         1,
         leaves,
         placement.seed(),
         0.65
      );
      return true;
   }

   private static boolean placeHerb(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement
   ) {
      Block block = herbBlock(placement);
      return setPlant(level, ground.above(), block.defaultBlockState());
   }

   private static Block herbBlock(TellusVegetationPlanner.Placement placement) {
      int variant = placement.variant();
      return switch (placement.community()) {
         case BOREAL_FOREST, SUBARCTIC_SCRUB -> switch (variant % 5) {
            case 0, 1 -> Blocks.FERN;
            case 2 -> Blocks.SWEET_BERRY_BUSH;
            case 3 -> Blocks.BROWN_MUSHROOM;
            default -> VegetationVersionCompat.shortGrass();
         };
         case TROPICAL_MOIST_FOREST, WETLAND -> switch (variant % 6) {
            case 0, 1, 2 -> Blocks.FERN;
            case 3 -> Blocks.BLUE_ORCHID;
            case 4 -> Blocks.BROWN_MUSHROOM;
            default -> VegetationVersionCompat.shortGrass();
         };
         case MEDITERRANEAN_SCRUB,
            XERIC_SCRUB,
            SAVANNA,
            MALLEE,
            TROPICAL_DRY_FOREST,
            EUCALYPT_WOODLAND -> variant % 4 == 0
               ? Blocks.DEAD_BUSH
               : VegetationVersionCompat.shortGrass();
         default -> switch (variant % 6) {
            case 0 -> Blocks.FERN;
            case 1 -> Blocks.DANDELION;
            case 2 -> Blocks.POPPY;
            case 3 -> Blocks.OXEYE_DAISY;
            default -> VegetationVersionCompat.shortGrass();
         };
      };
   }

   private static boolean placeGroundCover(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement
   ) {
      Block block = switch (placement.community()) {
         case BOREAL_FOREST, TROPICAL_MOIST_FOREST, WETLAND ->
            placement.variant() % 5 == 0 ? Blocks.BROWN_MUSHROOM : Blocks.MOSS_CARPET;
         case TEMPERATE_FOREST, PINE_OAK_FOREST ->
            placement.variant() % 4 == 0
               ? Blocks.RED_MUSHROOM
               : VegetationVersionCompat.leafLitterOr(Blocks.MOSS_CARPET);
         case MEDITERRANEAN_SCRUB,
            XERIC_SCRUB,
            SAVANNA,
            MALLEE,
            TROPICAL_DRY_FOREST,
            EUCALYPT_WOODLAND -> placement.variant() % 3 == 0
               ? Blocks.DEAD_BUSH
               : VegetationVersionCompat.shortGrass();
         default -> Blocks.MOSS_CARPET;
      };
      return setPlant(level, ground.above(), block.defaultBlockState());
   }

   private static boolean placeDeadwood(
      WorldGenLevel level,
      BlockPos ground,
      TellusVegetationPlanner.Placement placement
   ) {
      Direction.Axis axis = (placement.variant() & 1) == 0 ? Direction.Axis.X : Direction.Axis.Z;
      int direction = (placement.variant() & 2) == 0 ? 1 : -1;
      BlockState log = axis(
         TellusProceduralTreeGenerator.logState(placement.treeProfile(), placement.seed()),
         axis
      );
      boolean placed = false;
      for (int step = 0; step < placement.size(); step++) {
         int x = ground.getX() + (axis == Direction.Axis.X ? step * direction : 0);
         int z = ground.getZ() + (axis == Direction.Axis.Z ? step * direction : 0);
         int topY = level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            x,
            z
         ) - 1;
         BlockPos position = new BlockPos(x, topY + 1, z);
         if (!supportsVegetation(level.getBlockState(position.below()))) {
            continue;
         }
         placed |= setLog(level, position, log);
      }
      return placed;
   }

   private static void placeLeafEllipsoid(
      WorldGenLevel level,
      int centerX,
      int centerY,
      int centerZ,
      int horizontalRadius,
      int verticalRadius,
      BlockState leaves,
      long seed,
      double density
   ) {
      double horizontalSquared = Math.max(1.0, horizontalRadius * horizontalRadius);
      double verticalSquared = Math.max(1.0, verticalRadius * verticalRadius);
      for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
         for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
               double normalized = (dx * dx + dz * dz) / horizontalSquared + dy * dy / verticalSquared;
               if (normalized <= 1.0
                  && unitHash(seed, centerX + dx, centerY + dy, centerZ + dz)
                     <= density + Math.max(0.0, 0.24 * (1.0 - normalized))) {
                  setLeaves(level, new BlockPos(centerX + dx, centerY + dy, centerZ + dz), leaves);
               }
            }
         }
      }
   }

   private static boolean setPlant(WorldGenLevel level, BlockPos position, BlockState plant) {
      if (!canReplace(level, position) || !plant.canSurvive(level, position)) {
         return false;
      }
      return level.setBlock(position, plant, PLACEMENT_FLAGS);
   }

   private static boolean setLog(WorldGenLevel level, BlockPos position, BlockState log) {
      if (!canReplace(level, position)) {
         return false;
      }
      return level.setBlock(position, log, PLACEMENT_FLAGS);
   }

   private static boolean setLeaves(
      WorldGenLevel level, BlockPos position, BlockState leaves
   ) {
      if (!canReplace(level, position)) {
         return false;
      }
      return level.setBlock(position, leaves, PLACEMENT_FLAGS);
   }

   private static boolean canReplace(WorldGenLevel level, BlockPos position) {
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, position)
         || !level.ensureCanWrite(position)
         || !level.getFluidState(position).isEmpty()) {
         return false;
      }
      BlockState current = level.getBlockState(position);
      return current.isAir()
         || !current.is(BlockTags.LEAVES) && current.is(BlockTags.REPLACEABLE_BY_TREES);
   }

   static boolean supportsVegetation(BlockState surface) {
      return surface.is(BlockTags.DIRT)
         || surface.is(BlockTags.SAND)
         || surface.is(Blocks.GRASS_BLOCK)
         || surface.is(Blocks.DIRT)
         || surface.is(Blocks.COARSE_DIRT)
         || surface.is(Blocks.PODZOL)
         || surface.is(Blocks.ROOTED_DIRT)
         || surface.is(Blocks.MYCELIUM)
         || surface.is(Blocks.MOSS_BLOCK)
         || surface.is(Blocks.MUD)
         || surface.is(Blocks.PACKED_MUD);
   }

   private static BlockState axis(BlockState state, Direction.Axis axis) {
      return state.hasProperty(BlockStateProperties.AXIS)
         ? state.setValue(BlockStateProperties.AXIS, axis)
         : state;
   }

   private static int placementOrder(TellusVegetationPlanner.Stratum stratum) {
      return switch (stratum) {
         case DEADWOOD -> 0;
         case SUBCANOPY -> 1;
         case SHRUB -> 2;
         case GROUND -> 3;
         case HERB -> 4;
      };
   }

   private static double unitHash(long seed, int x, int z) {
      return unitHash(seed, x, 0, z);
   }

   private static double unitHash(long seed, int x, int y, int z) {
      long value = seed
         ^ (long)x * 0x632BE59BD9B4E019L
         ^ (long)y * 0x9E3779B97F4A7C15L
         ^ (long)z * 0x94D049BB133111EBL;
      value ^= value >>> 30;
      value *= 0xBF58476D1CE4E5B9L;
      value ^= value >>> 27;
      value *= 0x94D049BB133111EBL;
      value ^= value >>> 31;
      return (value >>> 11) * 0x1.0p-53;
   }

   @FunctionalInterface
   public interface EnvironmentSampler {
      TellusVegetationPlanner.Environment sample(
         TellusVegetationPlanner.Stratum stratum, int worldX, int worldZ, long seed
      );
   }
}
