package com.yucareux.tellus.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/** Version-specific feature and carver types; other 26.x APIs are shared. */
public final class MinecraftVersionCompat extends Minecraft26Compat {
   private MinecraftVersionCompat() {}

   public static HolderSet<Block> overworldCarverReplaceables(Registry<Block> blockRegistry) {
      return blockRegistry.getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
   }

   public static DensityFunctions.BeardifierOrMarker emptyBeardifier() {
      return Beardifier.EMPTY;
   }

   @SuppressWarnings("unchecked")
   public static ConfiguredFeature<?, ?> unwrapConfiguredFeature(Object configuredFeature) {
      return ((Holder<ConfiguredFeature<?, ?>>)configuredFeature).value();
   }

   public static CarvingMask carvingMask(ProtoChunk chunk) {
      return chunk.getCarvingMask();
   }

   public static CarvingMask getOrCreateCarvingMask(ProtoChunk chunk) {
      return chunk.getOrCreateCarvingMask();
   }

   public static void setCarvingMask(ProtoChunk chunk, CarvingMask mask) {
      chunk.setCarvingMask(mask);
   }

}
