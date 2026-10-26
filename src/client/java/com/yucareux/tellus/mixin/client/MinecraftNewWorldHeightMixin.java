package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.worldgen.NewWorldHeightAlignment;
import net.minecraft.client.Minecraft;
import net.minecraft.server.WorldStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Minecraft.class)
public abstract class MinecraftNewWorldHeightMixin {
   // Every singleplayer world reaches the integrated server here, including ones whose Customize changes a data pack reload undid.
   @ModifyVariable(
      method = "doWorldLoad",
      at = @At("HEAD"),
      argsOnly = true
   )
   private WorldStem tellus$alignNewEarthWorld(WorldStem worldStem) {
      return NewWorldHeightAlignment.align(worldStem);
   }
}
