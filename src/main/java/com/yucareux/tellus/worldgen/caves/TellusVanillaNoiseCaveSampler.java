package com.yucareux.tellus.worldgen.caves;

import com.google.common.base.Preconditions;
import com.yucareux.tellus.worldgen.GeologicalStonePlacementPolicy;
import com.yucareux.tellus.worldgen.UndergroundSurfaceGrid;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Samples the real vanilla Overworld density router into a temporary standard
 * height field, then projects its caves into Tellus terrain by depth below the
 * local surface. The temporary field never writes vanilla terrain into the
 * target chunk.
 */
public final class TellusVanillaNoiseCaveSampler {
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   private static final int SURFACE_COVER_DEPTH = 4;
   private static final int Y_OFFSET_BITS = 16;
   private static final int Y_OFFSET_MASK = (1 << Y_OFFSET_BITS) - 1;
   private static final int Y_OFFSET_SHIFT = 8;
   private static final int REPLACEMENT_SHIFT = 24;
   private static final int REPLACEMENT_MASK = 15;
   private static final int NOISE_FEATURE_FLAG = 1 << 28;
   private static final byte RAW_SOLID = 0;
   private static final byte RAW_AIR = 1;
   private static final byte RAW_LAVA = 2;
   private static final byte RAW_FLUID = 3;
   private static final byte RAW_COPPER_ORE = 4;
   private static final byte RAW_COPPER_BLOCK = 5;
   private static final byte RAW_DEEPSLATE_IRON_ORE = 6;
   private static final byte RAW_IRON_BLOCK = 7;
   private static final byte RAW_GRANITE = 8;
   private static final byte RAW_TUFF = 9;
   private static final int REPLACE_CAVE_AIR = 1;
   private static final int REPLACE_WATER = 2;
   private static final int REPLACE_LAVA = 3;
   private static final int REPLACE_COPPER_ORE = 4;
   private static final int REPLACE_COPPER_BLOCK = 5;
   private static final int REPLACE_DEEPSLATE_IRON_ORE = 6;
   private static final int REPLACE_IRON_BLOCK = 7;
   private static final int REPLACE_GRANITE = 8;
   private static final int REPLACE_TUFF = 9;
   private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
   private static final BlockState WATER = Blocks.WATER.defaultBlockState();
   private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

   private final NoiseGeneratorSettings vanillaSettings;
   private volatile SeededRandomState cachedRandomState;

   public TellusVanillaNoiseCaveSampler(NoiseGeneratorSettings vanillaSettings) {
      this.vanillaSettings = vanillaSettings;
   }

