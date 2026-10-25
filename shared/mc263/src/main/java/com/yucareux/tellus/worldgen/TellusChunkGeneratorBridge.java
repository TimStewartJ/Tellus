package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Adapts vanilla generation entry points without duplicating Tellus terrain logic. */
public abstract class TellusChunkGeneratorBridge extends ChunkGenerator {
   public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
            EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
         )
         .apply(instance, EarthChunkGenerator::new)
   );

   protected TellusChunkGeneratorBridge(BiomeSource biomes, Function<Holder<Biome>, BiomeGenerationSettings> settings) {
      super(biomes, settings);
   }

   @Override
   protected MapCodec<? extends ChunkGenerator> codec() {
      return Objects.requireNonNull(CODEC, "CODEC");
   }

   protected static Structure trialChambers(Registry<Structure> registry) {
      return registry.getValueOrThrow(BuiltinStructures.TRIAL_CHAMBERS);
   }

   protected ChunkGeneratorStructureState createStructureState(RandomState random, long seed, HolderLookup<StructureSet> structures) {
      return ChunkGeneratorStructureState.createForNormal(random, seed, this.getOrigin(random), this.biomeSource, structures);
   }

   protected abstract void addTellusDebugInfo(List<String> info, RandomState random, BlockPos pos);

   @Override
   public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos, SamplerContext context) {
      this.addTellusDebugInfo(info, random, pos);
   }

   public abstract CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk);

   public abstract void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk);

   @Override
   public CompletableFuture<ChunkAccess> buildTerrain(
      ChunkAccess chunk, Blender blender, RandomState random, StructureManager structures,
      BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> possibleBiomes
   ) {
      return this.fillFromNoise(blender, random, structures, chunk).thenApply(filled -> {
         this.applyCarvers(region, region.getSeed(), random, biomes, structures, filled);
         return filled;
      });
   }
}
