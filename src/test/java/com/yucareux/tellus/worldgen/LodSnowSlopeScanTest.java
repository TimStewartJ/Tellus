package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.LodSnowSlope;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/lod-scan/run-snow-scan.sh}): the DEM slope the snow rule reads
 * for mapped snow inside Distant Horizons LOD cells, against the slope real chunks read there.
 *
 * <p>For random cells of each level whose centre is mapped snow, the scan records the cell's gradient
 * at the LOD resolution, its height and local treeline, and the full-resolution gradient of random snow
 * columns inside it. It reports how much of that snow the slope bands keep in real chunks, in LODs that
 * read the cell's own slope, and in LODs that draw it with {@link LodSnowSlope}, all with the facing shift.</p>
 *
 * <p>{@code TELLUS_SNOW_SLOPE_SCAN="label|gameDir|lat|lon|radiusBlocks"}. Rows go to
 * {@code TELLUS_LOD_SCAN_OUT}/label.snow.csv as: level, latitude, cell slope, cell rises east and south,
 * cell elevation, treeline, the height range around the cell per metre, then slope and rises of each snow column. Missing tiles are downloaded.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_SNOW_SLOPE_SCAN", matches = ".+")
class LodSnowSlopeScanTest {
   private static final int MAX_DETAIL = 8;
   private static final int CELLS_PER_LEVEL = 12000;
   private static final int CANDIDATES_PER_LEVEL = 240000;
   private static final int COLUMNS_PER_CELL = 16;
   private static final double SCALE = 30.0;

