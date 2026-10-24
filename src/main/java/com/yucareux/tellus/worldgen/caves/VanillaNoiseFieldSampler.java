package com.yucareux.tellus.worldgen.caves;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import com.yucareux.tellus.worldgen.caves.TellusVanillaNoiseCaveSampler.VanillaField;

/** Native density sampling only; Tellus's surface-relative projection is shared. */
final class VanillaNoiseFieldSampler {
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = 256;
   private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
   private final NoiseGeneratorSettings vanillaSettings;

   VanillaNoiseFieldSampler(NoiseGeneratorSettings settings) {
      this.vanillaSettings = settings;
   }

   RandomState createRandomState(RegistryAccess registries, long seed) {
      return RandomState.create(this.vanillaSettings, registries.lookupOrThrow(Registries.NOISE), seed);
   }

   static boolean isCarvable(BlockState state) {
      return state.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
   }

   VanillaField sample(RegistryAccess registries, RandomState randomState, ChunkPos chunkPos) {
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
      BlockState[] states = new BlockState[height * CHUNK_AREA];
      int[] preliminarySurfaceY = new int[CHUNK_AREA];
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
                           states[VanillaField.index(localX, virtualY, localZ, minY)] = state;
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

      return new VanillaField(minY, height, states, preliminarySurfaceY);
   }

   private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
      Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, LAVA);
      Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
      int lavaCeiling = Math.min(-54, settings.seaLevel());
      return (x, y, z) -> y < lavaCeiling ? lava : sea;
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
   }}
