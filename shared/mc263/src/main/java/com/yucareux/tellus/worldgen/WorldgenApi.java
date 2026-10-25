package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature;
import net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature;

/** Small native API operations used by the shared 26.x generator. */
public final class WorldgenApi {
   private WorldgenApi() {}

   public static void placeBonusChest(net.minecraft.server.level.ServerLevel level, ChunkGenerator generator, BlockPos pos) {
      level.registryAccess()
         .lookup(net.minecraft.core.registries.Registries.FEATURE)
         .flatMap(registry -> registry.get(net.minecraft.data.worldgen.features.MiscOverworldFeatures.BONUS_CHEST))
         .ifPresent(feature -> feature.value().place(level, generator, level.getRandom(), pos));
   }

   public static BiomeResolver biomeResolver(BiomeSource source, RandomState random) {
      return source.createUncachedResolver(random);
   }

   public static void fillBiomes(ChunkAccess chunk, BiomeResolver resolver, RandomState random) {
      chunk.fillBiomesFromNoise(resolver);
   }

   public static Holder<Biome> sampleBiome(BiomeSource source, int x, int y, int z, RandomState random) {
      return source.createUncachedResolver(random).getNoiseBiome(x, y, z);
   }

   public static void spawnMobs(WorldGenRegion level, ChunkPos center, RandomSource random) {
      NaturalSpawner.spawnMobsForChunkGeneration(level, center.getWorldPosition().atY(level.getMaxY()), center, random);
   }

   public static List<StructureStart> structureStarts(StructureManager structures, ChunkPos pos, Predicate<Structure> predicate) {
      return structures.startsForStructure(pos.x(), pos.z(), predicate);
   }

   public static TreeFeatureHandle primaryFeature(PlacedFeature placed) {
      return new TreeFeatureHandle(placed.feature().value());
   }

   public static Stream<TreeFeatureHandle> features(PlacedFeature placed) {
      return placed.getFeatures().map(feature -> new TreeFeatureHandle(MinecraftVersionCompat.unwrapConfiguredFeature(feature)));
   }

   public record TreeFeatureHandle(Feature value) {
      public boolean isTree() {
         Feature type = this.value;
         return type instanceof TreeFeature || type instanceof FallenTreeFeature || type instanceof HugeBrownMushroomFeature || type instanceof HugeRedMushroomFeature;
      }

      public boolean isHugeRedMushroom() {
         return this.value instanceof HugeRedMushroomFeature;
      }

      public void place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos) {
         this.value.place(level, generator, random, pos);
      }
   }
}