   /**
    * Compiles the vanilla density field into an ordered list of candidate mutations. This method
    * reads no world or chunk state and is safe to run in Tellus's parallel fill stage.
    */
   public PreparedVanillaCavePlan prepare(
      RandomState randomState,
      ChunkPos chunkPos,
      int chunkMinY,
      int tellusSeaLevel,
      boolean applyCaves,
      boolean cavesReachSurface,
      boolean applyOreVeins,
      boolean applyGeologicalStonePatches,
      int[] surfaceYByColumn,
      IntBinaryOperator surfaceHeightSampler,
      int[] floodGuardYByColumn,
      int[] generationFloorYByColumn
   ) {
      Preconditions.checkArgument(
         applyCaves || applyOreVeins || applyGeologicalStonePatches,
         "At least one underground noise feature must be enabled"
      );
      Preconditions.checkArgument(surfaceYByColumn.length == CHUNK_AREA, "Tellus cave surface array must contain 256 columns");
      if (applyGeologicalStonePatches) {
         Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      }
      Objects.requireNonNull(randomState, "randomState");
      Objects.requireNonNull(chunkPos, "chunkPos");
      VanillaField field = this.sampleVanillaField(randomState, chunkPos);
      int chunkMinX = chunkPos.getMinBlockX();
      int chunkMinZ = chunkPos.getMinBlockZ();
      int[] minimumGeologySurfaceYByColumn = applyGeologicalStonePatches
         ? UndergroundSurfaceGrid.minimumNearbySurfaceYByColumn(
            surfaceHeightSampler,
            chunkMinX,
            chunkMinZ,
            GeologicalStonePlacementPolicy.NOISE_SURFACE_SAMPLE_RADIUS
         )
         : null;
      boolean exposeSurfaceEntrances = applyCaves && cavesReachSurface;
      IntArrayList mutations = new IntArrayList();

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int columnIndex = localZ * CHUNK_SIDE + localX;
            int actualSurfaceY = surfaceYByColumn[columnIndex];
            int virtualSurfaceY = field.surfaceReferenceY(localX, localZ, exposeSurfaceEntrances);
            if (virtualSurfaceY <= field.minY()) {
               continue;
            }

            int actualBottomY = chunkMinY + 1;
            if (generationFloorYByColumn != null) {
               actualBottomY = Math.max(actualBottomY, generationFloorYByColumn[columnIndex] + 1);
            }
            int firstCarveY = exposeSurfaceEntrances ? actualSurfaceY : actualSurfaceY - SURFACE_COVER_DEPTH - 1;
            if (firstCarveY < actualBottomY) {
               continue;
            }

            int minimumNearbySurfaceY = minimumGeologySurfaceYByColumn != null
               ? minimumGeologySurfaceYByColumn[columnIndex]
               : Integer.MAX_VALUE;
            for (int actualY = firstCarveY; actualY >= actualBottomY; actualY--) {
               int virtualY = TellusCaveDepthMapper.virtualYForActualY(
                  actualY, actualSurfaceY, actualBottomY, virtualSurfaceY, field.minY()
               );
               boolean caveAllowed = applyCaves
                  && (floodGuardYByColumn == null || actualY < floodGuardYByColumn[columnIndex]);
               byte rawKind = field.rawKind(localX, virtualY, localZ);
               int replacementKind = caveAllowed
                  ? caveReplacementKind(rawKind, actualY, tellusSeaLevel)
                  : 0;
               boolean noiseFeature = false;
               if (replacementKind == 0 && (applyOreVeins || applyGeologicalStonePatches)) {
                  replacementKind = noiseReplacementKind(
                     rawKind, applyOreVeins, applyGeologicalStonePatches
                  );
                  boolean geologicalStone = replacementKind == REPLACE_GRANITE
                     || replacementKind == REPLACE_TUFF;
                  if (geologicalStone
                     && !GeologicalStonePlacementPolicy.isNoiseStoneBuried(actualY, minimumNearbySurfaceY)) {
                     replacementKind = 0;
                  }
                  noiseFeature = replacementKind != 0;
               }
               if (replacementKind == 0) {
                  continue;
               }

               mutations.add(packMutation(
                  localX, localZ, actualY, chunkMinY, replacementKind, noiseFeature
               ));
            }
         }
      }
      return new PreparedVanillaCavePlan(
         chunkMinX, chunkMinZ, chunkMinY, mutations.toIntArray()
      );
   }

   /**
    * Applies a prepared plan in the historical column/Y order. Structure exclusions and current
    * replaceability are intentionally checked here because they belong to the serialized world
    * mutation stage.
    */
   public void applyPrepared(
      PreparedVanillaCavePlan plan,
      ChunkAccess chunk,
      List<UndergroundStructureExclusion.Box> structureExclusions,
      CaveBlockWriter blockWriter
   ) {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(blockWriter, "blockWriter");
      if (!plan.matches(chunk)) {
         throw new IllegalArgumentException("Prepared cave plan does not match chunk " + chunk.getPos());
      }

      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int packed : plan.mutations) {
         int localX = packed & 15;
         int localZ = packed >>> 4 & 15;
         int actualY = plan.chunkMinY + (packed >>> Y_OFFSET_SHIFT & Y_OFFSET_MASK);
         boolean noiseFeature = (packed & NOISE_FEATURE_FLAG) != 0;
         int worldX = plan.chunkMinX + localX;
         int worldZ = plan.chunkMinZ + localZ;
         if (!noiseFeature
            && UndergroundStructureExclusion.blocksCarving(
               structureExclusions, worldX, actualY, worldZ
            )) {
            continue;
         }

         cursor.set(worldX, actualY, worldZ);
         BlockState current = chunk.getBlockState(cursor);
         boolean replaceable = noiseFeature
            ? current.is(BlockTags.BASE_STONE_OVERWORLD)
            : current.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
         if (!replaceable) {
            continue;
         }

         BlockState replacement = replacementState(
            packed >>> REPLACEMENT_SHIFT & REPLACEMENT_MASK
         );
         blockWriter.set(
            chunk, cursor, replacement, !replacement.getFluidState().isEmpty()
         );
      }
   }

   private VanillaField sampleVanillaField(RandomState randomState, ChunkPos chunkPos) {
      NoiseSettings noiseSettings = this.vanillaSettings.noiseSettings();
      int minY = noiseSettings.minY();
      int height = noiseSettings.height();
      int cellWidth = noiseSettings.getCellWidth();
      int cellHeight = noiseSettings.getCellHeight();
      int horizontalCellCount = CHUNK_SIDE / cellWidth;
      int verticalCellCount = height / cellHeight;
      int cellMinY = Math.floorDiv(minY, cellHeight);
      Aquifer.FluidPicker fluidPicker = createFluidPicker(this.vanillaSettings);
      // Structure starts have already been retargeted into Tellus's actual Y
      // range. Feeding those coordinates into this temporary vanilla-height
      // field mixes two vertical coordinate systems and can stretch a local
      // structure beard into a surface-reaching cave chamber.
      SamplingNoiseChunk noiseChunk = new SamplingNoiseChunk(
         horizontalCellCount,
         randomState,
         chunkPos.getMinBlockX(),
         chunkPos.getMinBlockZ(),
         noiseSettings,
         TellusEmptyBeardifier.instance(),
         this.vanillaSettings,
         fluidPicker,
         Blender.empty()
      );
      byte[] rawKinds = new byte[height * CHUNK_AREA];
      int[] preliminarySurfaceY = new int[CHUNK_AREA];
      int[] highestSolidY = new int[CHUNK_AREA];
      java.util.Arrays.fill(highestSolidY, minY);
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int worldX = chunkPos.getMinBlockX() + localX;
            int worldZ = chunkPos.getMinBlockZ() + localZ;
            preliminarySurfaceY[localZ * CHUNK_SIDE + localX] = Mth.clamp(
               noiseChunk.preliminarySurfaceLevel(worldX, worldZ), minY, minY + height - 1
            );
         }
      }

      noiseChunk.initializeForFirstCellX();
      try {
         for (int cellX = 0; cellX < horizontalCellCount; cellX++) {
            noiseChunk.advanceCellX(cellX);
            for (int cellZ = 0; cellZ < horizontalCellCount; cellZ++) {
               for (int cellY = verticalCellCount - 1; cellY >= 0; cellY--) {
                  noiseChunk.selectCellYZ(cellY, cellZ);
                  for (int inCellY = cellHeight - 1; inCellY >= 0; inCellY--) {
                     int virtualY = (cellMinY + cellY) * cellHeight + inCellY;
                     noiseChunk.updateForY(virtualY, inCellY / (double)cellHeight);
                     for (int inCellX = 0; inCellX < cellWidth; inCellX++) {
                        int localX = cellX * cellWidth + inCellX;
                        int worldX = chunkPos.getMinBlockX() + localX;
                        noiseChunk.updateForX(worldX, inCellX / (double)cellWidth);
                        for (int inCellZ = 0; inCellZ < cellWidth; inCellZ++) {
                           int localZ = cellZ * cellWidth + inCellZ;
                           int worldZ = chunkPos.getMinBlockZ() + localZ;
                           noiseChunk.updateForZ(worldZ, inCellZ / (double)cellWidth);
                           BlockState state = noiseChunk.sampleInterpolatedState();
                           if (state == null) {
                              state = this.vanillaSettings.defaultBlock();
                           }
                           int columnIndex = localZ * CHUNK_SIDE + localX;
                           rawKinds[VanillaField.index(localX, virtualY, localZ, minY)] = rawKind(state);
                           if (!state.isAir()
                              && state.getFluidState().isEmpty()
                              && virtualY > highestSolidY[columnIndex]) {
                              highestSolidY[columnIndex] = virtualY;
                           }
                        }
                     }
                  }
               }
            }
            noiseChunk.swapSlices();
         }
      } finally {
         noiseChunk.stopInterpolation();
      }

      return new VanillaField(minY, height, rawKinds, preliminarySurfaceY, highestSolidY);
   }

   RandomState randomStateFor(RegistryAccess registryAccess, long worldSeed) {
      SeededRandomState cached = this.cachedRandomState;
      if (cached != null && cached.seed() == worldSeed) {
         return cached.state();
      }

      synchronized (this) {
         cached = this.cachedRandomState;
         if (cached == null || cached.seed() != worldSeed) {
            RandomState state = RandomState.create(
               this.vanillaSettings, registryAccess.lookupOrThrow(Registries.NOISE), worldSeed
            );
            cached = new SeededRandomState(worldSeed, state);
            this.cachedRandomState = cached;
         }
         return cached.state();
      }
   }

   private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
      Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, LAVA);
      Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
      int lavaCeiling = Math.min(-54, settings.seaLevel());
      return (x, y, z) -> y < lavaCeiling ? lava : sea;
   }

   private static int caveReplacementKind(byte rawKind, int actualY, int tellusSeaLevel) {
      return switch (rawKind) {
         case RAW_AIR -> REPLACE_CAVE_AIR;
         case RAW_LAVA -> REPLACE_LAVA;
         case RAW_FLUID -> actualY <= tellusSeaLevel
            ? REPLACE_WATER
            : REPLACE_CAVE_AIR;
         default -> 0;
      };
   }

   private static int noiseReplacementKind(
      byte rawKind, boolean applyOreVeins, boolean applyGeologicalStonePatches
   ) {
      if (applyOreVeins) {
         int oreKind = switch (rawKind) {
            case RAW_COPPER_ORE -> REPLACE_COPPER_ORE;
            case RAW_COPPER_BLOCK -> REPLACE_COPPER_BLOCK;
            case RAW_DEEPSLATE_IRON_ORE -> REPLACE_DEEPSLATE_IRON_ORE;
            case RAW_IRON_BLOCK -> REPLACE_IRON_BLOCK;
            default -> 0;
         };
         if (oreKind != 0) {
            return oreKind;
         }
      }
      if (applyGeologicalStonePatches) {
         return switch (rawKind) {
            case RAW_GRANITE -> REPLACE_GRANITE;
            case RAW_TUFF -> REPLACE_TUFF;
            default -> 0;
         };
      }
      return 0;
   }

   private static byte rawKind(BlockState state) {
      if (state.isAir()) {
         return RAW_AIR;
      }
      if (state.is(Blocks.LAVA)) {
         return RAW_LAVA;
      }
      if (!state.getFluidState().isEmpty()) {
         return RAW_FLUID;
      }
      if (state.is(Blocks.COPPER_ORE)) {
         return RAW_COPPER_ORE;
      }
      if (state.is(Blocks.RAW_COPPER_BLOCK)) {
         return RAW_COPPER_BLOCK;
      }
      if (state.is(Blocks.DEEPSLATE_IRON_ORE)) {
         return RAW_DEEPSLATE_IRON_ORE;
      }
      if (state.is(Blocks.RAW_IRON_BLOCK)) {
         return RAW_IRON_BLOCK;
      }
      if (state.is(Blocks.GRANITE)) {
         return RAW_GRANITE;
      }
      return state.is(Blocks.TUFF) ? RAW_TUFF : RAW_SOLID;
   }

   private static BlockState replacementState(int replacementKind) {
      return switch (replacementKind) {
         case REPLACE_CAVE_AIR -> CAVE_AIR;
         case REPLACE_WATER -> WATER;
         case REPLACE_LAVA -> LAVA;
         case REPLACE_COPPER_ORE -> Blocks.COPPER_ORE.defaultBlockState();
         case REPLACE_COPPER_BLOCK -> Blocks.RAW_COPPER_BLOCK.defaultBlockState();
         case REPLACE_DEEPSLATE_IRON_ORE -> Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
         case REPLACE_IRON_BLOCK -> Blocks.RAW_IRON_BLOCK.defaultBlockState();
         case REPLACE_GRANITE -> Blocks.GRANITE.defaultBlockState();
         case REPLACE_TUFF -> Blocks.TUFF.defaultBlockState();
         default -> throw new IllegalArgumentException(
            "Unknown prepared cave replacement kind " + replacementKind
         );
      };
   }

   private static int packMutation(
      int localX,
      int localZ,
      int actualY,
      int chunkMinY,
      int replacementKind,
      boolean noiseFeature
   ) {
      int yOffset = actualY - chunkMinY;
      if (yOffset < 0 || yOffset > Y_OFFSET_MASK) {
         throw new IllegalArgumentException(
            "Prepared cave Y " + actualY + " is outside the encodable chunk range from " + chunkMinY
         );
      }
      return localX
         | localZ << 4
         | yOffset << Y_OFFSET_SHIFT
         | replacementKind << REPLACEMENT_SHIFT
         | (noiseFeature ? NOISE_FEATURE_FLAG : 0);
   }

   static BlockState oreVeinReplacement(
      BlockState vanillaState, boolean applyOreVeins, boolean applyGeologicalStonePatches
   ) {
      int replacementKind = noiseReplacementKind(
         rawKind(vanillaState), applyOreVeins, applyGeologicalStonePatches
      );
      return replacementKind == 0 ? null : replacementState(replacementKind);
   }

   static int surfaceReferenceY(int highestSolidY, int preliminarySurfaceY, boolean cavesReachSurface) {
      return cavesReachSurface && preliminarySurfaceY - highestSolidY > SURFACE_COVER_DEPTH
         ? preliminarySurfaceY
         : highestSolidY;
   }

   private record SeededRandomState(long seed, RandomState state) {
   }

   @FunctionalInterface
   public interface CaveBlockWriter {
      void set(ChunkAccess chunk, BlockPos pos, BlockState state, boolean fluid);
   }

   public static final class PreparedVanillaCavePlan {
      private static final int FIXED_ESTIMATED_BYTES = 64;
      private final int chunkMinX;
      private final int chunkMinZ;
      private final int chunkMinY;
      private final int[] mutations;

      private PreparedVanillaCavePlan(
         int chunkMinX, int chunkMinZ, int chunkMinY, int[] mutations
      ) {
         this.chunkMinX = chunkMinX;
         this.chunkMinZ = chunkMinZ;
         this.chunkMinY = chunkMinY;
         this.mutations = mutations;
      }

      public int mutationCount() {
         return this.mutations.length;
      }

      public int estimatedBytes() {
         long estimated = FIXED_ESTIMATED_BYTES + (long)this.mutations.length * Integer.BYTES;
         return (int)Math.min(Integer.MAX_VALUE, estimated);
      }

      public long checksum() {
         long checksum = 1L;
         for (int mutation : this.mutations) {
            checksum = checksum * 31L + mutation;
         }
         return checksum;
      }

      private boolean matches(ChunkAccess chunk) {
         return chunk.getPos().getMinBlockX() == this.chunkMinX
            && chunk.getPos().getMinBlockZ() == this.chunkMinZ;
      }
   }

   private record VanillaField(
      int minY,
      int height,
      byte[] rawKinds,
      int[] preliminarySurfaceY,
      int[] highestSolidY
   ) {
      byte rawKind(int localX, int y, int localZ) {
         return this.rawKinds[index(localX, y, localZ, this.minY)];
      }

      int surfaceReferenceY(int localX, int localZ, boolean cavesReachSurface) {
         int highestSolidY = this.highestSolidY[localZ * CHUNK_SIDE + localX];
         int preliminaryY = this.preliminarySurfaceY[localZ * CHUNK_SIDE + localX];
         return TellusVanillaNoiseCaveSampler.surfaceReferenceY(highestSolidY, preliminaryY, cavesReachSurface);
      }

      static int index(int localX, int y, int localZ, int minY) {
         return (y - minY) * CHUNK_AREA + localZ * CHUNK_SIDE + localX;
      }
   }

   private static final class SamplingNoiseChunk extends NoiseChunk {
      SamplingNoiseChunk(
         int horizontalCellCount,
         RandomState randomState,
         int firstBlockX,
         int firstBlockZ,
         NoiseSettings noiseSettings,
         DensityFunctions.BeardifierOrMarker beardifier,
         NoiseGeneratorSettings generatorSettings,
         Aquifer.FluidPicker fluidPicker,
         Blender blender
      ) {
         super(
            horizontalCellCount,
            randomState,
            firstBlockX,
            firstBlockZ,
            noiseSettings,
            beardifier,
            generatorSettings,
            fluidPicker,
            blender
         );
      }

      BlockState sampleInterpolatedState() {
         return this.getInterpolatedState();
      }
   }
}
