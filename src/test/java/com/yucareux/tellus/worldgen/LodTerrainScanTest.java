package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.LodTerrainMetrics;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/lod-scan}): compares the slope and convexity that Distant
 * Horizons LODs measure at each detail level with the ones real chunks measure, over a real area.
 *
 * <p>Real chunks measure over four blocks on full-resolution heights. A LOD cell of {@code 2^d}
 * blocks samples coarser elevation data at its centre and its neighbours. For random cells of each
 * level the scan records the cell's metrics and those of random real-chunk columns inside it, and
 * reports how the column metrics are distributed for each cell slope.</p>
 *
 * <p>{@code TELLUS_LOD_SCAN="label|gameDir|lat|lon|scale|radiusBlocks"}; tables go to
 * {@code TELLUS_LOD_SCAN_OUT}/label.tsv. Missing map or DEM tiles are downloaded as in game.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_LOD_SCAN", matches = ".+")
class LodTerrainScanTest {
   private static final int MAX_DETAIL = 8;
   private static final int CELLS_PER_LEVEL = 20000;
   private static final int COLUMNS_PER_CELL = 16;
   private static final int SLOPE_BINS = 48;
   private static final int CONVEXITY_BINS = 48;
   private static final int SLOPE_HISTOGRAM = 16;
   private static final int CONVEXITY_HISTOGRAM = 17;

