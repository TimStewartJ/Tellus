package com.yucareux.tellus.world.data.biome;

import java.util.ServiceLoader;

/** NeoForge providers must be resolved through the game's transforming loader. */
final class BiomeProviderDiscovery {
   private BiomeProviderDiscovery() {}

   static ServiceLoader<BiomeClassificationProvider> load() {
      return ServiceLoader.load(BiomeClassificationProvider.class, BiomeClassificationProvider.class.getClassLoader());
   }
}
