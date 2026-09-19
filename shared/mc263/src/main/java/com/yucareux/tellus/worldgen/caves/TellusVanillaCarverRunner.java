package com.yucareux.tellus.worldgen.caves;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.Aquifer.FluidPicker;
import net.minecraft.world.level.levelgen.Aquifer.FluidStatus;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

public final class TellusVanillaCarverRunner {
   private static final int CARVER_RADIUS_CHUNKS = 8;
   
   private final BiomeSource biomeSource;
   
   private final NoiseBasedChunkGenerator carvingContextGenerator;
   
   private final NoiseGeneratorSettings contextNoiseSettings;
   private final TellusConfiguredCarvers configuredCarvers;
   private final int chunkMinY;
   private final TellusVanillaNoiseCaveSampler noiseCaveSampler;
   private final RandomState vanillaRandomState;

   public TellusVanillaCarverRunner(
      BiomeSource biomeSource,
      Registry<Block> blockRegistry,
      Holder<NoiseGeneratorSettings> vanillaNoiseSettings,
      Holder<NoiseGeneratorSettings> noiseSettings,
      int tellusMinY,
      int tellusHeight,
      int undergroundDepth,
      RegistryAccess registryAccess,
      long worldSeed
   ) {
      this.biomeSource = Objects.requireNonNull(biomeSource, "biomeSource");
      Objects.requireNonNull(blockRegistry, "blockRegistry");
      Holder<NoiseGeneratorSettings> contextSettings = Objects.requireNonNull(noiseSettings, "noiseSettings");
      this.chunkMinY = tellusMinY;
      this.contextNoiseSettings = Objects.requireNonNull((NoiseGeneratorSettings)contextSettings.value(), "contextNoiseSettings");
      this.carvingContextGenerator = Objects.requireNonNull(new NoiseBasedChunkGenerator(this.biomeSource, contextSettings), "carvingContextGenerator");
      this.configuredCarvers = TellusConfiguredCarvers.create(tellusMinY, tellusHeight, undergroundDepth);
      this.noiseCaveSampler = new TellusVanillaNoiseCaveSampler(
         Objects.requireNonNull(vanillaNoiseSettings, "vanillaNoiseSettings").value()
      );
      this.vanillaRandomState = this.noiseCaveSampler.randomStateFor(
         Objects.requireNonNull(registryAccess, "registryAccess"), worldSeed
      );
   }

   public TellusVanillaNoiseCaveSampler.PreparedVanillaCavePlan prepareVanillaNoise(
      ChunkPos chunkPos,
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
      return this.noiseCaveSampler.prepare(
         this.vanillaRandomState,
         chunkPos,
         this.chunkMinY,
         tellusSeaLevel,
         applyCaves,
         cavesReachSurface,
         applyOreVeins,
         applyGeologicalStonePatches,
         surfaceYByColumn,
         surfaceHeightSampler,
         floodGuardYByColumn,
         generationFloorYByColumn
      );
   }

