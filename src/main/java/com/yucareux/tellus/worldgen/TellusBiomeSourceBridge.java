package com.yucareux.tellus.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;

/** Keeps biome resolver signatures outside the shared geographic biome logic. */
public abstract class TellusBiomeSourceBridge extends BiomeSource {
   @FunctionalInterface
   protected interface ColumnResolver {
      Holder<Biome> resolve(int x, int y, int z);
   }

   protected BiomeResolver chunkResolver(ColumnResolver resolver) {
      return (x, y, z, sampler) -> resolver.resolve(x, y, z);
   }
}
