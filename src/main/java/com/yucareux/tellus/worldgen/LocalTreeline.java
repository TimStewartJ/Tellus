package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import java.util.concurrent.ExecutionException;

/**
 * Treeline elevation from the local climate.
 *
 * <p>{@link MountainElevationZones#treelineMeters} is one curve per latitude, but
 * treelines at the same latitude differ by hundreds of metres: inner ranges sit
 * higher than outer ones and maritime coasts lose their trees low. The Köppen map
 * shipped with the mod marks tundra (ET: warmest month below 10 °C, the classic
 * treeline isotherm) and ice cap (EF). In mountains their border with warmer
 * classes follows the height where trees stop, so every pair of neighbouring
 * pixels across it gives a treeline sample at the pair's mean elevation.</p>
 *
 * <p>Samples are averaged with Gaussian weights over {@value #SIGMA_KM} km, with
 * the latitude curve as a weak prior, on a grid of {@value #NODES_PER_DEGREE}
 * nodes per degree. The field moves smoothly and returns to the curve where no
 * tundra border is near. It depends only on the Köppen raster and elevation at a
 * fixed resolution, so real chunks and every LOD detail level agree.</p>
 */
public final class LocalTreeline {
   static final double SIGMA_KM = 10.0;
   static final double PRIOR_WEIGHT = 2.0;
   static final int NODES_PER_DEGREE = 24;
   /** Largest departure from the latitude curve, against stray data. */
   static final double MAX_DEVIATION_METERS = 1200.0;
   // Pixel elevations come from 2 km data, which the far LOD levels load anyway. A border
   // pair's mean elevation is unaffected by that smoothing on a steady slope.
   static final double ELEVATION_RESOLUTION_METERS = 2000.0;
   private static final double WINDOW_SIGMAS = 3.0;
   private static final double KM_PER_DEGREE = 111.32;
   private static final double PAIR_RELIEF_SCALE_METERS = 400.0;
   private static final double INVERTED_PAIR_WEIGHT = 0.25;
   private static final long DEGRADED_RETRY_NANOS = 60_000_000_000L;
   private static final int ELEVATION_BLOCK_SHIFT = 5;
   private static final int ELEVATION_BLOCK_MASK = (1 << ELEVATION_BLOCK_SHIFT) - 1;
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.localTreeline"));
   private static final LocalTreeline.Tile CURVE_ONLY = new LocalTreeline.Tile(null, false, 0L);
   private final LocalTreeline.ClimateRaster climate;
   private final LocalTreeline.ElevationSampler elevation;
   private final Cache<Long, LocalTreeline.Tile> tiles = CacheBuilder.newBuilder().maximumSize(4096L).build();
   // Elevations of 32 x 32 pixel blocks (4 KB each), shared by the tiles whose windows overlap them.
   private final Cache<Long, float[]> elevationBlocks = CacheBuilder.newBuilder().maximumSize(4096L).build();

   public LocalTreeline(LocalTreeline.ClimateRaster climate, LocalTreeline.ElevationSampler elevation) {
      this.climate = climate;
      this.elevation = elevation;
   }

   /** The treeline for a world, reading the shipped Köppen raster and the world's elevation source. */
   public static LocalTreeline forSettings(EarthGeneratorSettings settings) {
      TellusKoppenSource koppen = TellusWorldgenSources.koppen();
      TellusElevationSource source = TellusWorldgenSources.elevation();
      double worldScale = settings.worldScale();
      EarthGeneratorSettings.DemSelection demSelection = settings.demSelection();
      return new LocalTreeline(
         new LocalTreeline.ClimateRaster() {
            @Override
            public TellusKoppenSource.PixelGrid grid() {
               return koppen.pixelGrid();
            }

            @Override
            public int classIndex(int pixelX, int pixelY) {
               return koppen.classIndexAtPixel(pixelX, pixelY);
            }

            @Override
            public void readWindow(int pixelX, int pixelY, int width, int height, byte[] out) {
               koppen.readClassWindow(pixelX, pixelY, width, height, out);
            }
         },
         (latitude, longitude) -> worldScale > 0.0
            ? source.samplePreviewElevationMeters(
               EarthProjection.longitudeToBlockX(longitude, worldScale),
               EarthProjection.latToBlockZ(latitude, worldScale),
               worldScale,
               false,
               demSelection,
               ELEVATION_RESOLUTION_METERS
            )
            : Double.NaN
      );
   }

