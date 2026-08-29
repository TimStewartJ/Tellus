package com.yucareux.tellus.integration.geotp;

import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class GeoTeleportVersionCompat {
   private GeoTeleportVersionCompat() {
   }

   public static void requestFullChunk(
      ServerLevel level, int chunkX, int chunkZ, BiConsumer<Boolean, Throwable> completion
   ) {
      level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true).whenComplete((result, error) -> {
         if (error != null || result == null || !result.isSuccess()) {
            completion.accept(false, error);
         } else {
            completion.accept(true, null);
         }
      });
   }
}
