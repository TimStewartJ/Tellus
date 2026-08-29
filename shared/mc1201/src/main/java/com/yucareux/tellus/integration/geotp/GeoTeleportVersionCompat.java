package com.yucareux.tellus.integration.geotp;

import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkStatus;

public final class GeoTeleportVersionCompat {
   private GeoTeleportVersionCompat() {
   }

   public static void requestFullChunk(
      ServerLevel level, int chunkX, int chunkZ, BiConsumer<Boolean, Throwable> completion
   ) {
      level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenComplete((result, error) -> {
         completion.accept(error == null && result != null && result.left().orElse(null) != null, error);
      });
   }
}
