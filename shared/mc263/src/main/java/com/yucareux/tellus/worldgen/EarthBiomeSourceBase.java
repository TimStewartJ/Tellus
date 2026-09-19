package com.yucareux.tellus.worldgen;

import com.mojang.datafixers.util.Pair;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Binds {@link EarthBiomeSource} to the biome source entry points of Minecraft 26.3, which hands out
 * resolvers instead of passing a climate sampler to every biome lookup.
 */
abstract class EarthBiomeSourceBase extends BiomeSource {
   abstract Holder<Biome> resolveNoiseBiome(int quartX, int quartY, int quartZ);

   abstract Pair<BlockPos, Holder<Biome>> locateClosestBiome3d(
      BlockPos origin, int radius, int horizontalInterval, Predicate<Holder<Biome>> predicate
   );

   static BiomeResolver resolver(QuartBiomeLookup lookup) {
      return lookup::resolve;
   }

   @Override
   public BiomeResolver createResolver(Sampler sampler) {
      return this::resolveNoiseBiome;
   }

   @Override
   public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
      BlockPos origin,
      int radius,
      int horizontalInterval,
      int verticalInterval,
      Predicate<Holder<Biome>> predicate,
      RandomState randomState,
      LevelReader level
   ) {
      return this.locateClosestBiome3d(origin, radius, horizontalInterval, predicate);
   }

   @FunctionalInterface
   interface QuartBiomeLookup {
      Holder<Biome> resolve(int quartX, int quartY, int quartZ);
   }
}