   @Test
   void scan() throws Exception {
      String[] spec = System.getenv("TELLUS_LOD_SCAN").split("\\|");
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]), scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]);
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_LOD_SCAN_OUT", "build/lod-scan"));
      Files.createDirectories(out);

      JsonObject json = new JsonObject();
      json.addProperty("spawn_latitude", lat);
      json.addProperty("spawn_longitude", lon);
      json.addProperty("world_scale", scale);
      json.addProperty("experimental_increase_height", true);
      json.addProperty("experimental_height_coordinate_profile", HighYPackedCoordinateProfile.PROFILE_ID);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      Heights heights = new Heights(TellusWorldgenSources.elevation(), settings);
      int centerX = (int)Math.floor(WorldProjection.global(scale).lonToBlockX(lon));
      int centerZ = (int)Math.floor(WorldProjection.global(scale).latToBlockZ(lat));
      System.out.printf(Locale.ROOT, "LODSCAN_START label=%s lat=%.4f lon=%.4f scale=%.1f radius=%d center=%d,%d%n",
         label, lat, lon, scale, radius, centerX, centerZ);

      try (
         PrintWriter tsv = new PrintWriter(Files.newBufferedWriter(out.resolve(label + ".tsv")));
         PrintWriter cells = new PrintWriter(Files.newBufferedWriter(out.resolve(label + ".cells.csv")))
      ) {
         for (int detail = 0; detail <= MAX_DETAIL; detail++) {
            long started = System.nanoTime();
            Level level = scanLevel(heights, scale, detail, centerX, centerZ, radius);
            level.report(label, (System.nanoTime() - started) / 1e9);
            level.write(tsv);
            level.dump(cells);
         }
      }
   }

   private static Level scanLevel(Heights heights, double scale, int detail, int centerX, int centerZ, int radius) {
      int cell = 1 << detail;
      double resolution = scale * cell;
      // Neighbour distance of the LOD metric: four blocks for fine cells, one cell otherwise.
      int span = cell >= 4 ? cell : 4;
      int minCellX = Math.floorDiv(centerX - radius, cell), maxCellX = Math.floorDiv(centerX + radius, cell) - 1;
      int minCellZ = Math.floorDiv(centerZ - radius, cell), maxCellZ = Math.floorDiv(centerZ + radius, cell) - 1;
      long width = maxCellX - minCellX + 1L, height = maxCellZ - minCellZ + 1L;
      int count = (int)Math.min(CELLS_PER_LEVEL, width * height);
      int columns = Math.min(COLUMNS_PER_CELL, cell * cell);
      SplittableRandom random = new SplittableRandom(0x10D5CA9L + detail);
      int[] cellXs = new int[count], cellZs = new int[count];
      long[] seeds = new long[count];
      for (int i = 0; i < count; i++) {
         long pick = count == width * height ? i : random.nextLong(width * height);
         cellXs[i] = (int)(minCellX + pick % width);
         cellZs[i] = (int)(minCellZ + pick / width);
         seeds[i] = random.nextLong();
      }

      Level level = new Level(detail, cell, count, columns);
      LodTerrainMetrics.Level metrics = LodTerrainMetrics.level(scale, cell);
      IntStream.range(0, count).parallel().forEach(i -> {
         int x = cellXs[i] * cell + (cell >> 1), z = cellZs[i] * cell + (cell >> 1);
         int center = heights.height(x, z, resolution);
         int east = heights.height(x + span, z, resolution), west = heights.height(x - span, z, resolution);
         int north = heights.height(x, z - span, resolution), south = heights.height(x, z + span, resolution);
         if (center == Heights.MISSING || east == Heights.MISSING || west == Heights.MISSING || north == Heights.MISSING || south == Heights.MISSING) {
            return;
         }
         int maxDiff = Math.max(Math.max(Math.abs(east - center), Math.abs(west - center)), Math.max(Math.abs(north - center), Math.abs(south - center)));
         int neighbourMean = (east + west + north + south) / 4;
         int low = Math.min(center, Math.min(Math.min(east, west), Math.min(north, south)));
         int high = Math.max(center, Math.max(Math.max(east, west), Math.max(north, south)));
         // The runtime metrics on the same samples: a one-cell grid with its reach margin.
         int reach = LodTerrainMetrics.reach(cell);
         int gridWidth = 1 + 2 * reach;
         int middle = reach * gridWidth + reach;
         int[] grid = new int[gridWidth * gridWidth];
         Arrays.fill(grid, center);
         grid[middle + reach] = east;
         grid[middle - reach] = west;
         grid[middle - reach * gridWidth] = north;
         grid[middle + reach * gridWidth] = south;
         for (int corner = 0; corner < 4; corner++) {
            int cornerHeight = heights.height(x + ((corner & 1) == 0 ? -span : span), z + ((corner & 2) == 0 ? -span : span), resolution);
            if (cornerHeight != Heights.MISSING) {
               low = Math.min(low, cornerHeight);
               high = Math.max(high, cornerHeight);
               grid[middle + ((corner & 1) == 0 ? -reach : reach) + ((corner & 2) == 0 ? -reach : reach) * gridWidth] = cornerHeight;
            }
         }
         int[] drawnSlope = new int[1], drawnConvexity = new int[1];
         LodTerrainMetrics.fill(grid, 1, cell, metrics, new int[]{x}, new int[]{z}, drawnSlope, drawnConvexity);
         level.cellSlopeNew[i] = drawnSlope[0];
         level.cellConvexityNew[i] = drawnConvexity[0];
         level.cellRelief[i] = (high - low) * 2.0 / span;
         level.cellSlope[i] = maxDiff * 4.0 / span;
         level.cellConvexity[i] = ((east + west + north + south) / 4.0 - center) * 4.0 / span;
         level.cellSlopeInt[i] = maxDiff * 4 / span;
         level.cellConvexityInt[i] = (neighbourMean - center) * 4 / span;
         level.cellSlopeRaw[i] = maxDiff;
         level.cellConvexityRaw[i] = neighbourMean - center;
         level.valid[i] = true;
         SplittableRandom columnRandom = new SplittableRandom(seeds[i]);
         for (int j = 0; j < columns; j++) {
            int offsetX, offsetZ;
            if (columns == cell * cell) {
               offsetX = j % cell - (cell >> 1);
               offsetZ = j / cell - (cell >> 1);
            } else {
               offsetX = columnRandom.nextInt(cell) - (cell >> 1);
               offsetZ = columnRandom.nextInt(cell) - (cell >> 1);
            }
            int cx = x + offsetX, cz = z + offsetZ;
            int c = heights.height(cx, cz, scale);
            int e = heights.height(cx + 4, cz, scale), w = heights.height(cx - 4, cz, scale);
            int n = heights.height(cx, cz - 4, scale), s = heights.height(cx, cz + 4, scale);
            int index = i * columns + j;
            if (c == Heights.MISSING || e == Heights.MISSING || w == Heights.MISSING || n == Heights.MISSING || s == Heights.MISSING) {
               level.columnSlope[index] = -1;
               continue;
            }
            level.columnSlope[index] = Math.max(Math.max(Math.abs(e - c), Math.abs(w - c)), Math.max(Math.abs(n - c), Math.abs(s - c)));
            level.columnConvexity[index] = (e + w + n + s) / 4 - c;
         }
      });
      return level;
   }

   /** Surface heights as the generator computes them from the DEM, or {@link #MISSING} off land. */
   private static final class Heights {
      static final int MISSING = Integer.MIN_VALUE;
      private final TellusElevationSource elevation;
      private final EarthGeneratorSettings settings;

      Heights(TellusElevationSource elevation, EarthGeneratorSettings settings) {
         this.elevation = elevation;
         this.settings = settings;
      }

      int height(int x, int z, double resolution) {
         double meters = this.elevation.samplePreviewElevationMeters(
            x, z, this.settings.projection(), false, this.settings.demSelection(), resolution
         );
         if (!Double.isFinite(meters) || meters <= 0.0) {
            return MISSING;
         }
         return TerrainHeightTransform.blockOffset(
            meters,
            z,
            this.settings.projection(),
            this.settings.effectiveTerrestrialHeightScale(),
            this.settings.effectiveOceanicHeightScale(),
            this.settings.experimentalIncreaseHeight(),
            this.settings.automaticHeightScaling()
         ) + this.settings.effectiveHeightOffset();
      }
   }

   private static final class Level {
      final int detail, cell, count, columns;
      final double[] cellSlope, cellConvexity, cellRelief;
      final int[] cellSlopeInt, cellConvexityInt, cellSlopeRaw, cellConvexityRaw, cellSlopeNew, cellConvexityNew;
      final boolean[] valid;
      final int[] columnSlope, columnConvexity;

      Level(int detail, int cell, int count, int columns) {
         this.detail = detail;
         this.cell = cell;
         this.count = count;
         this.columns = columns;
         this.cellSlope = new double[count];
         this.cellConvexity = new double[count];
         this.cellRelief = new double[count];
         this.cellSlopeInt = new int[count];
         this.cellConvexityInt = new int[count];
         this.cellSlopeRaw = new int[count];
         this.cellConvexityRaw = new int[count];
         this.cellSlopeNew = new int[count];
         this.cellConvexityNew = new int[count];
         this.valid = new boolean[count];
         this.columnSlope = new int[count * columns];
         this.columnConvexity = new int[count * columns];
      }

      /**
       * Real-chunk columns against the LOD cells' runtime metrics, and against the previous LOD
       * metrics (cell-scaled, or unscaled over the cell for highland cells at levels 3-6), as
       * slope shares and as what the surface rules paint for a few cover and zone settings.
       */
      void report(String label, double seconds) {
         double[] columnShares = new double[SLOPE_HISTOGRAM], newShares = new double[SLOPE_HISTOGRAM], cellShares = new double[SLOPE_HISTOGRAM];
         double[] columnConvexityShares = new double[3], newConvexityShares = new double[3];
         double[][] chunks = new double[SCENARIOS][], lodNew = new double[SCENARIOS][], lodOld = new double[SCENARIOS][];
         for (int k = 0; k < SCENARIOS; k++) {
            chunks[k] = new double[OUTCOMES];
            lodNew[k] = new double[OUTCOMES];
            lodOld[k] = new double[OUTCOMES];
         }
         boolean oldUnscaled = this.detail >= 3 && this.detail <= 6;
         int columnTotal = 0, cellTotal = 0;
         for (int i = 0; i < this.count; i++) {
            if (!this.valid[i]) continue;
            cellTotal++;
            newShares[Math.min(SLOPE_HISTOGRAM - 1, this.cellSlopeNew[i])]++;
            cellShares[Math.min(SLOPE_HISTOGRAM - 1, this.cellSlopeInt[i])]++;
            newConvexityShares[convexityClass(this.cellConvexityNew[i])]++;
            int x = i * 7919, z = i * 104729;
            for (int k = 0; k < SCENARIOS; k++) {
               boolean highland = SCENARIO_ZONES[k] >= MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA;
               paint(lodNew[k], k, this.cellSlopeNew[i], this.cellConvexityNew[i], x, z);
               paint(lodOld[k], k, oldUnscaled && highland ? this.cellSlopeRaw[i] : this.cellSlopeInt[i],
                  oldUnscaled && highland ? this.cellConvexityRaw[i] : this.cellConvexityInt[i], x, z);
            }
            for (int j = 0; j < this.columns; j++) {
               int index = i * this.columns + j;
               if (this.columnSlope[index] < 0) continue;
               columnTotal++;
               columnShares[Math.min(SLOPE_HISTOGRAM - 1, this.columnSlope[index])]++;
               columnConvexityShares[convexityClass(this.columnConvexity[index])]++;
               for (int k = 0; k < SCENARIOS; k++) {
                  paint(chunks[k], k, this.columnSlope[index], this.columnConvexity[index], x + j, z + j);
               }
            }
         }
         System.out.printf(Locale.ROOT, "LODSCAN_LEVEL label=%s detail=%d cell=%d cells=%d columns=%d seconds=%.1f%n",
            label, this.detail, this.cell, cellTotal, columnTotal, seconds);
         System.out.println("LODSCAN_SHARES detail=" + this.detail + " chunks   " + atLeast(columnShares, columnTotal) + convexity(columnConvexityShares, columnTotal));
         System.out.println("LODSCAN_SHARES detail=" + this.detail + " lod-new  " + atLeast(newShares, cellTotal) + convexity(newConvexityShares, cellTotal));
         System.out.println("LODSCAN_SHARES detail=" + this.detail + " scaled   " + atLeast(cellShares, cellTotal));
         for (int k = 0; k < SCENARIOS; k++) {
            System.out.printf(Locale.ROOT, "LODSCAN_PAINT detail=%d %-14s chunks %s | new %s | old %s%n", this.detail, SCENARIO_NAMES[k],
               outcomes(chunks[k], columnTotal), outcomes(lodNew[k], cellTotal), outcomes(lodOld[k], cellTotal));
         }
      }

      // Cover and zone height per scenario: bare ground above the alpine anchor, grass in the
      // highland band and grass in the lowlands.
      private static final int SCENARIOS = 3;
      private static final String[] SCENARIO_NAMES = {"bare-alpine", "grass-highland", "grass-lowland"};
      private static final int[] SCENARIO_COVERS = {MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_GRASSLAND};
      private static final int[] SCENARIO_ZONES = {210, 150, 60};
      // cliff, steep rock, loose debris (scree, gullies, hollows), ridge, valley wall, meadow, meadow scree, outcrop
      private static final int OUTCOMES = 8;

      private static void paint(double[] tally, int scenario, int slope, int convexity, int x, int z) {
         MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
            SCENARIO_COVERS[scenario], SCENARIO_COVERS[scenario], SCENARIO_ZONES[scenario], slope, convexity, false, x, z
         );
         MountainSurfaceRules.MountainForm form = surface.form();
         if (surface.isMountain()) {
            switch (form) {
               case CLIFF_FACE -> tally[0]++;
               case STEEP_ROCKY_SLOPE -> tally[1]++;
               case TALUS_SLOPE, DRAINAGE_CHUTE, GLACIER_BASIN -> tally[2]++;
               case RIDGE -> tally[3]++;
               default -> tally[4]++;
            }
         } else if (form == MountainSurfaceRules.MountainForm.ALPINE_MEADOW) {
            tally[5]++;
            tally[6] += MountainSurfaceRules.alpineMeadowScreePercent(slope) / 100.0;
         } else {
            tally[7] += MountainSurfaceRules.exposedRockPercent(slope) / 100.0;
         }
      }

      private static String outcomes(double[] tally, int total) {
         String[] names = {"cliff", "steep", "debris", "ridge", "wall", "meadow", "scree", "rock"};
         StringBuilder builder = new StringBuilder();
         for (int i = 0; i < OUTCOMES; i++) {
            if (tally[i] > 0.0 || i == 0 || i == 7) {
               builder.append(String.format(Locale.ROOT, "%s %4.1f ", names[i], total == 0 ? 0.0 : 100.0 * tally[i] / total));
            }
         }
         return builder.toString().trim();
      }

      /** Column metric histograms for each cell slope bin and cell convexity bin. */
      void write(PrintWriter tsv) {
         long[][] slopeBySlope = new long[SLOPE_BINS][SLOPE_HISTOGRAM];
         long[][] convexityBySlope = new long[SLOPE_BINS][CONVEXITY_HISTOGRAM];
         long[][] jointBySlope = new long[SLOPE_BINS][7 * 3];
         long[][] convexityByConvexity = new long[CONVEXITY_BINS][CONVEXITY_HISTOGRAM];
         long[][] slopeByConvexity = new long[CONVEXITY_BINS][SLOPE_HISTOGRAM];
         for (int i = 0; i < this.count; i++) {
            if (!this.valid[i]) continue;
            int slopeBin = Math.min(SLOPE_BINS - 1, (int)Math.floor(this.cellSlope[i] * 4.0));
            int convexityBin = Math.max(0, Math.min(CONVEXITY_BINS - 1, (int)Math.floor(this.cellConvexity[i] * 4.0) + CONVEXITY_BINS / 2));
            for (int j = 0; j < this.columns; j++) {
               int index = i * this.columns + j;
               int slope = this.columnSlope[index];
               if (slope < 0) continue;
               int convexity = this.columnConvexity[index];
               int slopeIndex = Math.min(SLOPE_HISTOGRAM - 1, slope);
               int convexityIndex = Math.max(0, Math.min(CONVEXITY_HISTOGRAM - 1, convexity + CONVEXITY_HISTOGRAM / 2));
               slopeBySlope[slopeBin][slopeIndex]++;
               convexityBySlope[slopeBin][convexityIndex]++;
               jointBySlope[slopeBin][Math.min(6, slope) * 3 + convexityClass(convexity)]++;
               convexityByConvexity[convexityBin][convexityIndex]++;
               slopeByConvexity[convexityBin][slopeIndex]++;
            }
         }
         for (int bin = 0; bin < SLOPE_BINS; bin++) {
            tsv.println(row(this.detail, "slope|slope", bin, slopeBySlope[bin]));
            tsv.println(row(this.detail, "convexity|slope", bin, convexityBySlope[bin]));
            tsv.println(row(this.detail, "joint|slope", bin, jointBySlope[bin]));
         }
         for (int bin = 0; bin < CONVEXITY_BINS; bin++) {
            tsv.println(row(this.detail, "convexity|convexity", bin, convexityByConvexity[bin]));
            tsv.println(row(this.detail, "slope|convexity", bin, slopeByConvexity[bin]));
         }
         tsv.flush();
      }

      /** One line per cell: detail, cell slope, convexity and relief, then each column's slope and convexity. */
      void dump(PrintWriter cells) {
         for (int i = 0; i < this.count; i++) {
            if (!this.valid[i]) continue;
            StringBuilder builder = new StringBuilder().append(this.detail).append(',')
               .append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f", this.cellSlope[i], this.cellConvexity[i], this.cellRelief[i]));
            for (int j = 0; j < this.columns; j++) {
               int index = i * this.columns + j;
               builder.append(',').append(this.columnSlope[index]).append(',').append(this.columnSlope[index] < 0 ? 0 : this.columnConvexity[index]);
            }
            cells.println(builder);
         }
         cells.flush();
      }

      private static String row(int detail, String table, int bin, long[] values) {
         StringBuilder builder = new StringBuilder().append(detail).append('\t').append(table).append('\t').append(bin);
         for (long value : values) builder.append('\t').append(value);
         return builder.toString();
      }

      private static int convexityClass(int convexity) {
         return convexity <= -2 ? 0 : convexity >= 1 ? 2 : 1;
      }

      private static String atLeast(double[] histogram, int total) {
         StringBuilder builder = new StringBuilder();
         for (int threshold = 2; threshold <= 6; threshold++) {
            double share = 0.0;
            for (int i = threshold; i < histogram.length; i++) share += histogram[i];
            builder.append(String.format(Locale.ROOT, " >=%d:%5.1f%%", threshold, total == 0 ? 0.0 : 100.0 * share / total));
         }
         return builder.toString();
      }

      private static String convexity(double[] classes, int total) {
         return total == 0 ? "" : String.format(Locale.ROOT, "  conv<=-2:%5.1f%% conv>=1:%5.1f%%",
            100.0 * classes[0] / total, 100.0 * classes[2] / total);
      }
   }
}
