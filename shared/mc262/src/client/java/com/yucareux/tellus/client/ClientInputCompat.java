package com.yucareux.tellus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/** Keeps GLFW/SDL input and platform calls outside shared screens. */
public final class ClientInputCompat {
   private ClientInputCompat() {}

   public static boolean isShiftDown() {
      return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 340) || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 344);
   }

   public static boolean isAcceptKey(int key) {
      return key == 257 || key == 335;
   }

   public static void openUri(String uri) {
      Util.getPlatform().openUri(uri);
   }

   public static KeyMapping openMapKey(KeyMapping.Category category) {
      return new KeyMapping("key.tellus.open_map", InputConstants.Type.KEYSYM, 77, category);
   }
}
