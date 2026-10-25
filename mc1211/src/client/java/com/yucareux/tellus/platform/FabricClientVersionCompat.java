package com.yucareux.tellus.platform;

import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/** Fabric client key category and HUD registration for Minecraft 1.21.1. */
public final class FabricClientVersionCompat {
   private FabricClientVersionCompat() {
   }

   public static String keyCategory() {
      return "key.category.tellus.controls";
   }

   public static void registerHud() {
      HudRenderCallback.EVENT.register((graphics, deltaTracker) -> ManagedTerrainDownloadOverlay.render(graphics));
   }
}
