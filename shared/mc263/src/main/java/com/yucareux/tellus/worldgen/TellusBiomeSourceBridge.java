package com.yucareux.tellus.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import com.mojang.datafixers.util.Pair;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.levelgen.RandomState;

/** Keeps biome resolver signatures outside the shared geographic biome logic. */
public abstract class TellusBiomeSourceBridge extends BiomeSource {
   @FunctionalInterface
   protected interface ColumnResolver {
      Holder<Biome> resolve(int x, int y, int z);
   }

   protected BiomeResolver chunkResolver(ColumnResolver resolver) {
      return (x, y, z) -> resolver.resolve(x, y, z);
   }

   public abstract Holder<Biome> getNoiseBiome(int x, int y, int z, Sampler sampler);

   @Override
   public BiomeResolver createResolver(Sampler sampler) {
      return (x, y, z) -> this.getNoiseBiome(x, y, z, sampler);
   }

   public abstract Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
      BlockPos origin, int radius, int horizontalInterval, int verticalInterval,
      Predicate<Holder<Biome>> predicate, Sampler sampler, LevelReader level
   );

   @Override
   public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(
      BlockPos origin, int radius, int horizontalInterval, int verticalInterval,
      Predicate<Holder<Biome>> predicate, RandomState random, LevelReader level
   ) {
      return this.findClosestBiome3d(origin, radius, horizontalInterval, verticalInterval, predicate, (Sampler)null, level);
   }
}
