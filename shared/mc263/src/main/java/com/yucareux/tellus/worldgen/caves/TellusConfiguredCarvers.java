package com.yucareux.tellus.worldgen.caves;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

final class TellusConfiguredCarvers {
   private static final float CAVE_PROBABILITY = 0.15F;
   private static final float CAVE_EXTRA_PROBABILITY = 0.07F;
   private static final float CANYON_PROBABILITY = 0.01F;
   private final int tellusMinY;
   private final int tellusMaxY;
   private final int undergroundDepth;
   private final ConcurrentMap<Integer, SurfaceCarvers> carversBySurface = new ConcurrentHashMap<>();

   private TellusConfiguredCarvers(int tellusMinY, int tellusHeight, int undergroundDepth) {
      this.tellusMinY = tellusMinY;
      this.tellusMaxY = tellusMinY + Math.max(1, tellusHeight) - 1;
      this.undergroundDepth = Math.max(0, undergroundDepth);
   }

   static TellusConfiguredCarvers create(int tellusMinY, int tellusHeight, int undergroundDepth) {
      return new TellusConfiguredCarvers(tellusMinY, tellusHeight, undergroundDepth);
   }

   private SurfaceCarvers createForSurface(int surfaceY) {
      int caveFloorY = Math.max(this.tellusMinY, surfaceY - this.undergroundDepth + 1);
      int caveCeilingY = Math.max(caveFloorY, Math.min(this.tellusMaxY, surfaceY - 1));
      int caveExtraCeilingY = Math.max(caveFloorY, caveCeilingY - 16);
      int canyonFloorY = Math.max(caveFloorY, caveCeilingY - 96);
      int canyonCeilingY = caveCeilingY;
      int lavaLevelY = Math.min(caveCeilingY, caveFloorY + 8);

      TellusCaveCarver cave = new TellusCaveCarver(
         CAVE_PROBABILITY,
         UniformHeight.of(VerticalAnchor.absolute(caveFloorY), VerticalAnchor.absolute(caveCeilingY)),
         UniformFloat.of(0.1F, 0.9F),
         UniformFloat.of(0.7F, 1.4F),
         UniformFloat.of(0.8F, 1.3F),
         UniformFloat.of(-1.0F, -0.4F)
      );
      TellusCaveCarver caveExtraUnderground = new TellusCaveCarver(
         CAVE_EXTRA_PROBABILITY,
         UniformHeight.of(VerticalAnchor.absolute(caveFloorY), VerticalAnchor.absolute(caveExtraCeilingY)),
         UniformFloat.of(0.1F, 0.9F),
         UniformFloat.of(0.7F, 1.4F),
         UniformFloat.of(0.8F, 1.3F),
         UniformFloat.of(-1.0F, -0.4F)
      );
      CanyonWorldCarver.Shape canyonShape = new CanyonWorldCarver.Shape(
         UniformFloat.of(0.75F, 1.0F),
         TrapezoidFloat.of(0.0F, 6.0F, 2.0F),
         3,
         UniformFloat.of(0.75F, 1.0F),
         1.0F,
         0.0F,
         ConstantFloat.of(3.0F)
      );
      TellusRavineCarver canyon = new TellusRavineCarver(
         CANYON_PROBABILITY,
         UniformHeight.of(VerticalAnchor.absolute(canyonFloorY), VerticalAnchor.absolute(canyonCeilingY)),
         UniformFloat.of(-0.125F, 0.125F),
         canyonShape
      );
      return new SurfaceCarvers(List.of(cave.carver(), caveExtraUnderground.carver(), canyon.carver()), lavaLevelY);
   }

   SurfaceCarvers orderedCarvers(int surfaceY) {
      int boundedSurfaceY = Math.max(this.tellusMinY, Math.min(this.tellusMaxY, surfaceY));
      return this.carversBySurface.computeIfAbsent(boundedSurfaceY, this::createForSurface);
   }

   /** Minecraft 26.3 carvers no longer carry a lava level, so the runner applies the one these carvers had in 26.2. */
   record SurfaceCarvers(List<WorldCarver> carvers, int lavaLevelY) {
   }
}
