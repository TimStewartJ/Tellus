package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.LoadingCache;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** Supplies a planar DEM around the equator without downloading terrain data. */
public final class TerrainSlopeFixture implements AutoCloseable {
   private final TellusElevationSource source;
   private final LoadingCache<Object, ShortRaster> cache;
   private final Map<Object, ShortRaster> previousTiles = new HashMap<>();

   @SuppressWarnings("unchecked")
   public TerrainSlopeFixture(TellusElevationSource source, double worldScale, double slopeDegrees) throws Exception {
      this.source = source;
      Field cacheField = TellusElevationSource.class.getDeclaredField("cache");
      cacheField.setAccessible(true);
      this.cache = (LoadingCache<Object, ShortRaster>)cacheField.get(source);
      Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
      Constructor<?> keyConstructor = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
      keyConstructor.setAccessible(true);
      int zoom = TellusElevationSource.selectPreviewZoom(worldScale, worldScale, 18);
      int originTile = 1 << (zoom - 1);
      double metersPerPixel = 40075017.0 / ((1 << zoom) * 512.0);
      double risePerPixel = Math.tan(Math.toRadians(slopeDegrees)) * metersPerPixel;
      for (int tileZ = -1; tileZ <= 0; tileZ++) {
         for (int tileX = -1; tileX <= 0; tileX++) {
            Object key = keyConstructor.newInstance(zoom, originTile + tileX, originTile + tileZ);
            this.previousTiles.put(key, this.cache.getIfPresent(key));
            ShortRaster raster = ShortRaster.create(512, 512);
            for (int z = 0; z < 512; z++) {
               for (int x = 0; x < 512; x++) {
                  raster.set(x, z, (short)Math.round(4000.0 + (tileX * 512 + x) * risePerPixel));
               }
            }
            this.cache.put(key, raster);
         }
      }
      // Tiles were replaced behind the source's back; it must not serve the ones it read last.
      source.forgetRecentTiles();
   }

   @Override
   public void close() {
      this.previousTiles.forEach((key, previous) -> {
         if (previous == null) {
            this.cache.invalidate(key);
         } else {
            this.cache.put(key, previous);
         }
      });
      this.source.forgetRecentTiles();
   }
}
