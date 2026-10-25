package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Aquifer.FluidPicker;
import net.minecraft.world.level.levelgen.Aquifer.FluidStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

public final class TellusVanillaCarverRunner {
   private static final int CARVER_RADIUS_CHUNKS = 8;
   
   private final BiomeSource biomeSource;
   
   private final NoiseBasedChunkGenerator carvingContextGenerator;
   
   private final NoiseGeneratorSettings contextNoiseSettings;
   private final TellusConfiguredCarvers configuredCarvers;
   private final int chunkMinY;
   private final TellusVanillaNoiseCaveSampler noiseCaveSampler;

   public TellusVanillaCarverRunner(
      BiomeSource biomeSource,
      Registry<Block> blockRegistry,
      Holder<NoiseGeneratorSettings> vanillaNoiseSettings,
      Holder<NoiseGeneratorSettings> noiseSettings,
      int tellusMinY,
      int tellusHeight,
      int undergroundDepth
   ) {
      this.biomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
      Objects.requireNonNull(blockRegistry, "blockRegistry");
      Holder<NoiseGeneratorSettings> contextSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
      this.chunkMinY = tellusMinY;
      this.contextNoiseSettings = Objects.requireNonNull((NoiseGeneratorSettings)contextSettings.value(), "contextNoiseSettings");
      this.carvingContextGenerator = Objects.requireNonNull(new NoiseBasedChunkGenerator(this.biomeSource, contextSettings), "carvingContextGenerator");
      this.configuredCarvers = TellusConfiguredCarvers.create(blockRegistry, tellusMinY, tellusHeight, undergroundDepth);
      this.noiseCaveSampler = new TellusVanillaNoiseCaveSampler(
         Objects.requireNonNull(vanillaNoiseSettings, "vanillaNoiseSettings").value()
      );
   }

