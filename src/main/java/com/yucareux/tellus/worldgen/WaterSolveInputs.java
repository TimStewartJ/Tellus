package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Block inputs of the coastal water region solve, kept in 64-block tiles and shared by every solve that
 * reads them. A solve covers its 64-block region plus a margin of up to 512 blocks (1:1), so each block's
 * DEM surface and bathymetry were sampled again by each of up to 289 regions around it: nine tenths of
 * a coastal solve's time. Tiles hold exactly what the samplers return; a tile with an unavailable DEM
 * sample serves the solve that read it but is not kept, so a later solve samples it again as before.
 */
final class WaterSolveInputs {
   static final int SHIFT = 6;
   static final int SIZE = 1 << SHIFT;
   static final int MASK = SIZE - 1;
   static final int AREA = SIZE * SIZE;
   private static final int RECENT = 64;

   /** Samples one block's surface into a tile; returns false when its DEM sample was unavailable. */
   interface SurfaceSampler {
      boolean sample(int x, int z, SurfaceTile tile, int index);
   }

   private final Cache<Long, SurfaceTile> surfaces;
   private final AtomicReferenceArray<SurfaceTile> recentSurfaces = new AtomicReferenceArray<>(RECENT);
   private final Cache<Long, DepthTile> depths;
   private final AtomicReferenceArray<DepthTile> recentDepths = new AtomicReferenceArray<>(RECENT);
   private final AtomicLong generation = new AtomicLong();

   WaterSolveInputs(int surfaceTiles, int depthTiles) {
      this.surfaces = CacheBuilder.newBuilder().maximumSize(surfaceTiles).build();
      this.depths = CacheBuilder.newBuilder().maximumSize(depthTiles).build();
   }

   void clear() {
      this.generation.incrementAndGet();
      for (int slot = 0; slot < RECENT; slot++) {
         this.recentSurfaces.set(slot, null);
         this.recentDepths.set(slot, null);
      }
      this.surfaces.invalidateAll();
      this.depths.invalidateAll();
   }

   /** The surface tile at tile coordinates (tx, tz), sampled now if no complete copy is kept. */
   SurfaceTile surface(int tx, int tz, SurfaceSampler sampler) {
      long generation = this.generation.get();
      long key = key(tx, tz);
      int slot = slot(key);
      SurfaceTile recent = this.recentSurfaces.get(slot);
      if (recent != null && recent.tx == tx && recent.tz == tz && recent.generation == generation) {
         return recent;
      }
      SurfaceTile tile;
      try {
         tile = this.surfaces.get(key, () -> SurfaceTile.sample(tx, tz, generation, sampler));
      } catch (ExecutionException | com.google.common.util.concurrent.UncheckedExecutionException error) {
         if (error.getCause() instanceof RuntimeException cause) throw cause;
         if (error.getCause() instanceof Error cause) throw cause;
         throw new IllegalStateException("Unable to sample water solve surfaces", error.getCause());
      }
      if (!tile.complete || tile.generation != generation) {
         this.surfaces.asMap().remove(key, tile);
      } else {
         this.recentSurfaces.set(slot, tile);
      }
      return tile;
   }

   /** The bathymetry tile at tile coordinates (tx, tz); its blocks are sampled as they are first read. */
   DepthTile depth(int tx, int tz) {
      long generation = this.generation.get();
      long key = key(tx, tz);
      int slot = slot(key);
      DepthTile recent = this.recentDepths.get(slot);
      if (recent != null && recent.tx == tx && recent.tz == tz && recent.generation == generation) {
         return recent;
      }
      DepthTile tile;
      try {
         tile = this.depths.get(key, () -> new DepthTile(tx, tz, generation));
      } catch (ExecutionException error) {
         throw new IllegalStateException("Unable to allocate a bathymetry tile", error.getCause());
      }
      if (tile.generation == generation) {
         this.recentDepths.set(slot, tile);
      }
      return tile;
   }

   static long key(int tx, int tz) {
      return (long)tx & 0xFFFFFFFFL | ((long)tz & 0xFFFFFFFFL) << 32;
   }

   private static int slot(long key) {
      return (int)(HydrologyBlockCache.mix(key) >>> 58);
   }

   static int index(int x, int z) {
      return (z & MASK) << SHIFT | x & MASK;
   }

   static final class SurfaceTile {
      final int tx;
      final int tz;
      final long generation;
      final int[] heights = new int[AREA];
      final long[] landOverride = new long[AREA / 64];
      private boolean complete = true;

      private SurfaceTile(int tx, int tz, long generation) {
         this.tx = tx;
         this.tz = tz;
         this.generation = generation;
      }

      private static SurfaceTile sample(int tx, int tz, long generation, SurfaceSampler sampler) {
         SurfaceTile tile = new SurfaceTile(tx, tz, generation);
         int minX = tx << SHIFT;
         int minZ = tz << SHIFT;
         for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
               tile.complete &= sampler.sample(minX + x, minZ + z, tile, z << SHIFT | x);
            }
         }
         return tile;
      }

      void set(int index, int height, boolean landOverride) {
         this.heights[index] = height;
         if (landOverride) {
            this.landOverride[index >>> 6] |= 1L << (index & 63);
         }
      }

      boolean landOverride(int index) {
         return (this.landOverride[index >>> 6] & 1L << (index & 63)) != 0L;
      }

      boolean complete() {
         return this.complete;
      }
   }

   /**
    * Raw ocean depth per block, filled as blocks are first read: valid bathymetry as its depth, a definitive
    * invalid sample as minus its depth (every depth is at least one block), 0 while unknown. Concurrent readers
    * may sample a block twice; both store the same value.
    */
   static final class DepthTile {
      final int tx;
      final int tz;
      final long generation;
      final int[] encoded = new int[AREA];

      private DepthTile(int tx, int tz, long generation) {
         this.tx = tx;
         this.tz = tz;
         this.generation = generation;
      }
   }
}
