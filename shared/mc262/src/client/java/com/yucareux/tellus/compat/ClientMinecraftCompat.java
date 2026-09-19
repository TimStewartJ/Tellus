package com.yucareux.tellus.compat;

import net.minecraft.util.Util;

/** Minecraft 26.2 client bridge. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static void openUri(String uri) {
      Util.getPlatform().openUri(uri);
   }
}