   public void applyCarvers(
      WorldGenRegion level,
      long worldSeed,
      BiomeManager biomeManager,
      StructureManager structures,
      ChunkAccess chunk,
      int tellusSeaLevel,
      boolean applyCaves,
      boolean cavesReachSurface,
      boolean applyOreVeins,
      boolean applyGeologicalStonePatches,
      int[] surfaceYByColumn,
      IntBinaryOperator surfaceHeightSampler,
      int[] floodGuardYByColumn,
      int[] generationFloorYByColumn,
      List<UndergroundStructureExclusion.Box> structureExclusions
   ) {
      StructureManager safeStructures = Objects.requireNonNull(structures, "structures");
      IntBinaryOperator safeSurfaceHeightSampler = Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      NoiseGeneratorSettings safeNoiseSettings = Objects.requireNonNull(this.contextNoiseSettings, "contextNoiseSettings");
      NoiseBasedChunkGenerator safeCarvingContextGenerator = Objects.requireNonNull(this.carvingContextGenerator, "carvingContextGenerator");
      RegistryAccess registryAccess = level.registryAccess();
      RandomState safeRandomState = this.noiseCaveSampler.randomStateFor(registryAccess, worldSeed);
      this.noiseCaveSampler.apply(
         registryAccess,
         worldSeed,
         chunk,
         this.chunkMinY,
         tellusSeaLevel,
         applyCaves,
         cavesReachSurface,
         applyOreVeins,
         applyGeologicalStonePatches,
         surfaceYByColumn,
         safeSurfaceHeightSampler,
         floodGuardYByColumn,
         generationFloorYByColumn,
         structureExclusions,
         (target, pos, state, fluid) -> {
            target.setBlockState(pos, state);
            if (fluid && target instanceof ProtoChunk protoChunk) {
               MinecraftVersionCompat.markPosForPostProcessing(protoChunk, pos);
            }
         }
      );
      if (!applyCaves) {
         return;
      }
      BiomeManager carvedBiomeManager = biomeManager.withDifferentSource(this.biomeSource.createUncachedResolver(safeRandomState));
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      ChunkPos targetPos = chunk.getPos();
      WorldGenerationContext context = new WorldGenerationContext(safeCarvingContextGenerator, chunk.getHeightAccessorForGeneration());
      CarvingMask mask = new CarvingMask(chunk.getMinY() + 1, chunk.getMaxY() - (chunk.isUpgrading() ? 0 : 7));
      for (int dx = -CARVER_RADIUS_CHUNKS; dx <= CARVER_RADIUS_CHUNKS; dx++) {
         for (int dz = -CARVER_RADIUS_CHUNKS; dz <= CARVER_RADIUS_CHUNKS; dz++) {
            ChunkPos sourcePos = new ChunkPos(targetPos.x() + dx, targetPos.z() + dz);
            int surfaceY = safeSurfaceHeightSampler.applyAsInt(sourcePos.getMinBlockX() + 8, sourcePos.getMinBlockZ() + 8);
            List<WorldCarver> carvers = this.configuredCarvers.orderedCarvers(surfaceY);
            for (int index = 0; index < carvers.size(); index++) {
               WorldCarver carver = carvers.get(index);
               random.setLargeFeatureSeed(worldSeed + index, sourcePos.x(), sourcePos.z());
               if (carver.isStartChunk(random)) {
                  carver.carve(context, random, targetPos, sourcePos, mask);
               }
            }
         }
      }
      if (mask.isEmpty()) {
         return;
      }
      // 26.3 carvers only describe carved cells. Apply Tellus's flood, terrain-shell
      // and structure guards when materializing that mask, before any block write.
      DensityVolume volume = new DensityVolume(16, chunk.getHeight(), 16, targetPos.getMinBlockX(), chunk.getMinY(), targetPos.getMinBlockZ());
      try (NoiseChunk noise = new NoiseChunk(safeRandomState,
         Beardifier.forStructuresInChunk(safeStructures, targetPos), safeNoiseSettings,
         createFluidPicker(this.chunkMinY + 8, tellusSeaLevel), Blender.empty(), volume)) {
         BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
         BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
         Aquifer aquifer = noise.aquifer();
         mask.visit((x, z, bottom, top) -> {
            int worldX = targetPos.getBlockX(x);
            int worldZ = targetPos.getBlockZ(z);
            boolean foundSurface = false;
            for (int y = top; y >= bottom; y--) {
               if (isProtected(x, y, z, targetPos, cavesReachSurface, surfaceYByColumn,
                  floodGuardYByColumn, generationFloorYByColumn, structureExclusions)) {
                  continue;
               }
               cursor.set(worldX, y, worldZ);
               BlockState current = chunk.getBlockState(cursor);
               if (current.is(BlockTags.UNCARVABLE)) {
                  continue;
               }
               foundSurface |= current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.MYCELIUM);
               BlockState replacement = aquifer.computeSubstance(worldX, y, worldZ, 0.0);
               if (replacement == null) {
                  continue;
               }
               chunk.setBlockState(cursor, replacement);
               if (aquifer.shouldScheduleFluidUpdate() && !replacement.getFluidState().isEmpty()) {
                  chunk.markPosForPostProcessing(cursor);
               }
               if (foundSurface && !isProtected(x, y - 1, z, targetPos, cavesReachSurface, surfaceYByColumn,
                  floodGuardYByColumn, generationFloorYByColumn, structureExclusions)) {
                  below.setWithOffset(cursor, Direction.DOWN);
                  if (chunk.getBlockState(below).is(Blocks.DIRT)) {
                     // Vanilla's private carving-mask pass still calls topMaterial; 26.3 has no public replacement.
                     @SuppressWarnings("deprecation")
                     Optional<BlockState> topMaterial = safeRandomState.surfaceSystem().topMaterial(
                        safeNoiseSettings.materialRule().value(), safeRandomState, context, carvedBiomeManager::getBiome,
                        chunk, noise.cachingSamplers(), below, !replacement.getFluidState().isEmpty()
                     );
                     topMaterial.ifPresent(state -> {
                        chunk.setBlockState(below, state);
                        if (!state.getFluidState().isEmpty()) {
                           chunk.markPosForPostProcessing(below);
                        }
                     });
                  }
               }
            }
         });
      }
   }

   static boolean isProtected(
      int x, int y, int z, ChunkPos chunkPos, boolean cavesReachSurface,
      int[] surfaceYByColumn, int[] floodGuardYByColumn, int[] generationFloorYByColumn,
      List<UndergroundStructureExclusion.Box> structureExclusions
   ) {
      int index = (z & 15) * 16 + (x & 15);
      return !cavesReachSurface && surfaceYByColumn != null && y >= surfaceYByColumn[index] - 4
         || floodGuardYByColumn != null && y >= floodGuardYByColumn[index]
         || generationFloorYByColumn != null && y <= generationFloorYByColumn[index]
         || UndergroundStructureExclusion.blocksCarving(structureExclusions, chunkPos.getBlockX(x & 15), y, chunkPos.getBlockZ(z & 15));
   }

   private static FluidPicker createFluidPicker(int lavaLevel, int seaLevel) {
      FluidStatus lava = new FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
      FluidStatus water = new FluidStatus(seaLevel, Blocks.WATER.defaultBlockState());
      return Objects.requireNonNull((x, y, z) -> y < lavaLevel ? lava : water, "fluidPicker");
   }
}