   @Test
   void scan() throws Exception {
      String[] spec = System.getenv("TELLUS_SNOW_SLOPE_SCAN").split("\\|");
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]);
      int radius = Integer.parseInt(spec[4]);
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_LOD_SCAN_OUT", "build/lod-scan"));
      Files.createDirectories(out);

      JsonObject json = new JsonObject();
      json.addProperty("world_scale", SCALE);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      LocalTreeline treeline = LocalTreeline.forSettings(settings);
      TellusElevationSource elevation = TellusWorldgenSources.elevation();
      TellusLandCoverSource cover = TellusWorldgenSources.landCover();
      int centerX = (int)Math.floor(EarthProjection.longitudeToBlockX(lon, SCALE));
      int centerZ = (int)Math.floor(EarthProjection.latToBlockZ(lat, SCALE));
      System.out.printf(Locale.ROOT, "SNOWSLOPE_START label=%s lat=%.4f lon=%.4f radius=%d%n", label, lat, lon, radius);

      try (PrintWriter rows = new PrintWriter(Files.newBufferedWriter(out.resolve(label + ".snow.csv")))) {
         for (int detail = 1; detail <= MAX_DETAIL; detail++) {
            long started = System.nanoTime();
            Cell[] cells = scanLevel(elevation, cover, treeline, detail, centerX, centerZ, radius);
            int count = 0;
            double[] tally = new double[5];
            for (Cell cell : cells) {
               if (cell == null) continue;
               count++;
               rows.println(cell.row());
               cell.tally(tally);
            }
            rows.flush();
            double columns = Math.max(1.0, tally[0]);
            System.out.printf(
               Locale.ROOT,
               "SNOWSLOPE %s d=%d cells=%d columns=%.0f | steeper than 45: real %.1f%% | kept: real %.1f%%, lod reading its cell %.1f%%, lod drawing %.1f%% %.1fs%n",
               label, detail, count, tally[0], 100 * tally[1] / columns, 100 * tally[2] / columns, 100 * tally[3] / columns,
               100 * tally[4] / columns, (System.nanoTime() - started) / 1e9
            );
         }
      }
      reportSnowBiomes(label, elevation, cover, centerX, centerZ, radius);
   }

   /** How the area's mapped snow splits between the snow biomes, read at biome quart corners as chunks do. */
   private static void reportSnowBiomes(
      String label, TellusElevationSource elevation, TellusLandCoverSource cover, int centerX, int centerZ, int radius
   ) {
      int baseline = SnowBiomePolicy.baselineBlocks(SCALE);
      int[] kinds = new int[SnowBiomePolicy.Kind.values().length];
      for (int z = centerZ - radius; z < centerZ + radius; z += 16) {
         double groundX = baseline * EarthProjection.groundMetersPerBlockX(z, SCALE);
         double groundZ = baseline * EarthProjection.groundMetersPerBlockZ(z, SCALE);
         for (int x = centerX - radius; x < centerX + radius; x += 16) {
            if (cover.sampleCoverClass(x, z, SCALE) != MountainSurfaceRules.ESA_SNOW_ICE) continue;
            SnowBiomePolicy.Kind kind = SnowBiomePolicy.classify(
               elevation.sampleElevationMeters(x, z, SCALE),
               elevation.sampleElevationMeters(x + baseline, z, SCALE),
               elevation.sampleElevationMeters(x - baseline, z, SCALE),
               elevation.sampleElevationMeters(x, z - baseline, SCALE),
               elevation.sampleElevationMeters(x, z + baseline, SCALE),
               groundX,
               groundZ
            );
            kinds[kind.ordinal()]++;
         }
      }
      double total = Math.max(1, Arrays.stream(kinds).sum());
      System.out.printf(
         Locale.ROOT,
         "SNOWBIOME %s frozen peaks %.1f%% snowy slopes %.1f%% jagged peaks %.1f%% of %.0f snow quarts%n",
         label,
         100 * kinds[SnowBiomePolicy.Kind.FROZEN_PEAKS.ordinal()] / total,
         100 * kinds[SnowBiomePolicy.Kind.SNOWY_SLOPES.ordinal()] / total,
         100 * kinds[SnowBiomePolicy.Kind.JAGGED_PEAKS.ordinal()] / total,
         total
      );
   }

   private static Cell[] scanLevel(
      TellusElevationSource elevation,
      TellusLandCoverSource cover,
      LocalTreeline treeline,
      int detail,
      int centerX,
      int centerZ,
      int radius
   ) {
      int cell = 1 << detail;
      double resolution = SCALE * cell;
      LodSnowSlope.Level level = LodSnowSlope.level(SCALE, resolution);
      int minCellX = Math.floorDiv(centerX - radius, cell), maxCellX = Math.floorDiv(centerX + radius, cell) - 1;
      int minCellZ = Math.floorDiv(centerZ - radius, cell), maxCellZ = Math.floorDiv(centerZ + radius, cell) - 1;
      long width = maxCellX - minCellX + 1L, height = maxCellZ - minCellZ + 1L;
      int candidates = (int)Math.min(CANDIDATES_PER_LEVEL, width * height);
      SplittableRandom random = new SplittableRandom(0x5A0751L + detail);
      long[] picks = new long[candidates];
      long[] seeds = new long[candidates];
      for (int i = 0; i < candidates; i++) {
         picks[i] = candidates == width * height ? i : random.nextLong(width * height);
         seeds[i] = random.nextLong();
      }

      Cell[] cells = new Cell[candidates];
      AtomicInteger accepted = new AtomicInteger();
      IntStream.range(0, candidates).parallel().forEach(i -> {
         if (accepted.get() >= CELLS_PER_LEVEL) return;
         int originX = (int)(minCellX + picks[i] % width) * cell, originZ = (int)(minCellZ + picks[i] / width) * cell;
         int x = originX + (cell >> 1), z = originZ + (cell >> 1);
         if (cover.sampleCoverClass(x, z, SCALE) != MountainSurfaceRules.ESA_SNOW_ICE) return;
         TerrainSlopePolicy.Neighbourhood neighbourhood = elevation.sampleTerrainNeighbourhoodAtResolution(x, z, SCALE, resolution);
         TerrainSlopePolicy.Gradient cellGradient = neighbourhood.gradient();
         if (!Double.isFinite(cellGradient.slopeDegrees()) || !Double.isFinite(neighbourhood.relief())) return;
         double latitude = EarthProjection.blockZToLat(z, SCALE);
         double treelineMeters = treeline.treelineMeters(latitude, EarthProjection.blockXToLongitude(x, SCALE));
         double drawn = LodSnowSlope.drawSlopeDegrees(
            level, cellGradient.slopeDegrees(), neighbourhood.relief(), cellGradient.centerElevation() - treelineMeters, x, z
         );
         SplittableRandom columns = new SplittableRandom(seeds[i]);
         TerrainSlopePolicy.Gradient[] snow = new TerrainSlopePolicy.Gradient[COLUMNS_PER_CELL];
         int kept = 0;
         for (int c = 0; c < COLUMNS_PER_CELL; c++) {
            int columnX = originX + columns.nextInt(cell), columnZ = originZ + columns.nextInt(cell);
            if (cover.sampleCoverClass(columnX, columnZ, SCALE) != MountainSurfaceRules.ESA_SNOW_ICE) continue;
            TerrainSlopePolicy.Gradient gradient = elevation.sampleTerrainGradientAtResolution(columnX, columnZ, SCALE, SCALE);
            if (Double.isFinite(gradient.slopeDegrees())) {
               snow[kept++] = gradient;
            }
         }
         if (kept > 0 && accepted.incrementAndGet() <= CELLS_PER_LEVEL) {
            cells[i] = new Cell(detail, latitude, cellGradient, neighbourhood.relief(), treelineMeters, drawn, Arrays.copyOf(snow, kept));
         }
      });
      return cells;
   }

   private record Cell(
      int detail,
      double latitude,
      TerrainSlopePolicy.Gradient gradient,
      double relief,
      double treelineMeters,
      double drawnSlope,
      TerrainSlopePolicy.Gradient[] snow
   ) {
      String row() {
         StringBuilder line = new StringBuilder(String.format(
            Locale.ROOT,
            "%d,%.3f,%.2f,%.4f,%.4f,%.0f,%.0f,%.4f",
            this.detail,
            this.latitude,
            this.gradient.slopeDegrees(),
            this.gradient.riseEast(),
            this.gradient.riseSouth(),
            this.gradient.centerElevation(),
            this.treelineMeters,
            this.relief
         ));
         for (TerrainSlopePolicy.Gradient column : this.snow) {
            line.append(String.format(Locale.ROOT, ",%.2f,%.4f,%.4f", column.slopeDegrees(), column.riseEast(), column.riseSouth()));
         }
         return line.toString();
      }

      /** Snow columns; share steeper than 45 degrees; share kept by real chunks, by LODs reading the cell, by LODs drawing. */
      void tally(double[] tally) {
         double facing = this.gradient.polewardFacing(this.latitude);
         double cellKept = kept(SnowSlopePolicy.effectiveSlopeDegrees(this.gradient, this.latitude));
         double drawnKept = kept(SnowSlopePolicy.effectiveSlopeDegrees(this.drawnSlope, facing, this.latitude));
         for (TerrainSlopePolicy.Gradient column : this.snow) {
            tally[0]++;
            tally[1] += column.slopeDegrees() >= 45.0 ? 1 : 0;
            tally[2] += kept(SnowSlopePolicy.effectiveSlopeDegrees(column, this.latitude));
            tally[3] += cellKept;
            tally[4] += drawnKept;
         }
      }

      private static double kept(double slopeDegrees) {
         return SnowSlopePolicy.coveragePercent(slopeDegrees) / 100.0;
      }
   }
}
