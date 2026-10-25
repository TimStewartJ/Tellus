package com.yucareux.tellus.world.data.koppen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.SurfacePatchNoise;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutionException;
import java.util.zip.InflaterInputStream;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.util.Mth;

public final class TellusKoppenSource implements TellusCacheHandle {
   private static final double EQUATOR_CIRCUMFERENCE = 4.0075017E7;
   private static final double MIN_LAT = -90.0;
   private static final double MAX_LAT = 90.0;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final String RESOURCE_PATH = "tellus/koppen/koppen_geiger_0p00833333.tif";
   private static final double SEARCH_RADIUS_METERS = 5000.0;
   private static final int SMOOTH_RADIUS_PIXELS = 2;
   private static final double WARP_AMPLITUDE_METERS = 800.0;
   private static final double WARP_WAVELENGTH_METERS = 12000.0;
   /** The raster's 30" pixel, about 926 m: a fixed size keeps the warp independent of the loaded raster. */
   private static final double PIXEL_WARP_REFERENCE_METERS = 926.0;
   private static final double PIXEL_WARP_WAVELENGTH_PIXELS = 1.5;
   private static final double PIXEL_WARP_AMPLITUDE_PIXELS = 0.45;
   private static final long PIXEL_WARP_SALT = 0x7E3A15C9B42D86F1L;
   private static final long DITHER_SEED = 5883890050026909207L;
   private static final long WARP_SEED_X = 2611923443488327891L;
   private static final long WARP_SEED_Z = 1376283091369227076L;
   private static final String[] KOPPEN_CODES = new String[31];
   /** Tundra: warmest month below 10 °C. */
   public static final int CLASS_ET = 29;
   /** Ice cap: warmest month below 0 °C. */
   public static final int CLASS_EF = 30;
   private static final ThreadLocal<TellusKoppenSource.KoppenBlendScratch> DITHER_SCRATCH = ThreadLocal.withInitial(
      TellusKoppenSource.KoppenBlendScratch::new
   );
   private static final ThreadLocal<TellusKoppenSource.SmoothedValueCache> SMOOTHED_CACHE = ThreadLocal.withInitial(
      TellusKoppenSource.SmoothedValueCache::new
   );
   private final Path cachePath = TellusPlatform.gameDir().resolve("tellus/cache/koppen/koppen_geiger_0p00833333.tif");
   private volatile TellusKoppenSource.GeoTiffRaster raster;

   public TellusKoppenSource() {
      this.raster = this.loadRaster();
      TellusCacheRegistry.register(this);
   }

   public String sampleDitheredCode(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      TellusKoppenSource.PixelSample center = this.toPixelSample(blockX, blockZ, projection);
      if (center == null) {
         return null;
      } else {
         return raster == TellusKoppenSource.GeoTiffRaster.MISSING ? null : raster.sampleDithered(center, blockX, blockZ);
      }
   }

   public String sampleRawCode(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      TellusKoppenSource.Pixel center = this.toPixel(blockX, blockZ, projection);
      if (center == null) {
         return null;
      } else {
         return raster == TellusKoppenSource.GeoTiffRaster.MISSING ? null : raster.sample(center);
      }
   }

   public String sampleSmoothedCode(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      TellusKoppenSource.Pixel center = this.toPixel(blockX, blockZ, projection);
      if (center == null) {
         return null;
      } else {
         return raster == TellusKoppenSource.GeoTiffRaster.MISSING ? null : raster.sampleSmoothed(center, SMOOTH_RADIUS_PIXELS);
      }
   }

   /**
    * The regional climate: {@link #sampleSmoothedCode} without its 1 km staircase. The mode-filtered
    * classes of the surrounding pixels are dithered like {@link #sampleDitheredCode}, so a coherent
    * arid region ends in the same organic kind of border as the climate itself.
    */
   public String sampleCoherentCode(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      TellusKoppenSource.PixelSample center = this.toPixelSample(blockX, blockZ, projection);
      if (center == null) {
         return null;
      } else {
         return raster == TellusKoppenSource.GeoTiffRaster.MISSING
            ? null
            : raster.sampleSmoothedDithered(center, blockX, blockZ, SMOOTH_RADIUS_PIXELS);
      }
   }

   public String findNearestCode(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      TellusKoppenSource.Pixel center = this.toPixel(blockX, blockZ, projection);
      if (center == null) {
         return null;
      } else if (raster == TellusKoppenSource.GeoTiffRaster.MISSING) {
         return null;
      } else {
         int radius = raster.radiusForMeters(SEARCH_RADIUS_METERS);
         return raster.findNearest(center, radius);
      }
   }

   /** Raster geometry for scans over raw pixels, or null while the raster is unavailable. */
   public TellusKoppenSource.PixelGrid pixelGrid() {
      TellusKoppenSource.GeoTiffRaster raster = this.raster();
      return raster == TellusKoppenSource.GeoTiffRaster.MISSING
         ? null
         : new TellusKoppenSource.PixelGrid(raster.tieLon, raster.tieLat, raster.pixelScaleX, raster.pixelScaleY, raster.width, raster.height);
   }

   /** Class index of one raw pixel ({@link #CLASS_ET}, {@link #CLASS_EF}, ...), or 0 outside the data. */
   public int classIndexAtPixel(int pixelX, int pixelY) {
      return this.raster().sampleValue(pixelX, pixelY);
   }

   /**
    * Copies a window of raw class indices into {@code out}, row by row and {@code width} wide,
    * with 0 outside the data. Reads each raster tile once, for scans over many pixels.
    */
   public void readClassWindow(int pixelX, int pixelY, int width, int height, byte[] out) {
      this.raster().readWindow(pixelX, pixelY, width, height, out);
   }

   private TellusKoppenSource.Pixel toPixel(double blockX, double blockZ, WorldProjection projection) {
      TellusKoppenSource.PixelSample sample = this.toPixelSample(blockX, blockZ, projection);
      return sample == null ? null : new TellusKoppenSource.Pixel(sample.x(), sample.y());
   }

