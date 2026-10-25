package com.yucareux.tellus.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.Blaze3D;
import java.net.URI;

/** Keeps GLFW/SDL input and platform calls outside shared screens. */
public final class ClientInputCompat {
   private ClientInputCompat() {}

   public static boolean isShiftDown() {
      return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
   }

   public static boolean isAcceptKey(int key) {
      return key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;
   }

   public static void openUri(String uri) {
      Blaze3D.openUri(URI.create(uri));
   }

   public static KeyMapping openMapKey(KeyMapping.Category category) {
      return new KeyMapping("key.tellus.open_map", InputConstants.KEY_M, category);
   }
}
