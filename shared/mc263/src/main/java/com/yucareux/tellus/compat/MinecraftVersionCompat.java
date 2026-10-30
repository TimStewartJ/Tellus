package com.yucareux.tellus.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Version-specific feature and carver types; other 26.x APIs are shared. */
public final class MinecraftVersionCompat extends Minecraft26Compat {
   private MinecraftVersionCompat() {}

   public static HolderSet<Block> overworldCarverReplaceables(Registry<Block> blockRegistry) {
      return HolderSet.direct(blockRegistry.listElements().filter(block -> !block.is(BlockTags.UNCARVABLE)).toList());
   }

   public static Beardifier emptyBeardifier() {
      return Beardifier.EMPTY;
   }

   public static boolean hasAbandonedCamps() {
      return true;
   }

   @SuppressWarnings("unchecked")
   public static Feature unwrapConfiguredFeature(Object configuredFeature) {
      return ((Holder<Feature>)configuredFeature).value();
   }
}
