package com.yucareux.tellus.test;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

public final class MinecraftTestRegistries {
   private MinecraftTestRegistries() {}

   public static HolderLookup.Provider create() {
      return VanillaRegistries.createLookup();
   }
}
