package com.yucareux.tellus.world.data.biome;

import java.util.ServiceLoader;

/** Loader-specific service discovery; provider selection stays shared. */
final class BiomeProviderDiscovery {
   private BiomeProviderDiscovery() {}

   static ServiceLoader<BiomeClassificationProvider> load() {
      return ServiceLoader.load(BiomeClassificationProvider.class);
   }
}
