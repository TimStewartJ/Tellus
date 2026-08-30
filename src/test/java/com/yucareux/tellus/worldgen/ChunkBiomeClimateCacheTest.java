package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class ChunkBiomeClimateCacheTest {
   @Test
   void samplesEverySurfaceColumnAtItsExactCoordinate() {
      ChunkPos pos = new ChunkPos(-2, 3);
      EarthChunkGenerator.ChunkBiomeClimateCache cache =
         new EarthChunkGenerator.ChunkBiomeClimateCache(pos, 1.0);
      List<String> sampled = new ArrayList<>();
      EarthChunkGenerator.ClimateSampler sampler = (blockX, blockZ, worldScale) -> {
         String value = blockX + ":" + blockZ + ":" + worldScale;
         sampled.add(value);
         return value;
      };

      String first = cache.resolve(pos.getMinBlockX(), pos.getMinBlockZ(), sampler);
      String adjacent = cache.resolve(pos.getMinBlockX() + 1, pos.getMinBlockZ(), sampler);

      assertNotEquals(first, adjacent);
      assertEquals(List.of(first, adjacent), sampled);
      assertEquals(0, cache.hitCount());
      assertEquals(2, cache.missCount());
   }

   @Test
   void cachesOnlyRepeatedRequestsForTheSameColumn() {
      ChunkPos pos = new ChunkPos(-1, -1);
      EarthChunkGenerator.ChunkBiomeClimateCache cache =
         new EarthChunkGenerator.ChunkBiomeClimateCache(pos, 30.0);
      List<String> sampled = new ArrayList<>();
      EarthChunkGenerator.ClimateSampler sampler = (blockX, blockZ, worldScale) -> {
         String value = blockX + ":" + blockZ;
         sampled.add(value);
         return value;
      };

      for (int localZ = 0; localZ < 4; localZ++) {
         for (int localX = 0; localX < 4; localX++) {
            cache.resolve(pos.getMinBlockX() + localX, pos.getMinBlockZ() + localZ, sampler);
         }
      }
      cache.resolve(pos.getMinBlockX(), pos.getMinBlockZ(), sampler);

      assertEquals(16, sampled.size());
      assertEquals(1, cache.hitCount());
      assertEquals(16, cache.missCount());
   }

   @Test
   void indexesAllChunkColumnsUniquely() {
      boolean[] seen = new boolean[256];
      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localX = 0; localX < 16; localX++) {
            int index = EarthChunkGenerator.ChunkBiomeClimateCache.cellIndex(localX, localZ);
            assertFalse(seen[index]);
            seen[index] = true;
         }
      }
   }
}
