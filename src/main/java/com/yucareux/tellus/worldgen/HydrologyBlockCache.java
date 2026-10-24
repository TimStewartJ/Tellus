package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded memo of per-block values, grouped into 32x32 tiles. Hydrology reads
 * neighboring blocks together, so one tile lookup serves a whole neighborhood
 * instead of one hash lookup (and one eviction) per block. Like the per-block
 * caches it replaces, the first value published for a block is the one every
 * reader sees; concurrent loaders may compute a value twice but never publish
 * two different ones.
 */
final class HydrologyBlockCache<T> {
   private static final int SHIFT = 5;
   private static final int SIZE = 1 << SHIFT;
   private static final int MASK = SIZE - 1;
   private static final int RECENT = 64;
   private final Cache<Long, Tile<T>> tiles;
   // Recently used tiles, read without locking. A tile evicted from the bounded
   // cache can still be served from here; its published values remain valid.
   private final AtomicReferenceArray<Tile<T>> recent = new AtomicReferenceArray<>(RECENT);

   interface Loader<T> {
      T load(int x, int z);
   }

   HydrologyBlockCache(int maximumTiles) {
      this.tiles = CacheBuilder.newBuilder().maximumSize(maximumTiles).build();
   }

   T get(int x, int z, Loader<T> loader) {
      AtomicReferenceArray<T> values = this.tile(x >> SHIFT, z >> SHIFT).values;
      int index = (z & MASK) << SHIFT | x & MASK;
      T value = values.get(index);
      if (value != null) return value;
      value = loader.load(x, z);
      return values.compareAndSet(index, null, value) ? value : values.get(index);
   }

   private Tile<T> tile(int tx, int tz) {
      long key = mix(HydrologyWaterMap.key(tx, tz));
      int slot = (int)(key >>> 58);
      Tile<T> tile = this.recent.get(slot);
      if (tile != null && tile.tx == tx && tile.tz == tz) return tile;
      try {
         tile = this.tiles.get(key, () -> new Tile<>(tx, tz));
      } catch (ExecutionException error) {
         throw new IllegalStateException("Unable to allocate a hydrology cache tile", error.getCause());
      }
      this.recent.set(slot, tile);
      return tile;
   }

   /** A bijective mix, so distinct packed coordinates keep distinct, well-spread hash codes. */
   static long mix(long key) {
      key *= 0x9E3779B97F4A7C15L;
      return key ^ key >>> 29;
   }

   private static final class Tile<T> {
      final int tx, tz;
      final AtomicReferenceArray<T> values = new AtomicReferenceArray<>(SIZE * SIZE);

      Tile(int tx, int tz) {
         this.tx = tx;
         this.tz = tz;
      }
   }
}
