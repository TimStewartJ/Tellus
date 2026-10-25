package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Adapts the Minecraft 1.20.1 chunk-generator contract to the shared generator. */
public abstract class TellusChunkGeneratorBridge extends ChunkGenerator {
   public static final Codec<EarthChunkGenerator> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(EarthChunkGenerator::getBiomeSource),
            EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
         )
         .apply(instance, EarthChunkGenerator::new)
   );

   protected TellusChunkGeneratorBridge(BiomeSource biomes, Function<Holder<Biome>, BiomeGenerationSettings> settings) {
      super(biomes, settings);
   }

   @Override
   protected final Codec<? extends ChunkGenerator> codec() {
      return CODEC;
   }

   protected ChunkGeneratorStructureState createStructureState(RandomState random, long seed, HolderLookup<StructureSet> structures) {
      return ChunkGeneratorStructureState.createForNormal(random, seed, this.biomeSource, structures);
   }

   protected abstract void addTellusDebugInfo(List<String> info, RandomState random, BlockPos pos);

   @Override
   public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
      this.addTellusDebugInfo(info, random, pos);
   }

   public abstract CompletableFuture<ChunkAccess> createBiomes(RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk);

   @Override
   public final CompletableFuture<ChunkAccess> createBiomes(
      Executor executor, RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk
   ) {
      return this.createBiomes(random, blender, structures, chunk);
   }

   public abstract CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk);

   @Override
   public final CompletableFuture<ChunkAccess> fillFromNoise(
      Executor executor, Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk
   ) {
      return this.fillFromNoise(blender, random, structures, chunk);
   }

   public abstract void applyCarvers(
      WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk
   );

   @Override
   public final void applyCarvers(
      WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk,
      GenerationStep.Carving step
   ) {
      // The shared generator carves in the single pass later versions use; the liquid pass has nothing left to do.
      if (step == GenerationStep.Carving.AIR) {
         this.applyCarvers(region, seed, random, biomes, structures, chunk);
      }
   }

   public void createStructures(
      RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structures, ChunkAccess chunk,
      StructureTemplateManager templates, ResourceKey<Level> levelKey
   ) {
      super.createStructures(registryAccess, structureState, structures, chunk, templates);
   }

   @Override
   public final void createStructures(
      RegistryAccess registryAccess, ChunkGeneratorStructureState structureState, StructureManager structures, ChunkAccess chunk,
      StructureTemplateManager templates
   ) {
      this.createStructures(registryAccess, structureState, structures, chunk, templates, null);
   }

   // Trial chambers arrived in 1.21.
   protected static Structure trialChambers(Registry<Structure> registry) {
      return null;
   }
}
