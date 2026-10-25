package com.yucareux.tellus.worldgen.caves;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.core.Holder;
import java.util.EnumSet;
import java.util.Set;
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
      return RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, this.vanillaSettings);
   }

   static boolean isCarvable(BlockState state) {
      return !state.is(BlockTags.UNCARVABLE);
   }

   VanillaField sample(RegistryAccess registries, RandomState randomState, ChunkPos chunkPos) {
      NoiseSettings noiseSettings = this.vanillaSettings.noiseSettings();
      int minY = noiseSettings.minY();
      int height = noiseSettings.height();
      DensityVolume volume = new DensityVolume(16, height, 16, chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ());
      ProtoChunk sample = new ProtoChunk(
         chunkPos, UpgradeData.EMPTY, LevelHeightAccessor.create(minY, height), PalettedContainerFactory.create(registries), null
      );
      Holder<net.minecraft.world.level.biome.Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
      sample.fillBiomesFromNoise((x, y, z) -> plains);
      BlockState[] states = new BlockState[height * CHUNK_AREA];
      int[] preliminarySurfaceY = new int[CHUNK_AREA];
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      // Keep the virtual vanilla field independent of Tellus structure Y coordinates.
      try (NoiseChunk noise = new NoiseChunk(randomState, null, this.vanillaSettings,
         createFluidPicker(this.vanillaSettings), Blender.empty(), volume)) {
         var samplers = noise.cachingSamplers();
         var surfaceFunction = this.vanillaSettings.aquifers().map(Aquifer.Config::surfaceLevel)
            .orElse(this.vanillaSettings.noiseRouter().chunkSurfaceLevel());
         try (ScopedDensityBuffer density = samplers.get(this.vanillaSettings.noiseRouter().finalDensity()).sampleVolume(volume)) {
            for (int z = 0; z < CHUNK_SIDE; z++) {
               for (int x = 0; x < CHUNK_SIDE; x++) {
                  int worldX = volume.blockX(x);
                  int worldZ = volume.blockZ(z);
                  preliminarySurfaceY[z * CHUNK_SIDE + x] = Mth.clamp(
                     Mth.floor(samplers.sampleValue(surfaceFunction, worldX, 0, worldZ)), minY, minY + height - 1
                  );
                  for (int y = 0; y < height; y++) {
                     int worldY = volume.blockY(y);
                     BlockState state = noise.aquifer().computeSubstance(worldX, worldY, worldZ, density.get(volume.indexUnchecked(x, y, z)));
                     sample.setBlockState(cursor.set(worldX, worldY, worldZ), state == null ? this.vanillaSettings.defaultBlock() : state);
                  }
               }
            }
         }
         Heightmap.primeHeightmaps(sample, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
         // Ore veins moved from NoiseChunk to material rules in 26.3. Apply the live
         // Overworld rule to this temporary chunk before projecting caves and ores.
         NoiseBasedChunkGenerator vanilla = new NoiseBasedChunkGenerator(new FixedBiomeSource(plains), Holder.direct(this.vanillaSettings));
         // shortcut: the zoom seed cannot change a plains-only lookup, pass the world seed if this source starts varying.
         randomState.surfaceSystem().buildSurface(
            randomState, new BiomeManager((x, y, z) -> plains, 0L),
            new WorldGenerationContext(vanilla, sample), sample, noise, this.vanillaSettings.materialRule().value(), Set.of(plains)
         );
         for (int z = 0; z < CHUNK_SIDE; z++) {
            for (int x = 0; x < CHUNK_SIDE; x++) {
               for (int y = 0; y < height; y++) {
                  states[VanillaField.index(x, minY + y, z, minY)] = sample.getBlockState(cursor.set(volume.blockX(x), minY + y, volume.blockZ(z)));
               }
            }
         }
      }
      return new VanillaField(minY, height, states, preliminarySurfaceY);
   }

   private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings settings) {
      Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, LAVA);
      Aquifer.FluidStatus sea = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
      int lavaCeiling = Math.min(-54, settings.seaLevel());
      return (x, y, z) -> y < lavaCeiling ? lava : sea;
   }

}
