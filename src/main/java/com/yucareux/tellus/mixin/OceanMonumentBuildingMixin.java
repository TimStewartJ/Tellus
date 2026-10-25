package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public abstract class OceanMonumentBuildingMixin extends StructurePiece {
   @Shadow
   @Final
   private List<StructurePiece> childPieces;

   protected OceanMonumentBuildingMixin(StructurePieceType type, int genDepth, BoundingBox boundingBox) {
      super(type, genDepth, boundingBox);
   }

   // Vanilla never moves monuments, so its move() only shifts the shell and leaves every room at the vanilla Y.
   @Override
   public void move(int x, int y, int z) {
      super.move(x, y, z);
      for (StructurePiece child : this.childPieces) {
         child.move(x, y, z);
      }
   }

   @Redirect(
      method = "postProcess",
      at = @At(
         value = "INVOKE",
         target = "Ljava/lang/Math;max(II)I",
         remap = false
      )
   )
   private int tellus$useTellusSeaLevel(
      int seaLevel,
      int minimumSeaLevel,
      WorldGenLevel level,
      StructureManager structureManager,
      ChunkGenerator generator,
      RandomSource random,
      BoundingBox box,
      ChunkPos chunkPos,
      BlockPos pos
   ) {
      return generator instanceof EarthChunkGenerator ? seaLevel : Math.max(seaLevel, minimumSeaLevel);
   }
}
