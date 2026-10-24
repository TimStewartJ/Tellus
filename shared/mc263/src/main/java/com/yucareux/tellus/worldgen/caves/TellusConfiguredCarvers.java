package com.yucareux.tellus.worldgen.caves;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.Registry;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.util.valueproviders.VeryBiasedToBottomInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

/** 26.3 stores each carver's settings on the carver itself. */
final class TellusConfiguredCarvers {
   private final int tellusMinY;
   private final int tellusMaxY;
   private final int undergroundDepth;
   private final ConcurrentMap<Integer, List<WorldCarver>> carversBySurface = new ConcurrentHashMap<>();

   private TellusConfiguredCarvers(int tellusMinY, int tellusHeight, int undergroundDepth) {
      this.tellusMinY = tellusMinY;
      this.tellusMaxY = tellusMinY + Math.max(1, tellusHeight) - 1;
      this.undergroundDepth = Math.max(0, undergroundDepth);
   }

   static TellusConfiguredCarvers create(Registry<Block> blockRegistry, int tellusMinY, int tellusHeight, int undergroundDepth) {
      return new TellusConfiguredCarvers(tellusMinY, tellusHeight, undergroundDepth);
   }

   private List<WorldCarver> createForSurface(int surfaceY) {
      int floor = Math.max(this.tellusMinY, surfaceY - this.undergroundDepth + 1);
      int ceiling = Math.max(floor, Math.min(this.tellusMaxY, surfaceY - 1));
      return List.of(
         cave(0.15F, floor, ceiling),
         cave(0.07F, floor, Math.max(floor, ceiling - 16)),
         new CanyonWorldCarver(
            0.01F,
            UniformHeight.of(VerticalAnchor.absolute(Math.max(floor, ceiling - 96)), VerticalAnchor.absolute(ceiling)),
            UniformFloat.of(-0.125F, 0.125F),
            new CanyonWorldCarver.Shape(
               UniformFloat.of(0.75F, 1.0F), TrapezoidFloat.of(0.0F, 6.0F, 2.0F), 3,
               UniformFloat.of(0.75F, 1.0F), 1.0F, 0.0F, ConstantFloat.of(3.0F)
            )
         )
      );
   }

   private static CaveWorldCarver cave(float probability, int floor, int ceiling) {
      return new CaveWorldCarver(
         probability, UniformHeight.of(VerticalAnchor.absolute(floor), VerticalAnchor.absolute(ceiling)),
         VeryBiasedToBottomInt.of(0, 14), TrapezoidFloat.of(0.0F, 3.0F, 1.0F), true,
         UniformFloat.of(0.1F, 0.9F), UniformFloat.of(0.7F, 1.4F), UniformFloat.of(0.8F, 1.3F),
         ConstantFloat.of(1.0F), UniformFloat.of(-1.0F, -0.4F)
      );
   }

   List<WorldCarver> orderedCarvers(int surfaceY) {
      int bounded = Math.max(this.tellusMinY, Math.min(this.tellusMaxY, surfaceY));
      return this.carversBySurface.computeIfAbsent(bounded, this::createForSurface);
   }
}