   private TellusKoppenSource.PixelSample toPixelSample(double blockX, double blockZ, WorldProjection projection) {
      double worldScale = projection.worldScale();
      if (worldScale <= 0.0) {
         return null;
      } else {
         TellusKoppenSource.GeoTiffRaster raster = this.raster();
         TellusKoppenSource.WarpedCoords warped = warpBlock(blockX, blockZ, worldScale);
         blockX = warped.x();
         blockZ = warped.z();
         int step = downsampleStep(worldScale, raster.pixelSizeMeters());
         if (step > 1) {
            blockX = downsampleBlock(blockX, step);
            blockZ = downsampleBlock(blockZ, step);
         }

         double lon = projection.blockXToLon(blockX);
         double lat = projection.blockZToLat(blockZ);
         return !(lat < MIN_LAT) && !(lat > MAX_LAT) && !(lon < MIN_LON) && !(lon > MAX_LON) ? raster.toPixelSample(lon, lat) : null;
      }
   }

   private static int downsampleStep(double worldScale, double resolutionMeters) {
      return 1;
   }

   private static double downsampleBlock(double blockCoord, int step) {
      if (step <= 1) {
         return blockCoord;
      } else {
         int block = Mth.floor(blockCoord);
         int snapped = Math.floorDiv(block, step) * step;
         return snapped + step * 0.5;
      }
   }

