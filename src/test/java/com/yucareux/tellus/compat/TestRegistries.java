package com.yucareux.tellus.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

/** Vanilla registry lookup for tests. Minecraft 26.3 renamed the factory and compiles its own copy of this class. */
public final class TestRegistries {
   private TestRegistries() {
   }

   public static HolderLookup.Provider vanilla() {
      return VanillaRegistries.createLookup();
   }
}