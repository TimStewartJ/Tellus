package com.yucareux.tellus.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

/** Vanilla registry lookup for tests on Minecraft 26.3. */
public final class TestRegistries {
   private TestRegistries() {
   }

   public static HolderLookup.Provider vanilla() {
      return VanillaRegistries.createWorldLookup();
   }
}