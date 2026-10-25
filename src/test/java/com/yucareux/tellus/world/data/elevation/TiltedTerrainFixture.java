package com.yucareux.tellus.world.data.elevation;

import com.google.common.cache.LoadingCache;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Supplies a planar DEM around any point, falling one way at a given slope, without downloading
 * terrain. Tiles are filled at the zooms full chunks and the given LOD resolutions read.
 */
public final class TiltedTerrainFixture implements AutoCloseable {
   private static final int TILE_SIZE = 512;
   private static final double EQUATOR_METERS = 40075017.0;
   private static final double CENTER_ELEVATION_METERS = 3000.0;
   private final TellusElevationSource source;
   private final LoadingCache<Object, ShortRaster> cache;
   private final Map<Object, ShortRaster> previousTiles = new HashMap<>();

   @SuppressWarnings("unchecked")
   public TiltedTerrainFixture(
      TellusElevationSource source,
      double worldScale,
      double latitude,
      double longitude,
      double slopeDegrees,
      TiltedTerrainFixture.Falls falls,
      double... lodResolutionsMeters
   ) throws Exception {
      this.source = source;
      Field cacheField = TellusElevationSource.class.getDeclaredField("cache");
      cacheField.setAccessible(true);
      this.cache = (LoadingCache<Object, ShortRaster>)cacheField.get(source);
      Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
      Constructor<?> keyConstructor = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
      keyConstructor.setAccessible(true);
      TreeSet<Integer> zooms = new TreeSet<>();
      zooms.add(TellusElevationSource.selectPreviewZoom(worldScale, worldScale, 18));
      for (double resolution : lodResolutionsMeters) {
         zooms.add(TellusElevationSource.selectPreviewZoom(worldScale, resolution, 18));
      }

      double latitudeRadians = Math.toRadians(latitude);
      for (int zoom : zooms) {
         double tiles = 1 << zoom;
         double centerPixelX = (longitude + 180.0) / 360.0 * tiles * TILE_SIZE;
         double centerPixelY = (1.0 - Math.log(Math.tan(latitudeRadians) + 1.0 / Math.cos(latitudeRadians)) / Math.PI) / 2.0 * tiles * TILE_SIZE;
         double risePerPixel = Math.tan(Math.toRadians(slopeDegrees)) * EQUATOR_METERS * Math.cos(latitudeRadians) / (tiles * TILE_SIZE);
         int centerTileX = (int)Math.floor(centerPixelX / TILE_SIZE);
         int centerTileY = (int)Math.floor(centerPixelY / TILE_SIZE);
         for (int tileY = centerTileY - 1; tileY <= centerTileY + 1; tileY++) {
            for (int tileX = centerTileX - 1; tileX <= centerTileX + 1; tileX++) {
               Object key = keyConstructor.newInstance(zoom, tileX, tileY);
               this.previousTiles.put(key, this.cache.getIfPresent(key));
               ShortRaster raster = ShortRaster.create(TILE_SIZE, TILE_SIZE);
               for (int y = 0; y < TILE_SIZE; y++) {
                  for (int x = 0; x < TILE_SIZE; x++) {
                     double east = tileX * TILE_SIZE + x - centerPixelX;
                     double south = tileY * TILE_SIZE + y - centerPixelY;
                     double rise = switch (falls) {
                        case NORTH -> south;
                        case SOUTH -> -south;
                        case EAST -> -east;
                        case WEST -> east;
                     };
                     double elevation = CENTER_ELEVATION_METERS + rise * risePerPixel;
                     raster.set(x, y, (short)Math.round(Math.max(1.0, Math.min(Short.MAX_VALUE, elevation))));
                  }
               }
               this.cache.put(key, raster);
            }
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

   /** The way the ground falls; the plane rises the opposite way. */
   public enum Falls {
      NORTH,
      SOUTH,
      EAST,
      WEST
   }
}