   private static TellusKoppenSource.WarpedCoords warpBlock(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return new TellusKoppenSource.WarpedCoords(blockX, blockZ);
      } else {
         double amplitudeBlocks = WARP_AMPLITUDE_METERS / worldScale;
         if (amplitudeBlocks < 0.5) {
            return new TellusKoppenSource.WarpedCoords(blockX, blockZ);
         } else {
            double wavelengthBlocks = WARP_WAVELENGTH_METERS / worldScale;
            if (wavelengthBlocks <= 1.0) {
               return new TellusKoppenSource.WarpedCoords(blockX, blockZ);
            } else {
               double nx = blockX / wavelengthBlocks;
               double nz = blockZ / wavelengthBlocks;
               double offsetX = valueNoise(nx, nz, WARP_SEED_X) * amplitudeBlocks;
               double offsetZ = valueNoise(nx + 37.0, nz - 59.0, WARP_SEED_Z) * amplitudeBlocks;
               // The broad warp bends borders over kilometres but leaves each 1 km raster step;
               // a pixel-scale octave turns the steps themselves into irregular wiggles.
               double pixelBlocks = PIXEL_WARP_REFERENCE_METERS / worldScale;
               if (pixelBlocks > 2.0) {
                  double detailBlocks = pixelBlocks * PIXEL_WARP_WAVELENGTH_PIXELS;
                  double detailAmplitude = pixelBlocks * PIXEL_WARP_AMPLITUDE_PIXELS;
                  offsetX += valueNoise(blockX / detailBlocks - 13.0, blockZ / detailBlocks + 71.0, WARP_SEED_X ^ PIXEL_WARP_SALT) * detailAmplitude;
                  offsetZ += valueNoise(blockX / detailBlocks + 89.0, blockZ / detailBlocks - 5.0, WARP_SEED_Z ^ PIXEL_WARP_SALT) * detailAmplitude;
               }
               return new TellusKoppenSource.WarpedCoords(blockX + offsetX, blockZ + offsetZ);
            }
         }
      }
   }

   private static double valueNoise(double x, double z, long seed) {
      int x0 = Mth.floor(x);
      int z0 = Mth.floor(z);
      double fx = x - x0;
      double fz = z - z0;
      double v00 = hashToUnit(x0, z0, seed);
      double v10 = hashToUnit(x0 + 1, z0, seed);
      double v01 = hashToUnit(x0, z0 + 1, seed);
      double v11 = hashToUnit(x0 + 1, z0 + 1, seed);
      double u = fade(fx);
      double v = fade(fz);
      double lerpX0 = Mth.lerp(u, v00, v10);
      double lerpX1 = Mth.lerp(u, v01, v11);
      return Mth.lerp(v, lerpX0, lerpX1) * 2.0 - 1.0;
   }

   private static double fade(double t) {
      return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
   }

   private static double hashToUnit(int x, int z, long seed) {
      long h = seed ^ x * -7046029254386353131L;
      h ^= z * -4417276706812531889L;
      h = mix64(h);
      return (h >>> 11) * 1.110223E-16F;
   }

   private static long mix64(long value) {
      long z = (value ^ value >>> 33) * -49064778989728563L;
      z = (z ^ z >>> 33) * -4265267296055464877L;
      return z ^ z >>> 33;
   }

   private TellusKoppenSource.GeoTiffRaster loadRaster() {
      try {
         if (!Files.exists(this.cachePath)) {
            this.cacheRaster();
         }

         return !Files.exists(this.cachePath) ? TellusKoppenSource.GeoTiffRaster.MISSING : TellusKoppenSource.GeoTiffRaster.open(this.cachePath);
      } catch (IOException var2) {
         Tellus.LOGGER.warn("Failed to load Koppen raster", var2);
         try {
            Files.deleteIfExists(this.cachePath);
         } catch (IOException deleteError) {
            var2.addSuppressed(deleteError);
         }
         return TellusKoppenSource.GeoTiffRaster.MISSING;
      }
   }

   private void cacheRaster() {
      try {
         try (InputStream input = TellusKoppenSource.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (input != null) {
               long generation = TellusCacheRegistry.generation(TellusCacheDomain.KOPPEN);
               TellusCacheFiles.writeIfCurrent(TellusCacheDomain.KOPPEN, generation, this.cachePath, input::transferTo);
               return;
            }

            Tellus.LOGGER.warn("Missing Koppen raster resource {}", RESOURCE_PATH);
         }
      } catch (IOException var6) {
         Tellus.LOGGER.warn("Failed to cache Koppen raster", var6);
      }
   }

   private TellusKoppenSource.GeoTiffRaster raster() {
      TellusKoppenSource.GeoTiffRaster current = this.raster;
      if (current != TellusKoppenSource.GeoTiffRaster.MISSING) {
         return current;
      } else {
         synchronized (this) {
            current = this.raster;
            if (current == TellusKoppenSource.GeoTiffRaster.MISSING) {
               current = this.loadRaster();
               this.raster = current;
            }

            return current;
         }
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.KOPPEN;
   }

   @Override
   public void clearCache() {
      synchronized (this) {
         TellusKoppenSource.GeoTiffRaster current = this.raster;
         if (current != null) {
            current.close();
         }

         this.raster = TellusKoppenSource.GeoTiffRaster.MISSING;
      }
   }

   static {
      KOPPEN_CODES[1] = "Af";
      KOPPEN_CODES[2] = "Am";
      KOPPEN_CODES[3] = "Aw";
      KOPPEN_CODES[4] = "BWh";
      KOPPEN_CODES[5] = "BWk";
      KOPPEN_CODES[6] = "BSh";
      KOPPEN_CODES[7] = "BSk";
      KOPPEN_CODES[8] = "Csa";
      KOPPEN_CODES[9] = "Csb";
      KOPPEN_CODES[10] = "Csc";
      KOPPEN_CODES[11] = "Cwa";
      KOPPEN_CODES[12] = "Cwb";
      KOPPEN_CODES[13] = "Cwc";
      KOPPEN_CODES[14] = "Cfa";
      KOPPEN_CODES[15] = "Cfb";
      KOPPEN_CODES[16] = "Cfc";
      KOPPEN_CODES[17] = "Dsa";
      KOPPEN_CODES[18] = "Dsb";
      KOPPEN_CODES[19] = "Dsc";
      KOPPEN_CODES[20] = "Dsd";
      KOPPEN_CODES[21] = "Dwa";
      KOPPEN_CODES[22] = "Dwb";
      KOPPEN_CODES[23] = "Dwc";
      KOPPEN_CODES[24] = "Dwd";
      KOPPEN_CODES[25] = "Dfa";
      KOPPEN_CODES[26] = "Dfb";
      KOPPEN_CODES[27] = "Dfc";
      KOPPEN_CODES[28] = "Dfd";
      KOPPEN_CODES[29] = "ET";
      KOPPEN_CODES[30] = "EF";
   }

   private static final class GeoTiffRaster {
      private static final int TAG_IMAGE_WIDTH = 256;
      private static final int TAG_IMAGE_HEIGHT = 257;
      private static final int TAG_TILE_WIDTH = 322;
      private static final int TAG_TILE_HEIGHT = 323;
      private static final int TAG_TILE_OFFSETS = 324;
      private static final int TAG_TILE_BYTE_COUNTS = 325;
      private static final int TAG_COMPRESSION = 259;
      private static final int TAG_MODEL_PIXEL_SCALE = 33550;
      private static final int TAG_MODEL_TIEPOINT = 33922;
      private static final int TYPE_SHORT = 3;
      private static final int TYPE_LONG = 4;
      private static final int COMPRESSION_LZW = 5;
      private static final int COMPRESSION_DEFLATE = 8;
      private static final int MAX_TILE_CACHE = 64;
      private static final int RECENT_TILE_BITS = 4;
      private static final int RECENT_TILES = 1 << RECENT_TILE_BITS;
      private static final int MAX_RASTER_DIMENSION = 100_000;
      private static final int MAX_TILE_DIMENSION = 4096;
      private static final int MAX_TILE_SAMPLES = 16 * 1024 * 1024;
      private static final int MAX_TIFF_ARRAY_ENTRIES = 100_000;
      private static final int MAX_COMPRESSED_TILE_BYTES = 64 * 1024 * 1024;
      private static final java.util.concurrent.atomic.AtomicInteger NEXT_CACHE_ID = new java.util.concurrent.atomic.AtomicInteger();
      private static final TellusKoppenSource.GeoTiffRaster MISSING = new TellusKoppenSource.GeoTiffRaster();
      /** Identifies this raster in per-thread caches without keeping a closed raster reachable. */
      private final int cacheId = NEXT_CACHE_ID.incrementAndGet();
      private final Path path;
      private final FileChannel channel;
      private final int width;
      private final int height;
      private final int tileWidth;
      private final int tileHeight;
      private final int tilesPerRow;
      private final int compression;
      private final long[] tileOffsets;
      private final int[] tileByteCounts;
      private final double pixelScaleX;
      private final double pixelScaleY;
      private final double tieLon;
      private final double tieLat;
      private final double pixelSizeMeters;
      private final Cache<Integer, byte[]> tileCache;
      // Tiles read recently, without the bounded cache's locks and access-order bookkeeping: every LOD
      // column reads the climate here, and that bookkeeping cost more than the reads.
      private final java.util.concurrent.atomic.AtomicReferenceArray<TellusKoppenSource.RecentTile> recentTiles =
         new java.util.concurrent.atomic.AtomicReferenceArray<>(RECENT_TILES);
      private volatile boolean closed;

      private GeoTiffRaster() {
         this.path = null;
         this.channel = null;
         this.width = 0;
         this.height = 0;
         this.tileWidth = 0;
         this.tileHeight = 0;
         this.tilesPerRow = 0;
         this.compression = 0;
         this.tileOffsets = null;
         this.tileByteCounts = null;
         this.pixelScaleX = 0.0;
         this.pixelScaleY = 0.0;
         this.tieLon = 0.0;
         this.tieLat = 0.0;
         this.pixelSizeMeters = 0.0;
         this.tileCache = CacheBuilder.newBuilder().maximumSize(0L).build();
      }

      private GeoTiffRaster(
         Path path,
         FileChannel channel,
         int width,
         int height,
         int tileWidth,
         int tileHeight,
         int compression,
         long[] tileOffsets,
         int[] tileByteCounts,
         double pixelScaleX,
         double pixelScaleY,
         double tieLon,
         double tieLat
      ) {
         this.path = path;
         this.channel = channel;
         this.width = width;
         this.height = height;
         this.tileWidth = tileWidth;
         this.tileHeight = tileHeight;
         this.tilesPerRow = (int)Math.ceil((double)width / tileWidth);
         this.compression = compression;
         this.tileOffsets = tileOffsets;
         this.tileByteCounts = tileByteCounts;
         this.pixelScaleX = pixelScaleX;
         this.pixelScaleY = pixelScaleY;
         this.tieLon = tieLon;
         this.tieLat = tieLat;
         this.pixelSizeMeters = Math.abs(pixelScaleX) * (EQUATOR_CIRCUMFERENCE / 360.0);
         this.tileCache = CacheBuilder.newBuilder().maximumSize(MAX_TILE_CACHE).build();
      }

      static TellusKoppenSource.GeoTiffRaster open(Path path) throws IOException {
         FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);

         try {
            return readFromChannel(path, channel);
         } catch (IOException | RuntimeException error) {
            channel.close();
            if (error instanceof IOException io) {
               throw io;
            }
            throw new IOException("Invalid Koppen TIFF cache", error);
         }
      }

      void close() {
         if (this.channel != null) {
            this.closed = true;
            for (int slot = 0; slot < RECENT_TILES; slot++) {
               this.recentTiles.set(slot, null);
            }
            this.tileCache.invalidateAll();
            this.tileCache.cleanUp();

            try {
               this.channel.close();
            } catch (IOException var2) {
               Tellus.LOGGER.warn("Failed to close Koppen raster {}", this.path, var2);
            }
         }
      }

      TellusKoppenSource.PixelSample toPixelSample(double lon, double lat) {
         if (this == MISSING) {
            return null;
         } else {
            double pixelXCoord = (lon - this.tieLon) / this.pixelScaleX;
            double pixelYCoord = (this.tieLat - lat) / this.pixelScaleY;
            int pixelX = (int)Math.floor(pixelXCoord);
            int pixelY = (int)Math.floor(pixelYCoord);
            if (pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height) {
               double fracX = Mth.clamp(pixelXCoord - pixelX, 0.0, 0.999999);
               double fracY = Mth.clamp(pixelYCoord - pixelY, 0.0, 0.999999);
               return new TellusKoppenSource.PixelSample(pixelX, pixelY, fracX, fracY);
            } else {
               return null;
            }
         }
      }

      String sampleSmoothed(TellusKoppenSource.Pixel center, int radius) {
         if (center != null && radius > 0) {
            int bestIndex = this.smoothedValue(center.x, center.y, radius);
            return bestIndex <= 0 ? null : TellusKoppenSource.KOPPEN_CODES[bestIndex];
         } else {
            return this.sample(center);
         }
      }

      /** The most common class within {@code radius} pixels, ties going to the pixel's own class; 0 if none. */
      private int smoothedValue(int pixelX, int pixelY, int radius) {
         TellusKoppenSource.SmoothedValueCache cache = SMOOTHED_CACHE.get();
         int cached = cache.get(this, pixelX, pixelY, radius);
         if (cached >= 0) {
            return cached;
         }

         int side = 2 * radius + 1;
         byte[] window = cache.window(side * side);
         this.readWindow(pixelX - radius, pixelY - radius, side, side, window);
         int value = modeInWindow(window, side, radius, radius, radius, cache.counts);
         cache.put(this, pixelX, pixelY, radius, value);
         return value;
      }

      /** Mode of the disk of {@code radius} around ({@code cx}, {@code cy}) in a window {@code side} pixels wide. */
      private static int modeInWindow(byte[] window, int side, int cx, int cy, int radius, int[] counts) {
         java.util.Arrays.fill(counts, 0);
         int centerValue = Byte.toUnsignedInt(window[cy * side + cx]);
         for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (dx * dx + dy * dy <= radius * radius) {
                  int value = Byte.toUnsignedInt(window[(cy + dy) * side + cx + dx]);
                  if (value > 0 && value < counts.length) {
                     counts[value]++;
                  }
               }
            }
         }

         int bestIndex = 0;
         int bestCount = 0;
         for (int i = 1; i < counts.length; i++) {
            int count = counts[i];
            if (count > bestCount) {
               bestCount = count;
               bestIndex = i;
            } else if (count == bestCount && count > 0 && i == centerValue) {
               bestIndex = i;
            }
         }
         return bestIndex;
      }

      /**
       * The mode-filtered class of the four pixels around the point, picked by the clustered
       * dither {@link #sampleDithered} uses, so a smoothed climate region ends in an organic border.
       * The four disks are read from one window, usually a single tile access.
       */
      String sampleSmoothedDithered(TellusKoppenSource.PixelSample center, double blockX, double blockZ, int radius) {
         if (center == null) {
            return null;
         }
         double blendX = center.x + center.fracX - 0.5;
         double blendY = center.y + center.fracY - 0.5;
         int x0 = Mth.floor(blendX);
         int y0 = Mth.floor(blendY);
         double fx = blendX - x0;
         double fy = blendY - y0;
         TellusKoppenSource.SmoothedValueCache cache = SMOOTHED_CACHE.get();
         int side = 2 * radius + 2;
         byte[] window = cache.window(side * side);
         this.readWindow(x0 - radius, y0 - radius, side, side, window);
         int value00 = modeInWindow(window, side, radius, radius, radius, cache.counts);
         int value10 = modeInWindow(window, side, radius + 1, radius, radius, cache.counts);
         int value01 = modeInWindow(window, side, radius, radius + 1, radius, cache.counts);
         int value11 = modeInWindow(window, side, radius + 1, radius + 1, radius, cache.counts);
         int centerValue = center.x == x0 ? (center.y == y0 ? value00 : value01) : (center.y == y0 ? value10 : value11);
         if (centerValue <= 0 || centerValue >= TellusKoppenSource.KOPPEN_CODES.length) {
            return null;
         }
         TellusKoppenSource.KoppenBlendScratch scratch = DITHER_SCRATCH.get();
         scratch.reset();
         scratch.add(value00, (1.0 - fx) * (1.0 - fy));
         scratch.add(value10, fx * (1.0 - fy));
         scratch.add(value01, (1.0 - fx) * fy);
         scratch.add(value11, fx * fy);
         double threshold = SurfacePatchNoise.uniform(Mth.floor(blockX), Mth.floor(blockZ), DITHER_SEED);
         int selectedValue = scratch.pickWeighted(centerValue, threshold);
         return selectedValue > 0 && selectedValue < TellusKoppenSource.KOPPEN_CODES.length ? TellusKoppenSource.KOPPEN_CODES[selectedValue] : null;
      }

      String sampleDithered(TellusKoppenSource.PixelSample center, double blockX, double blockZ) {
         if (center == null) {
            return null;
         } else {
            int centerValue = this.sampleValue(center.x, center.y);
            if (centerValue > 0 && centerValue < TellusKoppenSource.KOPPEN_CODES.length) {
               double blendX = center.x + center.fracX - 0.5;
               double blendY = center.y + center.fracY - 0.5;
               int x0 = Mth.floor(blendX);
               int y0 = Mth.floor(blendY);
               int x1 = x0 + 1;
               int y1 = y0 + 1;
               double fx = blendX - x0;
               double fy = blendY - y0;
               double inverseFx = 1.0 - fx;
               double inverseFy = 1.0 - fy;
               TellusKoppenSource.KoppenBlendScratch scratch = DITHER_SCRATCH.get();
               scratch.reset();
               scratch.add(this.sampleValue(x0, y0), inverseFx * inverseFy);
               scratch.add(this.sampleValue(x1, y0), fx * inverseFy);
               scratch.add(this.sampleValue(x0, y1), inverseFx * fy);
               scratch.add(this.sampleValue(x1, y1), fx * fy);
               // Clustered rather than per-cell random, so a climate border becomes
               // an irregular edge with outlying groves instead of 4x4 confetti.
               double threshold = SurfacePatchNoise.uniform(Mth.floor(blockX), Mth.floor(blockZ), DITHER_SEED);
               int selectedValue = scratch.pickWeighted(centerValue, threshold);
               return selectedValue > 0 && selectedValue < TellusKoppenSource.KOPPEN_CODES.length ? TellusKoppenSource.KOPPEN_CODES[selectedValue] : null;
            } else {
               return null;
            }
         }
      }

      String findNearest(TellusKoppenSource.Pixel center, int radius) {
         if (center != null && radius > 0) {
            int bestValue = 0;
            int bestDist = Integer.MAX_VALUE;
            int maxDist = radius * radius;

            for (int dy = -radius; dy <= radius; dy++) {
               for (int dx = -radius; dx <= radius; dx++) {
                  int dist = dx * dx + dy * dy;
                  if (dist <= maxDist) {
                     int value = this.sampleValue(center.x + dx, center.y + dy);
                     if (value > 0 && value < TellusKoppenSource.KOPPEN_CODES.length && dist < bestDist) {
                        bestDist = dist;
                        bestValue = value;
                     }
                  }
               }
            }

            return bestValue > 0 ? TellusKoppenSource.KOPPEN_CODES[bestValue] : null;
         } else {
            return null;
         }
      }

      int radiusForMeters(double meters) {
         return this.pixelSizeMeters <= 0.0 ? 0 : Math.max(1, (int)Math.ceil(meters / this.pixelSizeMeters));
      }

      double pixelSizeMeters() {
         return this.pixelSizeMeters;
      }

      private String sample(TellusKoppenSource.Pixel pixel) {
         if (pixel == null) {
            return null;
         } else {
            int value = this.sampleValue(pixel.x, pixel.y);
            return value > 0 && value < TellusKoppenSource.KOPPEN_CODES.length ? TellusKoppenSource.KOPPEN_CODES[value] : null;
         }
      }

      private int sampleValue(int pixelX, int pixelY) {
         if (this == MISSING) {
            return 0;
         } else if (pixelX >= 0 && pixelY >= 0 && pixelX < this.width && pixelY < this.height) {
            int tileX = pixelX / this.tileWidth;
            int tileY = pixelY / this.tileHeight;
            int tileIndex = tileY * this.tilesPerRow + tileX;

            byte[] tile;
            try {
               tile = this.getTile(tileIndex);
            } catch (IOException var9) {
               if (!(var9 instanceof ClosedByInterruptException) && !Thread.currentThread().isInterrupted()) {
                  Tellus.LOGGER.warn("Failed to read Koppen tile {} in {}", new Object[]{tileIndex, this.path, var9});
                  return 0;
               }

               Thread.currentThread().interrupt();
               return 0;
            }

            int localX = pixelX - tileX * this.tileWidth;
            int localY = pixelY - tileY * this.tileHeight;
            return Byte.toUnsignedInt(tile[localX + localY * this.tileWidth]);
         } else {
            return 0;
         }
      }

      void readWindow(int windowX, int windowY, int windowWidth, int windowHeight, byte[] out) {
         java.util.Arrays.fill(out, 0, windowWidth * windowHeight, (byte)0);
         int fromX = Math.max(0, windowX);
         int toX = Math.min(this.width, windowX + windowWidth);
         int fromY = Math.max(0, windowY);
         int toY = Math.min(this.height, windowY + windowHeight);
         if (this == MISSING || fromX >= toX || fromY >= toY) {
            return;
         }

         for (int tileY = fromY / this.tileHeight; tileY <= (toY - 1) / this.tileHeight; tileY++) {
            for (int tileX = fromX / this.tileWidth; tileX <= (toX - 1) / this.tileWidth; tileX++) {
               byte[] tile;
               try {
                  tile = this.getTile(tileY * this.tilesPerRow + tileX);
               } catch (IOException error) {
                  if (error instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
                     Thread.currentThread().interrupt();
                     return;
                  }

                  Tellus.LOGGER.warn("Failed to read Koppen tile {} in {}", tileY * this.tilesPerRow + tileX, this.path, error);
                  continue;
               }

               int startX = Math.max(fromX, tileX * this.tileWidth);
               int endX = Math.min(toX, (tileX + 1) * this.tileWidth);
               int startY = Math.max(fromY, tileY * this.tileHeight);
               int endY = Math.min(toY, (tileY + 1) * this.tileHeight);
               for (int y = startY; y < endY; y++) {
                  System.arraycopy(
                     tile,
                     (startX - tileX * this.tileWidth) + (y - tileY * this.tileHeight) * this.tileWidth,
                     out,
                     (y - windowY) * windowWidth + (startX - windowX),
                     endX - startX
                  );
               }
            }
         }
      }

      private byte[] getTile(int tileIndex) throws IOException {
         int slot = (tileIndex * 0x9E3779B9) >>> (32 - RECENT_TILE_BITS);
         TellusKoppenSource.RecentTile recent = this.recentTiles.get(slot);
         if (recent != null && recent.index() == tileIndex) {
            return recent.data();
         }
         try {
            byte[] tile = this.tileCache.get(tileIndex, () -> this.readTile(tileIndex));
            if (!this.closed) {
               this.recentTiles.set(slot, new TellusKoppenSource.RecentTile(tileIndex, tile));
            }
            return tile;
         } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) {
               throw io;
            }
            if (cause instanceof RuntimeException runtime) {
               throw runtime;
            }
            if (cause instanceof Error fatal) {
               throw fatal;
            }
            throw new IOException("Failed to read Koppen tile " + tileIndex, cause);
         }
      }

      private byte[] readTile(int tileIndex) throws IOException {
         if (tileIndex < 0 || tileIndex >= this.tileOffsets.length || tileIndex >= this.tileByteCounts.length) {
            throw new IOException("TIFF tile index is outside the tile table");
         }
         long offset = this.tileOffsets[tileIndex];
         int length = this.tileByteCounts[tileIndex];
         if (length <= 0 || length > MAX_COMPRESSED_TILE_BYTES) {
            throw new IOException("Invalid TIFF tile byte count " + length);
         }
         byte[] compressed = new byte[length];

         try {
            readFully(this.channel, compressed, offset);
         } catch (ClosedChannelException var12) {
            if (this.path == null) {
               throw var12;
            }

            try (FileChannel reopened = FileChannel.open(this.path, StandardOpenOption.READ)) {
               readFully(reopened, compressed, offset);
            }
         }

         int expectedSize = checkedTileSamples(this.tileWidth, this.tileHeight);
         if (this.compression == COMPRESSION_DEFLATE) {
            return inflate(compressed, expectedSize);
         } else if (this.compression == COMPRESSION_LZW) {
            return decompressLzw(compressed, expectedSize);
         } else {
            throw new IOException("Unsupported TIFF compression " + this.compression);
         }
      }

      private static TellusKoppenSource.GeoTiffRaster readFromChannel(Path path, FileChannel channel) throws IOException {
         ByteBuffer header = ByteBuffer.allocate(8);
         readFully(channel, header, 0L);
         header.flip();
         short order = header.getShort();

         ByteOrder byteOrder = switch (order) {
            case 18761 -> ByteOrder.LITTLE_ENDIAN;
            case 19789 -> ByteOrder.BIG_ENDIAN;
            default -> throw new IOException("Invalid TIFF byte order");
         };
         header.order(byteOrder);
         short magic = header.getShort();
         if (magic != 42) {
            throw new IOException("Invalid TIFF magic");
         } else {
            long ifdOffset = Integer.toUnsignedLong(header.getInt());
            validateRange(channel, ifdOffset, 2L, "TIFF directory");
            ByteBuffer countBuffer = ByteBuffer.allocate(2).order(byteOrder);
            readFully(channel, countBuffer, ifdOffset);
            countBuffer.flip();
            int entryCount = Short.toUnsignedInt(countBuffer.getShort());
            validateRange(channel, ifdOffset + 2L, (long)entryCount * 12L, "TIFF directory entries");
            ByteBuffer entries = ByteBuffer.allocate(entryCount * 12).order(byteOrder);
            readFully(channel, entries, ifdOffset + 2L);
            entries.flip();
            int width = -1;
            int height = -1;
            int tileWidth = -1;
            int tileHeight = -1;
            int compression = -1;
            long[] tileOffsets = null;
            int[] tileByteCounts = null;
            double[] pixelScale = null;
            double[] tiepoint = null;

            for (int i = 0; i < entryCount; i++) {
               int tag = Short.toUnsignedInt(entries.getShort());
               int type = Short.toUnsignedInt(entries.getShort());
               int count = entries.getInt();
               int value = entries.getInt();
               switch (tag) {
                  case TAG_IMAGE_WIDTH:
                     width = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_IMAGE_HEIGHT:
                     height = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_COMPRESSION:
                     compression = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_WIDTH:
                     tileWidth = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_HEIGHT:
                     tileHeight = readIntValue(type, count, value, byteOrder);
                     break;
                  case TAG_TILE_OFFSETS:
                     tileOffsets = readLongArray(channel, Integer.toUnsignedLong(value), count, byteOrder);
                     break;
                  case TAG_TILE_BYTE_COUNTS:
                     tileByteCounts = readIntArray(channel, Integer.toUnsignedLong(value), count, byteOrder);
                     break;
                  case TAG_MODEL_PIXEL_SCALE:
                     pixelScale = readDoubleArray(channel, Integer.toUnsignedLong(value), count, byteOrder);
                     break;
                  case TAG_MODEL_TIEPOINT:
                     tiepoint = readDoubleArray(channel, Integer.toUnsignedLong(value), count, byteOrder);
               }
            }

            if (compression != COMPRESSION_DEFLATE && compression != COMPRESSION_LZW) {
               throw new IOException("Unsupported TIFF compression " + compression);
            } else if (width <= 0
               || height <= 0
               || width > MAX_RASTER_DIMENSION
               || height > MAX_RASTER_DIMENSION
               || tileWidth <= 0
               || tileHeight <= 0
               || tileWidth > MAX_TILE_DIMENSION
               || tileHeight > MAX_TILE_DIMENSION) {
               throw new IOException("Missing TIFF size tags");
            } else if (tileOffsets == null || tileByteCounts == null) {
               throw new IOException("Missing TIFF tile offsets");
            } else if (pixelScale != null && pixelScale.length >= 2 && tiepoint != null && tiepoint.length >= 5) {
               checkedTileSamples(tileWidth, tileHeight);
               long tilesAcross = ((long)width + tileWidth - 1L) / tileWidth;
               long tilesDown = ((long)height + tileHeight - 1L) / tileHeight;
               long expectedTiles = tilesAcross * tilesDown;
               if (expectedTiles <= 0L
                  || expectedTiles > MAX_TIFF_ARRAY_ENTRIES
                  || tileOffsets.length != expectedTiles
                  || tileByteCounts.length != expectedTiles) {
                  throw new IOException("TIFF tile table does not match the raster dimensions");
               }
               for (int tile = 0; tile < tileOffsets.length; tile++) {
                  int byteCount = tileByteCounts[tile];
                  if (byteCount <= 0 || byteCount > MAX_COMPRESSED_TILE_BYTES) {
                     throw new IOException("Invalid TIFF tile byte count " + byteCount);
                  }
                  validateRange(channel, tileOffsets[tile], byteCount, "TIFF tile");
               }
               if (!Double.isFinite(pixelScale[0])
                  || !Double.isFinite(pixelScale[1])
                  || pixelScale[0] == 0.0
                  || pixelScale[1] == 0.0
                  || !Double.isFinite(tiepoint[3])
                  || !Double.isFinite(tiepoint[4])) {
                  throw new IOException("Invalid TIFF georeference values");
               }
               return new TellusKoppenSource.GeoTiffRaster(
                  path,
                  channel,
                  width,
                  height,
                  tileWidth,
                  tileHeight,
                  compression,
                  tileOffsets,
                  tileByteCounts,
                  pixelScale[0],
                  pixelScale[1],
                  tiepoint[3],
                  tiepoint[4]
               );
            } else {
               throw new IOException("Missing TIFF georeference tags");
            }
         }
      }

      private static int readIntValue(int type, int count, int value, ByteOrder order) throws IOException {
         if (count != 1) {
            throw new IOException("Expected single TIFF value");
         } else {
            ByteBuffer buffer = ByteBuffer.allocate(4).order(order);
            buffer.putInt(value);
            buffer.flip();
            if (type == TYPE_SHORT) {
               return Short.toUnsignedInt(buffer.getShort());
            } else if (type == TYPE_LONG) {
               return buffer.getInt();
            } else {
               throw new IOException("Unsupported TIFF value type " + type);
            }
         }
      }

      private static long[] readLongArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new long[0];
         } else {
            int byteCount = checkedArrayBytes(count, Integer.BYTES, "TIFF long array");
            validateRange(channel, offset, byteCount, "TIFF long array");
            ByteBuffer buffer = ByteBuffer.allocate(byteCount).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            long[] values = new long[count];

            for (int i = 0; i < count; i++) {
               values[i] = Integer.toUnsignedLong(buffer.getInt());
            }

            return values;
         }
      }

      private static int[] readIntArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new int[0];
         } else {
            int byteCount = checkedArrayBytes(count, Integer.BYTES, "TIFF integer array");
            validateRange(channel, offset, byteCount, "TIFF integer array");
            ByteBuffer buffer = ByteBuffer.allocate(byteCount).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            int[] values = new int[count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getInt();
            }

            return values;
         }
      }

      private static double[] readDoubleArray(FileChannel channel, long offset, int count, ByteOrder order) throws IOException {
         if (count <= 0) {
            return new double[0];
         } else {
            int byteCount = checkedArrayBytes(count, Double.BYTES, "TIFF double array");
            validateRange(channel, offset, byteCount, "TIFF double array");
            ByteBuffer buffer = ByteBuffer.allocate(byteCount).order(order);
            readFully(channel, buffer, offset);
            buffer.flip();
            double[] values = new double[count];

            for (int i = 0; i < count; i++) {
               values[i] = buffer.getDouble();
            }

            return values;
         }
      }

      private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
         validateRange(channel, offset, buffer.remaining(), "TIFF data");
         long position = offset;

         while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
               throw new EOFException("Unexpected end of file");
            }

            position += read;
         }
      }

      private static void readFully(FileChannel channel, byte[] dest, long offset) throws IOException {
         readFully(channel, ByteBuffer.wrap(dest), offset);
      }

      private static int checkedArrayBytes(int count, int bytesPerValue, String label) throws IOException {
         if (count <= 0 || count > MAX_TIFF_ARRAY_ENTRIES) {
            throw new IOException("Invalid " + label + " count " + count);
         }
         return Math.multiplyExact(count, bytesPerValue);
      }

      private static int checkedTileSamples(int width, int height) throws IOException {
         long samples = (long)width * height;
         if (width <= 0 || height <= 0 || samples > MAX_TILE_SAMPLES) {
            throw new IOException("TIFF tile dimensions exceed the safety limit");
         }
         return (int)samples;
      }

      private static void validateRange(FileChannel channel, long offset, long length, String label) throws IOException {
         long fileSize = channel.size();
         if (offset < 0L || length < 0L || offset > fileSize || length > fileSize - offset) {
            throw new IOException(label + " range is outside the TIFF file");
         }
      }

      private static byte[] inflate(byte[] compressed, int expectedSize) throws IOException {
         byte[] output = new byte[expectedSize];

         byte[] var8;
         try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int offset = 0;

            while (offset < expectedSize) {
               int read = inflater.read(output, offset, expectedSize - offset);
               if (read < 0) {
                  break;
               }

               offset += read;
            }

            if (offset != expectedSize) {
               throw new IOException("Unexpected inflated data length");
            }
            if (inflater.read() != -1) {
               throw new IOException("Inflated TIFF tile exceeds its declared dimensions");
            }

            var8 = output;
         }

         return var8;
      }

      private static byte[] decompressLzw(byte[] compressed, int expectedSize) throws IOException {
         byte[][] table = new byte[4096][];

         for (int i = 0; i < 256; i++) {
            table[i] = new byte[]{(byte)i};
         }

         int clearCode = 256;
         int endCode = 257;
         int codeSize = 9;
         int nextCode = 258;
         byte[] output = new byte[expectedSize];
         int outPos = 0;
         TellusKoppenSource.GeoTiffRaster.LzwBitReader reader = new TellusKoppenSource.GeoTiffRaster.LzwBitReader(compressed);
         byte[] previous = null;

         while (true) {
            int code = reader.read(codeSize);
            if (code < 0) {
               break;
            }

            if (code == clearCode) {
               for (int i = 0; i < 256; i++) {
                  table[i] = new byte[]{(byte)i};
               }

               for (int i = 256; i < table.length; i++) {
                  table[i] = null;
               }

               codeSize = 9;
               nextCode = 258;
               previous = null;
            } else {
               if (code == endCode) {
                  break;
               }

               byte[] entry;
               if (code < nextCode && table[code] != null) {
                  entry = table[code];
               } else {
                  if (code != nextCode || previous == null) {
                     throw new IOException("Invalid LZW code " + code);
                  }

                  entry = concat(previous, previous[0]);
               }

               if (outPos + entry.length > output.length) {
                  throw new IOException("Unexpected LZW output size");
               }

               System.arraycopy(entry, 0, output, outPos, entry.length);
               outPos += entry.length;
               if (previous != null && nextCode < table.length) {
                  table[nextCode++] = concat(previous, entry[0]);
                  int threshold = (1 << codeSize) - 1;
                  if (nextCode == threshold && codeSize < 12) {
                     codeSize++;
                  }
               }

               previous = entry;
               if (outPos == output.length) {
                  break;
               }
            }
         }

         if (outPos != output.length) {
            throw new IOException("Unexpected LZW output length " + outPos);
         } else {
            return output;
         }
      }

      private static byte[] concat(byte[] prefix, byte suffix) {
         byte[] combined = new byte[prefix.length + 1];
         System.arraycopy(prefix, 0, combined, 0, prefix.length);
         combined[prefix.length] = suffix;
         return combined;
      }

      private static final class LzwBitReader {
         private final byte[] data;
         private int bitPos;

         private LzwBitReader(byte[] data) {
            this.data = data;
         }

         private int read(int bits) {
            int totalBits = this.data.length * 8;
            if (this.bitPos + bits > totalBits) {
               return -1;
            } else {
               int value = 0;

               for (int i = 0; i < bits; i++) {
                  int offset = this.bitPos + i;
                  int byteIndex = offset >> 3;
                  int bitIndex = 7 - (offset & 7);
                  int current = this.data[byteIndex] & 255;
                  value = value << 1 | current >> bitIndex & 1;
               }

               this.bitPos += bits;
               return value;
            }
         }
      }
   }

   /** Recent mode-filtered pixels per thread: neighbouring columns share the same few Köppen pixels. */
   private static final class SmoothedValueCache {
      private static final int SIZE = 64;
      private final int[] rasters = new int[SIZE];
      private final long[] keys = new long[SIZE];
      private final int[] radii = new int[SIZE];
      private final int[] values = new int[SIZE];
      private final int[] counts = new int[TellusKoppenSource.KOPPEN_CODES.length];
      private byte[] window = new byte[0];

      private byte[] window(int length) {
         if (this.window.length < length) {
            this.window = new byte[length];
         }
         return this.window;
      }

      private int get(TellusKoppenSource.GeoTiffRaster raster, int pixelX, int pixelY, int radius) {
         long key = key(pixelX, pixelY);
         int slot = slot(key);
         return this.rasters[slot] == raster.cacheId && this.keys[slot] == key && this.radii[slot] == radius ? this.values[slot] : -1;
      }

      private void put(TellusKoppenSource.GeoTiffRaster raster, int pixelX, int pixelY, int radius, int value) {
         long key = key(pixelX, pixelY);
         int slot = slot(key);
         this.rasters[slot] = raster.cacheId;
         this.keys[slot] = key;
         this.radii[slot] = radius;
         this.values[slot] = value;
      }

      private static long key(int pixelX, int pixelY) {
         return (long)pixelX << 32 | pixelY & 0xFFFFFFFFL;
      }

      private static int slot(long key) {
         long mixed = key * -7046029254386353131L;
         return (int)(mixed >>> 58);
      }
   }

   private static final class KoppenBlendScratch {
      private final double[] weights = new double[TellusKoppenSource.KOPPEN_CODES.length];
      private final int[] used = new int[TellusKoppenSource.KOPPEN_CODES.length];
      private int usedCount;

      private void reset() {
         for (int i = 0; i < this.usedCount; i++) {
            this.weights[this.used[i]] = 0.0;
         }

         this.usedCount = 0;
      }

      private void add(int value, double weight) {
         if (value > 0 && value < this.weights.length && weight > 0.0) {
            if (!(this.weights[value] > 0.0)) {
               this.used[this.usedCount++] = value;
            }

            this.weights[value] += weight;
         }
      }

      private int pickWeighted(int fallbackValue, double threshold) {
         if (this.usedCount == 0) {
            return fallbackValue;
         } else {
            double total = 0.0;

            for (int i = 0; i < this.usedCount; i++) {
               total += this.weights[this.used[i]];
            }

            if (!(total > 0.0)) {
               return fallbackValue;
            } else {
               // A spatially smooth threshold needs one class order everywhere;
               // insertion order flips between pixel cells and would leave
               // straight seams along the pixel-centre lines.
               for (int i = 1; i < this.usedCount; i++) {
                  int value = this.used[i];
                  int j = i - 1;
                  while (j >= 0 && this.used[j] > value) {
                     this.used[j + 1] = this.used[j];
                     j--;
                  }
                  this.used[j + 1] = value;
               }

               double target = Mth.clamp(threshold, 0.0, 0.9999999999999999) * total;
               double cumulative = 0.0;
               int lastValue = fallbackValue;

               for (int i = 0; i < this.usedCount; i++) {
                  int value = this.used[i];
                  lastValue = value;
                  cumulative += this.weights[value];
                  if (target < cumulative || i + 1 == this.usedCount) {
                     return value;
                  }
               }

               return lastValue;
            }
         }
      }
   }

   /** Pixel grid of the raster: pixel (0, 0) has its north-west corner at the origin. */
   public record PixelGrid(double originLongitude, double originLatitude, double pixelWidthDegrees, double pixelHeightDegrees, int width, int height) {
      public int pixelX(double longitude) {
         return (int)Math.floor((longitude - this.originLongitude) / this.pixelWidthDegrees);
      }

      public int pixelY(double latitude) {
         return (int)Math.floor((this.originLatitude - latitude) / this.pixelHeightDegrees);
      }

      public double centerLongitude(int pixelX) {
         return this.originLongitude + (pixelX + 0.5) * this.pixelWidthDegrees;
      }

      public double centerLatitude(int pixelY) {
         return this.originLatitude - (pixelY + 0.5) * this.pixelHeightDegrees;
      }
   }

   private record Pixel(int x, int y) {
   }

   private record PixelSample(int x, int y, double fracX, double fracY) {
   }

   private record WarpedCoords(double x, double z) {
   }

   private record RecentTile(int index, byte[] data) {
   }
}