   public void applyCarvers(
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
      List<UndergroundStructureExclusion.Box> structureExclusions,
      TellusVanillaNoiseCaveSampler.PreparedVanillaCavePlan preparedVanillaNoise
   ) {
      StructureManager safeStructures = Objects.requireNonNull(structures, "structures");
      IntBinaryOperator safeSurfaceHeightSampler = Objects.requireNonNull(surfaceHeightSampler, "surfaceHeightSampler");
      NoiseGeneratorSettings safeNoiseSettings = Objects.requireNonNull(this.contextNoiseSettings, "contextNoiseSettings");
      NoiseBasedChunkGenerator safeCarvingContextGenerator = Objects.requireNonNull(this.carvingContextGenerator, "carvingContextGenerator");
      RandomState safeRandomState = this.vanillaRandomState;
      TellusVanillaNoiseCaveSampler.PreparedVanillaCavePlan noisePlan = preparedVanillaNoise != null
         ? preparedVanillaNoise
         : this.prepareVanillaNoise(
            chunk.getPos(),
            tellusSeaLevel,
            applyCaves,
            cavesReachSurface,
            applyOreVeins,
            applyGeologicalStonePatches,
            surfaceYByColumn,
            safeSurfaceHeightSampler,
            floodGuardYByColumn,
            generationFloorYByColumn
         );
      this.noiseCaveSampler.applyPrepared(
         noisePlan,
         chunk,
         structureExclusions,
         (target, pos, state, fluid) -> {
            target.setBlockState(pos, state);
            if (fluid) {
               MinecraftVersionCompat.markPosForPostProcessing(target, pos);
            }
         }
      );
      if (!applyCaves) {
         return;
      }
      BiomeResolver biomeResolver = this.biomeSource.createUncachedResolver(safeRandomState);
      BiomeManager carvedBiomeManager = biomeManager.withDifferentSource(biomeResolver);
      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
      ChunkPos targetPos = chunk.getPos();
      FluidPicker fluidPicker = Objects.requireNonNull(createFluidPicker(this.chunkMinY + 8, tellusSeaLevel), "fluidPicker");
      NoiseSettings noiseSettings = safeNoiseSettings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
      DensityVolume volume = new DensityVolume(
         16,
         noiseSettings.height(),
         16,
         targetPos.getMinBlockX(),
         noiseSettings.minY(),
         targetPos.getMinBlockZ()
      );
      try (NoiseChunk noiseChunk = new NoiseChunk(
            safeRandomState,
            Beardifier.forStructuresInChunk(safeStructures, chunk.getPos()),
            safeNoiseSettings,
            fluidPicker,
            Blender.empty(),
            volume
         )) {
         Aquifer aquifer = noiseChunk.aquifer();
         WorldGenerationContext carvingContext = new WorldGenerationContext(
            safeCarvingContextGenerator, chunk.getHeightAccessorForGeneration()
         );
         int protectedBlocksOnTop = chunk.isUpgrading() ? 0 : 7;
         int maskMaxY = carvingContext.getMinGenY() + carvingContext.getGenDepth() - 1 - protectedBlocksOnTop;
         CarvingMask carvingMask = new CarvingMask(carvingContext.getMinGenY() + 1, maskMaxY);
         TellusCarverOutput carvingOutput = new TellusCarverOutput(
            carvingMask,
            getCarvingGuard(
               chunk,
               cavesReachSurface,
               surfaceYByColumn,
               floodGuardYByColumn,
               generationFloorYByColumn,
               structureExclusions
            )
         );

         for (int offsetX = -CARVER_RADIUS_CHUNKS; offsetX <= CARVER_RADIUS_CHUNKS; offsetX++) {
            for (int offsetZ = -CARVER_RADIUS_CHUNKS; offsetZ <= CARVER_RADIUS_CHUNKS; offsetZ++) {
               ChunkPos sourcePos = new ChunkPos(
                  MinecraftVersionCompat.chunkX(targetPos) + offsetX,
                  MinecraftVersionCompat.chunkZ(targetPos) + offsetZ
               );
               int sourceSurfaceY = safeSurfaceHeightSampler.applyAsInt(sourcePos.getMinBlockX() + 8, sourcePos.getMinBlockZ() + 8);
               TellusConfiguredCarvers.SurfaceCarvers source = this.configuredCarvers.orderedCarvers(sourceSurfaceY);
               List<WorldCarver> sourceCarvers = source.carvers();
               carvingOutput.lavaLevelY = source.lavaLevelY();

               for (int carverIndex = 0; carverIndex < sourceCarvers.size(); carverIndex++) {
                  WorldCarver carver = sourceCarvers.get(carverIndex);
                  random.setLargeFeatureSeed(
                     worldSeed + carverIndex,
                     MinecraftVersionCompat.chunkX(sourcePos),
                     MinecraftVersionCompat.chunkZ(sourcePos)
                  );
                  if (carver.isStartChunk(random)) {
                     carver.carve(carvingContext, random, targetPos, sourcePos, carvingOutput);
                  }
               }
            }
         }

         if (!carvingMask.isEmpty()) {
            applyCarvingMask(
               chunk,
               carvingMask,
               safeRandomState,
               safeNoiseSettings.materialRule().value(),
               carvingContext,
               noiseChunk,
               carvedBiomeManager,
               aquifer
            );
            if (carvingOutput.lavaMask != null) {
               applyLavaMask(chunk, carvingOutput.lavaMask, aquifer);
            }
         }
      }
   }

   
   private static CarvingGuard getCarvingGuard(
      ChunkAccess chunk,
      boolean cavesReachSurface,
      int[] surfaceYByColumn,
      int[] floodGuardYByColumn,
      int[] generationFloorYByColumn,
      List<UndergroundStructureExclusion.Box> structureExclusions
   ) {
      if ((cavesReachSurface || surfaceYByColumn == null)
         && floodGuardYByColumn == null
         && generationFloorYByColumn == null
         && (structureExclusions == null || structureExclusions.isEmpty())) {
         return null;
      } else {
         int chunkMinX = chunk.getPos().getMinBlockX();
         int chunkMinZ = chunk.getPos().getMinBlockZ();
         return (x, y, z) -> {
            int localX = x & 15;
            int localZ = z & 15;
            int index = chunkIndex(localX, localZ);
            return !cavesReachSurface && surfaceYByColumn != null && y >= surfaceYByColumn[index] - 4
               || floodGuardYByColumn != null && y >= floodGuardYByColumn[index]
               || generationFloorYByColumn != null && y <= generationFloorYByColumn[index]
               || UndergroundStructureExclusion.blocksCarving(
                  structureExclusions, chunkMinX + localX, y, chunkMinZ + localZ
               );
         };
      }
   }

