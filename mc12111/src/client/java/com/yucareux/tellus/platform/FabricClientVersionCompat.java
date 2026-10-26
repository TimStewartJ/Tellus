package com.yucareux.tellus.platform;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;

/** Fabric client key category and HUD registration for Minecraft 1.21.11. */
public final class FabricClientVersionCompat {
   private FabricClientVersionCompat() {
   }

   public static KeyMapping.Category keyCategory() {
      return KeyMapping.Category.register(Tellus.id("controls"));
   }

   public static void registerHud() {
      HudElementRegistry.addLast(Tellus.id("managed_terrain_status"), (graphics, deltaTracker) -> ManagedTerrainDownloadOverlay.render(graphics));
   }
}
