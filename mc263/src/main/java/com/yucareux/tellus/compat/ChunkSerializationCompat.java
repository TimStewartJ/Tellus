package com.yucareux.tellus.compat;

import java.util.List;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SerializableChunkData.SectionData;

/** Native chunk-data record construction; the high-Y repair algorithm is shared. */
public final class ChunkSerializationCompat {
   private ChunkSerializationCompat() {}

   public static SerializableChunkData withSections(SerializableChunkData original, List<SectionData> repairedSections) {
      return new SerializableChunkData(
            original.containerFactory(),
            original.chunkPos(),
            original.minSectionY(),
            original.lastUpdateTime(),
            original.inhabitedTime(),
            original.chunkStatus(),
            original.blendingData(),
            original.belowZeroRetrogen(),
            original.upgradeData(),
            original.heightmaps(),
            original.packedTicks(),
            original.postProcessingSections(),
            original.lightCorrect(),
            repairedSections,
            original.entities(),
            original.blockEntities(),
            original.structureData()
         );
   }
}
