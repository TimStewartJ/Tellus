package com.yucareux.tellus.compat;

import com.mojang.blaze3d.Blaze3D;
import com.yucareux.tellus.Tellus;
import java.net.URI;
import java.net.URISyntaxException;

/** Minecraft 26.3 client bridge. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static void openUri(String uri) {
      try {
         Blaze3D.openUri(new URI(uri));
      } catch (URISyntaxException error) {
         Tellus.LOGGER.error("Couldn't open uri '{}'", uri, error);
      }
   }
}
