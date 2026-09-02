package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.api.detail.ChunkDetailWriter;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class ChunkDetailWorldWriter implements ChunkDetailWriter {
   private final WorldGenLevel level;
   private final int chunkX;
   private final int chunkZ;
   private final int minY;
   private final int maxY;

   ChunkDetailWorldWriter(WorldGenLevel level, int chunkX, int chunkZ, int minY, int maxY) {
      this.level = Objects.requireNonNull(level, "level");
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.minY = minY;
      this.maxY = maxY;
   }

   @Override
   public int chunkX() {
      return this.chunkX;
   }

   @Override
   public int chunkZ() {
      return this.chunkZ;
   }

   @Override
   public int minY() {
      return this.minY;
   }

   @Override
   public int maxY() {
      return this.maxY;
   }

   @Override
   public int surfaceY(int localX, int localZ) {
      this.checkLocal(localX, localZ);
      return this.level.getHeight(
         Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
         this.chunkX * 16 + localX,
         this.chunkZ * 16 + localZ
      ) - 1;
   }

   @Override
   public BlockState blockState(int localX, int blockY, int localZ) {
      return this.level.getBlockState(this.position(localX, blockY, localZ));
   }

   @Override
   public boolean hasBlockEntity(int localX, int blockY, int localZ) {
      return this.level.getBlockEntity(this.position(localX, blockY, localZ)) != null;
   }

   @Override
   public boolean setBlock(int localX, int blockY, int localZ, BlockState state, int flags) {
      Objects.requireNonNull(state, "state");
      BlockPos position = this.position(localX, blockY, localZ);
      return this.level.ensureCanWrite(position) && this.level.setBlock(position, state, flags);
   }

   private BlockPos position(int localX, int blockY, int localZ) {
      this.checkLocal(localX, localZ);
      if (blockY < this.minY || blockY > this.maxY) {
         throw new IndexOutOfBoundsException("Chunk-detail Y outside build range: " + blockY);
      }
      BlockPos position = new BlockPos(
         this.chunkX * 16 + localX,
         blockY,
         this.chunkZ * 16 + localZ
      );
      if (!MinecraftVersionCompat.isInsideBuildHeight(this.level, position)) {
         throw new IndexOutOfBoundsException("Chunk-detail Y outside level build range: " + blockY);
      }
      return position;
   }

   private void checkLocal(int localX, int localZ) {
      if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
         throw new IndexOutOfBoundsException("Chunk-detail writer is owner-chunk-only: " + localX + "," + localZ);
      }
   }
}
