package com.yucareux.tellus.platform;

import com.yucareux.tellus.Tellus;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** NeoForge key category registration for Minecraft 1.21.11. */
public final class NeoForgeClientVersionCompat {
   private static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(Tellus.id("controls"));

   private NeoForgeClientVersionCompat() {
   }

   public static KeyMapping.Category keyCategory() {
      return KEY_CATEGORY;
   }

   public static void registerKeyCategory(RegisterKeyMappingsEvent event) {
      event.registerCategory(KEY_CATEGORY);
   }
}
