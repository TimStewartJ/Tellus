package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.NewWorldHeightAlignment;
import net.minecraft.server.Main;
import net.minecraft.server.WorldStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Main.class)
public abstract class ServerMainNewWorldHeightMixin {
   @ModifyVariable(
      method = "main",
      at = @At("STORE")
   )
   private static WorldStem tellus$alignNewEarthWorld(WorldStem worldStem) {
      return NewWorldHeightAlignment.align(worldStem);
   }
}