   /** Treeline elevation in metres at a location; the latitude curve where the climate says nothing. */
   public double treelineMeters(double latitude, double longitude) {
      double curve = MountainElevationZones.treelineMeters(latitude);
      if (!ENABLED || !Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) >= EarthProjection.MAX_MERCATOR_LATITUDE) {
         return curve;
      }

      double wrappedLongitude = longitude - 360.0 * Math.floor((longitude + 180.0) / 360.0);
      int tileLatitude = (int)Math.floor(latitude);
      int tileLongitude = (int)Math.floor(wrappedLongitude);
      LocalTreeline.Tile tile = this.tile(tileLatitude, tileLongitude);
      if (tile.nodes() == null) {
         return curve;
      }

      double row = (latitude - tileLatitude) * NODES_PER_DEGREE;
      double column = (wrappedLongitude - tileLongitude) * NODES_PER_DEGREE;
      int row0 = Math.min(NODES_PER_DEGREE - 1, (int)row);
      int column0 = Math.min(NODES_PER_DEGREE - 1, (int)column);
      double fractionRow = row - row0;
      double fractionColumn = column - column0;
      float[] nodes = tile.nodes();
      int stride = NODES_PER_DEGREE + 1;
      double south = nodes[row0 * stride + column0] + fractionColumn * (nodes[row0 * stride + column0 + 1] - nodes[row0 * stride + column0]);
      double north = nodes[(row0 + 1) * stride + column0]
         + fractionColumn * (nodes[(row0 + 1) * stride + column0 + 1] - nodes[(row0 + 1) * stride + column0]);
      return south + fractionRow * (north - south);
   }

   private LocalTreeline.Tile tile(int tileLatitude, int tileLongitude) {
      long key = ((long)(tileLatitude + 90) << 16) | (tileLongitude + 180);
      LocalTreeline.Tile cached = this.tiles.getIfPresent(key);
      if (cached != null && (!cached.degraded() || System.nanoTime() - cached.computedAt() < DEGRADED_RETRY_NANOS)) {
         return cached;
      }

      if (cached != null) {
         this.tiles.invalidate(key);
      }

      try {
         return this.tiles.get(key, () -> {
            LocalTreeline.Tile computed = this.computeTile(tileLatitude, tileLongitude);
            if (Thread.currentThread().isInterrupted()) {
               // Interrupted reads come back empty; keep the half-read tile out of the cache.
               throw new InterruptedException("Treeline tile interrupted");
            }
            return computed;
         });
      } catch (ExecutionException | RuntimeException error) {
         return CURVE_ONLY;
      }
   }

   private LocalTreeline.Tile computeTile(int tileLatitude, int tileLongitude) {
      TellusKoppenSource.PixelGrid grid = this.climate.grid();
      if (grid == null) {
         return new LocalTreeline.Tile(null, true, System.nanoTime());
      }

      double poleward = Math.min(85.0, Math.max(Math.abs(tileLatitude), Math.abs(tileLatitude + 1)));
      double cosine = Math.cos(Math.toRadians(poleward));
      int marginRows = (int)Math.ceil(WINDOW_SIGMAS * SIGMA_KM / KM_PER_DEGREE * NODES_PER_DEGREE);
      int marginColumns = (int)Math.ceil(WINDOW_SIGMAS * SIGMA_KM / (KM_PER_DEGREE * cosine) * NODES_PER_DEGREE);
      int rows = NODES_PER_DEGREE + 1 + 2 * marginRows;
      int columns = NODES_PER_DEGREE + 1 + 2 * marginColumns;
      double southLatitude = tileLatitude - (double)marginRows / NODES_PER_DEGREE;
      double westLongitude = tileLongitude - (double)marginColumns / NODES_PER_DEGREE;
      double halfNode = 0.5 / NODES_PER_DEGREE;
      int pixelWest = grid.pixelX(westLongitude - halfNode);
      int pixelEast = grid.pixelX(westLongitude + (columns - 1.0) / NODES_PER_DEGREE + halfNode);
      int pixelNorth = grid.pixelY(southLatitude + (rows - 1.0) / NODES_PER_DEGREE + halfNode);
      int pixelSouth = grid.pixelY(southLatitude - halfNode);
      int width = pixelEast - pixelWest + 1;
      int height = pixelSouth - pixelNorth + 1;
      if (width <= 1 || height <= 1) {
         return CURVE_ONLY;
      }

      byte[] classes = new byte[width * height];
      this.climate.readWindow(pixelWest, pixelNorth, width, height, classes);
      boolean anyCold = false;
      for (byte value : classes) {
         if (isCold(Byte.toUnsignedInt(value))) {
            anyCold = true;
            break;
         }
      }
      if (!anyCold) {
         return CURVE_ONLY;
      }

      float[] elevations = new float[width * height];
      java.util.Arrays.fill(elevations, Float.NEGATIVE_INFINITY);
      double[] weightedElevations = new double[rows * columns];
      double[] weights = new double[rows * columns];
      boolean degraded = false;
      int pairs = 0;
      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int value = Byte.toUnsignedInt(classes[y * width + x]);
            if (value == 0) {
               continue;
            }

            for (int direction = 0; direction < 2; direction++) {
               int neighbourX = x + (direction == 0 ? 1 : 0);
               int neighbourY = y + (direction == 1 ? 1 : 0);
               if (neighbourX >= width || neighbourY >= height) {
                  continue;
               }

               int neighbour = Byte.toUnsignedInt(classes[neighbourY * width + neighbourX]);
               if (neighbour == 0 || isCold(value) == isCold(neighbour)) {
                  continue;
               }

               double here = this.pixelElevation(elevations, grid, width, pixelWest, pixelNorth, x, y);
               double there = this.pixelElevation(elevations, grid, width, pixelWest, pixelNorth, neighbourX, neighbourY);
               if (!Double.isFinite(here) || !Double.isFinite(there)) {
                  degraded = true;
                  continue;
               }

               double cold = isCold(value) ? here : there;
               double warm = isCold(value) ? there : here;
               double relief = (cold - warm) / PAIR_RELIEF_SCALE_METERS;
               // A cold pixel below its warm neighbour is not an altitude border.
               double weight = (cold < warm - 50.0 ? INVERTED_PAIR_WEIGHT : 1.0) / (1.0 + relief * relief);
               double latitude = 0.5 * (grid.centerLatitude(pixelNorth + y) + grid.centerLatitude(pixelNorth + neighbourY));
               double longitude = 0.5 * (grid.centerLongitude(pixelWest + x) + grid.centerLongitude(pixelWest + neighbourX));
               int row = (int)Math.round((latitude - southLatitude) * NODES_PER_DEGREE);
               int column = (int)Math.round((longitude - westLongitude) * NODES_PER_DEGREE);
               if (row < 0 || row >= rows || column < 0 || column >= columns) {
                  continue;
               }

               weightedElevations[row * columns + column] += weight * 0.5 * (cold + warm);
               weights[row * columns + column] += weight;
               pairs++;
            }
         }
      }
      if (pairs == 0) {
         return degraded ? new LocalTreeline.Tile(null, true, System.nanoTime()) : CURVE_ONLY;
      }

      double sigmaRows = SIGMA_KM / KM_PER_DEGREE * NODES_PER_DEGREE;
      double sigmaColumns = SIGMA_KM / (KM_PER_DEGREE * Math.cos(Math.toRadians(Math.min(85.0, Math.abs(tileLatitude + 0.5))))) * NODES_PER_DEGREE;
      blur(weightedElevations, rows, columns, sigmaRows, sigmaColumns);
      blur(weights, rows, columns, sigmaRows, sigmaColumns);
      int stride = NODES_PER_DEGREE + 1;
      float[] nodes = new float[stride * stride];
      for (int i = 0; i < stride; i++) {
         double curve = MountainElevationZones.treelineMeters(tileLatitude + (double)i / NODES_PER_DEGREE);
         for (int j = 0; j < stride; j++) {
            int index = (i + marginRows) * columns + j + marginColumns;
            double treeline = (weightedElevations[index] + PRIOR_WEIGHT * curve) / (weights[index] + PRIOR_WEIGHT);
            nodes[i * stride + j] = (float)Math.max(curve - MAX_DEVIATION_METERS, Math.min(curve + MAX_DEVIATION_METERS, treeline));
         }
      }
      return new LocalTreeline.Tile(nodes, degraded, System.nanoTime());
   }

   private double pixelElevation(
      float[] elevations, TellusKoppenSource.PixelGrid grid, int width, int pixelWest, int pixelNorth, int x, int y
   ) {
      int index = y * width + x;
      float cached = elevations[index];
      if (cached != Float.NEGATIVE_INFINITY) {
         return cached;
      }

      elevations[index] = this.sharedPixelElevation(grid, pixelWest + x, pixelNorth + y);
      return elevations[index];
   }

   /**
    * Pixel elevation, shared between tiles: neighbouring tiles' windows overlap by
    * their margins, so most border pixels are read by several tiles. Unknown values
    * are not kept, so missing data is read again once it arrives.
    */
   private float sharedPixelElevation(TellusKoppenSource.PixelGrid grid, int pixelX, int pixelY) {
      long key = (long)(pixelY >> ELEVATION_BLOCK_SHIFT) << 32 | (long)(pixelX >> ELEVATION_BLOCK_SHIFT) & 0xFFFFFFFFL;
      float[] block = this.elevationBlocks.getIfPresent(key);
      if (block == null) {
         float[] created = new float[1 << (2 * ELEVATION_BLOCK_SHIFT)];
         java.util.Arrays.fill(created, Float.NEGATIVE_INFINITY);
         float[] raced = this.elevationBlocks.asMap().putIfAbsent(key, created);
         block = raced != null ? raced : created;
      }

      int index = (pixelY & ELEVATION_BLOCK_MASK) << ELEVATION_BLOCK_SHIFT | pixelX & ELEVATION_BLOCK_MASK;
      float cached = block[index];
      if (cached != Float.NEGATIVE_INFINITY) {
         return cached;
      }

      double meters = this.elevation.meters(grid.centerLatitude(pixelY), grid.centerLongitude(pixelX));
      if (!Double.isFinite(meters)) {
         return Float.NaN;
      }

      // Racing writers store the same value.
      block[index] = (float)meters;
      return block[index];
   }

   private static boolean isCold(int classIndex) {
      return classIndex == TellusKoppenSource.CLASS_ET || classIndex == TellusKoppenSource.CLASS_EF;
   }

   /** Separable Gaussian blur with peak weight one, so blurred weights count nearby samples. */
   private static void blur(double[] values, int rows, int columns, double sigmaRows, double sigmaColumns) {
      double[] scratch = new double[values.length];
      double[] rowKernel = kernel(sigmaColumns);
      int rowRadius = rowKernel.length / 2;
      for (int r = 0; r < rows; r++) {
         for (int c = 0; c < columns; c++) {
            double sum = 0.0;
            for (int k = -rowRadius; k <= rowRadius; k++) {
               int source = c + k;
               if (source >= 0 && source < columns) {
                  sum += values[r * columns + source] * rowKernel[k + rowRadius];
               }
            }
            scratch[r * columns + c] = sum;
         }
      }

      double[] columnKernel = kernel(sigmaRows);
      int columnRadius = columnKernel.length / 2;
      for (int c = 0; c < columns; c++) {
         for (int r = 0; r < rows; r++) {
            double sum = 0.0;
            for (int k = -columnRadius; k <= columnRadius; k++) {
               int source = r + k;
               if (source >= 0 && source < rows) {
                  sum += scratch[source * columns + c] * columnKernel[k + columnRadius];
               }
            }
            values[r * columns + c] = sum;
         }
      }
   }

   private static double[] kernel(double sigma) {
      int radius = Math.max(1, (int)Math.ceil(WINDOW_SIGMAS * sigma));
      double[] kernel = new double[2 * radius + 1];
      for (int k = -radius; k <= radius; k++) {
         kernel[k + radius] = Math.exp(-0.5 * k * k / (sigma * sigma));
      }
      return kernel;
   }

   /** Köppen classes on the raster's own pixel grid. */
   public interface ClimateRaster {
      TellusKoppenSource.PixelGrid grid();

      int classIndex(int pixelX, int pixelY);

      /** Class indices of a window, row by row and {@code width} wide. */
      default void readWindow(int pixelX, int pixelY, int width, int height, byte[] out) {
         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               out[y * width + x] = (byte)this.classIndex(pixelX + x, pixelY + y);
            }
         }
      }
   }

   /** Ground elevation in metres at a location, or NaN where unknown. */
   @FunctionalInterface
   public interface ElevationSampler {
      double meters(double latitude, double longitude);
   }

   private record Tile(float[] nodes, boolean degraded, long computedAt) {
   }
}
