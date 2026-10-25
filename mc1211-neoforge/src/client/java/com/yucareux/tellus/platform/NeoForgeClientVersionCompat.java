package com.yucareux.tellus.platform;

import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** NeoForge key category registration for Minecraft 1.21.1. */
public final class NeoForgeClientVersionCompat {
   private NeoForgeClientVersionCompat() {
   }

   public static String keyCategory() {
      return "key.category.tellus.controls";
   }

   public static void registerKeyCategory(RegisterKeyMappingsEvent event) {
      // 1.21.1 key categories are plain translation keys, so there is nothing to register.
   }
}