   private static void applyCarvingMask(
      ChunkAccess chunk,
      CarvingMask carvingMask,
      RandomState randomState,
      MaterialRule materialRule,
      WorldGenerationContext context,
      NoiseChunk noiseChunk,
      BiomeManager biomeManager,
      Aquifer aquifer
   ) {
      ChunkPos chunkPos = chunk.getPos();
      BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
      BlockPos.MutableBlockPos helperPos = new BlockPos.MutableBlockPos();
      carvingMask.visit((x, z, bottomY, topY) -> {
         boolean hasGrass = false;
         int worldX = chunkPos.getBlockX(x);
         int worldZ = chunkPos.getBlockZ(z);

         for (int worldY = topY; worldY >= bottomY; worldY--) {
            blockPos.set(worldX, worldY, worldZ);
            BlockState current = chunk.getBlockState(blockPos);
            if (current.is(BlockTags.UNCARVABLE)) {
               continue;
            }

            if (current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.MYCELIUM)) {
               hasGrass = true;
            }

            BlockState state = aquifer.computeSubstance(worldX, worldY, worldZ, 0.0);
            if (state == null) {
               continue;
            }

            chunk.setBlockState(blockPos, state);
            if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
               MinecraftVersionCompat.markPosForPostProcessing(chunk, blockPos);
            }

            if (hasGrass) {
               helperPos.setWithOffset(blockPos, Direction.DOWN);
               if (chunk.getBlockState(helperPos).is(Blocks.DIRT)) {
                  randomState.surfaceSystem()
                     .topMaterial(
                        materialRule,
                        randomState,
                        context,
                        biomeManager::getBiome,
                        chunk,
                        noiseChunk.cachingSamplers(),
                        helperPos,
                        !state.getFluidState().isEmpty()
                     )
                     .ifPresent(topMaterial -> {
                        chunk.setBlockState(helperPos, topMaterial);
                        if (!topMaterial.getFluidState().isEmpty()) {
                           MinecraftVersionCompat.markPosForPostProcessing(chunk, helperPos);
                        }
                     });
               }
            }
         }
      });
   }

   /**
    * Minecraft 26.2 carvers filled everything at or below their configured lava level with lava before
    * consulting the aquifer. Minecraft 26.3 carvers only report what they carve, so that fill happens here.
    */
   private static void applyLavaMask(ChunkAccess chunk, CarvingMask lavaMask, Aquifer aquifer) {
      ChunkPos chunkPos = chunk.getPos();
      BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
      BlockState lava = Blocks.LAVA.defaultBlockState();
      lavaMask.visit((x, z, bottomY, topY) -> {
         for (int worldY = topY; worldY >= bottomY; worldY--) {
            blockPos.set(chunkPos.getBlockX(x), worldY, chunkPos.getBlockZ(z));
            if (chunk.getBlockState(blockPos).is(BlockTags.UNCARVABLE)) {
               continue;
            }

            chunk.setBlockState(blockPos, lava);
            if (aquifer.shouldScheduleFluidUpdate()) {
               MinecraftVersionCompat.markPosForPostProcessing(chunk, blockPos);
            }
         }
      });
   }

   
   private static FluidPicker createFluidPicker(int lavaLevel, int seaLevel) {
      FluidStatus lava = new FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
      FluidStatus water = new FluidStatus(seaLevel, Blocks.WATER.defaultBlockState());
      return Objects.requireNonNull((x, y, z) -> y < lavaLevel ? lava : water, "fluidPicker");
   }

   private static int chunkIndex(int localX, int localZ) {
      return localZ << 4 | localX;
   }

   /** Blocks a carved-out cell when it returns true, as the additional carving mask did in 26.2. */
   @FunctionalInterface
   private interface CarvingGuard {
      boolean blocks(int x, int y, int z);
   }

   private static final class TellusCarverOutput implements CarverOutput {
      private final CarvingMask mask;
      private final CarvingGuard guard;
      private CarvingMask lavaMask;
      private int lavaLevelY;

      private TellusCarverOutput(CarvingMask mask, CarvingGuard guard) {
         this.mask = mask;
         this.guard = guard;
      }

      @Override
      public void carve(int x, int y, int z) {
         if (this.guard != null && this.guard.blocks(x, y, z)) {
            return;
         }

         this.mask.carve(x, y, z);
         if (y <= this.lavaLevelY) {
            if (this.lavaMask == null) {
               this.lavaMask = new CarvingMask(this.mask.minY(), this.mask.maxY());
            }
            this.lavaMask.carve(x, y, z);
         }
      }

      @Override
      public int minY() {
         return this.mask.minY();
      }

      @Override
      public int maxY() {
         return this.mask.maxY();
      }
   }
}
