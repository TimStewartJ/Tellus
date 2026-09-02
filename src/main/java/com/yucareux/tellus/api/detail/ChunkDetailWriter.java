package com.yucareux.tellus.api.detail;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Mutable access restricted to one generated owner chunk.
 */
public interface ChunkDetailWriter {
   int chunkX();

   int chunkZ();

   int minY();

   int maxY();

   int surfaceY(int localX, int localZ);

   BlockState blockState(int localX, int blockY, int localZ);

   boolean hasBlockEntity(int localX, int blockY, int localZ);

   boolean setBlock(int localX, int blockY, int localZ, BlockState state, int flags);
}
