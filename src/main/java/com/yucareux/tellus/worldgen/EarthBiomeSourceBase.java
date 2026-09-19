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

/**
 * Binds {@link EarthBiomeSource} to the biome source entry points of the running Minecraft version.
 * Minecraft 26.3 replaced per-call climate samplers with resolvers and compiles its own copy of this class.
 */
abstract class EarthBiomeSourceBase extends BiomeSource {
   abstract Holder<Biome> resolveNoiseBiome(int quartX, int quartY, int quartZ);

   abstract Pair<BlockPos, Holder<Biome>> locateClosestBiome3d(
      BlockPos origin, int radius, int horizontalInterval, Predicate<Holder<Biome>> predicate
   );

   static BiomeResolver resolver(QuartBiomeLookup lookup) {
      return (quartX, quartY, quartZ, sampler) -> lookup.resolve(quartX, quartY, quartZ);
   }

   @Override
   public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Sampler sampler) {
      return this.resolveNoiseBiome(quartX, quartY, quartZ);
   }

   @Override
   public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
      BlockPos origin,
      int radius,
      int horizontalInterval,
      int verticalInterval,
      Predicate<Holder<Biome>> predicate,
      Sampler sampler,
      LevelReader level
   ) {
      return this.locateClosestBiome3d(origin, radius, horizontalInterval, predicate);
   }

   @FunctionalInterface
   interface QuartBiomeLookup {
      Holder<Biome> resolve(int quartX, int quartY, int quartZ);
   }
}