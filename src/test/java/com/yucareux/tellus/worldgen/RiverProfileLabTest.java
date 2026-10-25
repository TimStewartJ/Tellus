package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in lab for river level profiles on real map data (see tools/river-lab). Builds the river graph of an
 * area and compares profile operators: uphill steps along the flow, carving below and water above the
 * terrain, with a level map and the longitudinal profile of the longest river.
 * {@code TELLUS_RIVER_LAB="label|gameDir|lat|lon|scale|radiusBlocks|zoom|cellBlocks|kinds;..."}.
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_RIVER_LAB", matches = ".+")
class RiverProfileLabTest {
   @Test
   void lab() throws Exception {
      for (String area : System.getenv("TELLUS_RIVER_LAB").split(";")) run(area.split("\\|"));
   }

   private static void run(String[] spec) throws Exception {
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]), scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]), zoom = Integer.parseInt(spec[6]), cellSize = Integer.parseInt(spec[7]);
      String kinds = spec.length > 8 ? spec[8] : "RIVER";
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_RIVER_LAB_OUT", "build/river-lab")).resolve(label);
      Files.createDirectories(out);
      JsonObject json = new JsonObject();
      json.addProperty("spawn_latitude", lat);
      json.addProperty("spawn_longitude", lon);
      json.addProperty("world_scale", scale);
      json.addProperty("experimental_increase_height", true);
      json.addProperty("experimental_height_coordinate_profile", HighYPackedCoordinateProfile.PROFILE_ID);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      WaterSurfaceResolver resolver = TellusWorldgenSources.waterResolver(settings);
      Method elevation = WaterSurfaceResolver.class.getDeclaredMethod("sampleHydrologyElevation", int.class, int.class);
      elevation.setAccessible(true);
      Method outlet = WaterSurfaceResolver.class.getDeclaredMethod("sampleHydrologyOutlet", int.class, int.class);
      outlet.setAccessible(true);
      TellusOsmWaterSource source = TellusWorldgenSources.osmWater();
      int cx = (int)Math.floor(WorldProjection.global(scale).lonToBlockX(lon));
      int cz = (int)Math.floor(WorldProjection.global(scale).latToBlockZ(lat));
      double tileBlocks = 360.0 * WorldProjection.global(scale).equatorialBlocksPerDegree() / (1 << zoom);
      long started = System.nanoTime();
      TellusOsmWaterSource.WaterQueryResult query = source.waterForAreaWithStatus(cx - radius, cz - radius, cx + radius, cz + radius,
         WorldProjection.global(scale), 0, OsmQueryMode.BLOCKING, 4096, (int)Math.floor(tileBlocks * 0.99));
      double queryTime = (System.nanoTime() - started) / 1e9;
      RiverGraph.Builder builder = new RiverGraph.Builder(cellSize);
      List<OsmWaterFeature> lines = new ArrayList<>();
      for (OsmWaterFeature f : query.features()) {
         if (!f.lineGeometry() || !f.flowingWater() || !kinds.contains(f.kind().name())) continue;
         lines.add(f);
      }
      lines.sort((a, b) -> Long.compare(a.featureId(), b.featureId()));
      double bpd = WorldProjection.global(scale).equatorialBlocksPerDegree();
      for (OsmWaterFeature f : lines) {
         double minX = f.hasTileBounds() ? f.tileWest() * bpd : Double.NEGATIVE_INFINITY;
         double maxX = f.hasTileBounds() ? f.tileEast() * bpd : Double.POSITIVE_INFINITY;
         double minZ = f.hasTileBounds() ? WorldProjection.global(scale).latToBlockZ(f.tileNorth()) : Double.NEGATIVE_INFINITY;
         double maxZ = f.hasTileBounds() ? WorldProjection.global(scale).latToBlockZ(f.tileSouth()) : Double.POSITIVE_INFINITY;
         for (int p = 0; p < f.partCount(); p++) {
            int n = f.pointCount(p);
            double[] xs = new double[n], zs = new double[n];
            for (int i = 0; i < n; i++) { xs[i] = f.lonAt(p, i) * bpd; zs[i] = WorldProjection.global(scale).latToBlockZ(f.latAt(p, i)); }
            builder.addLine(xs, zs, f.featureId(), 1.0, minX, minZ, maxX, maxZ);
         }
      }
      RiverGraph graph = builder.build();
      int links = graph.downStart[graph.count];
      int branches = 0, joins = 0, sources = 0, mouths = 0;
      for (int c = 0; c < graph.count; c++) {
         int d = graph.downStart[c + 1] - graph.downStart[c], u = graph.upStart[c + 1] - graph.upStart[c];
         if (d > 1) branches++;
         if (u > 1) joins++;
         if (u == 0) sources++;
         if (d == 0) mouths++;
      }
      System.out.printf(Locale.ROOT, "LAB %s zoom=%d/%d lines=%d cells=%d links=%d splits=%d joins=%d heads=%d ends=%d query=%.1fs%n",
         label, query.zoom(), zoom, lines.size(), graph.count, links, branches, joins, sources, mouths, queryTime);

      // Terrain observation per cell: each sample is the lowest of the line point and two points across it.
      started = System.nanoTime();
      double[] ground = new double[graph.count];
      double[] fixed = new double[graph.count];
      Arrays.fill(fixed, Double.NaN);
      double resolution = 1.0;
      double searchMeters = Double.parseDouble(System.getenv().getOrDefault("TELLUS_RIVER_LAB_SEARCH", "0"));
      int searchSteps = Integer.parseInt(System.getenv().getOrDefault("TELLUS_RIVER_LAB_SEARCH_STEPS", "1"));
      double mpb = WorldProjection.global(scale).groundMetersPerBlockX(cz);
      for (int c = 0; c < graph.count; c++) {
         int from = graph.sampleStart[c], to = graph.sampleStart[c + 1];
         double[] values = new double[to - from];
         for (int s = from; s < to; s++) {
            double x = graph.sampleX[s], z = graph.sampleZ[s];
            InlandHydrologyPlanner.Elevation e = (InlandHydrologyPlanner.Elevation)elevation.invoke(resolver, (int)Math.round(x), (int)Math.round(z));
            resolution = Math.max(resolution, e.resolutionBlocks());
            double r = searchMeters > 0 ? Math.max(1.0, searchMeters / mpb) : Math.max(1.0, Math.min(cellSize * 0.5, e.resolutionBlocks() * 0.5));
            double best = e.height();
            for (int step = 1; step <= searchSteps; step++) for (int side = -1; side <= 1; side += 2) {
               double d = r * step / searchSteps * side;
               double px = x - graph.sampleDirZ[s] * d, pz = z + graph.sampleDirX[s] * d;
               best = Math.min(best, ((InlandHydrologyPlanner.Elevation)elevation.invoke(resolver, (int)Math.round(px), (int)Math.round(pz))).height());
            }
            values[s - from] = best;
         }
         Arrays.sort(values);
         ground[c] = values.length == 0 ? Double.NaN : values[(values.length - 1) / 4];
         int sea = (Integer)outlet.invoke(resolver, (int)Math.round(graph.centerX(c)), (int)Math.round(graph.centerZ(c)));
         if (sea != Integer.MIN_VALUE) fixed[c] = sea;
      }
      System.out.printf(Locale.ROOT, "LAB %s terrain=%.1fs demResolution=%.1f blocks%n", label, (System.nanoTime() - started) / 1e9, resolution);

      double metersPerBlock = WorldProjection.global(scale).groundMetersPerBlockX(cz);
      DoubleUnaryOperator meters = m -> m / metersPerBlock;
      String[] variants = System.getenv().getOrDefault("TELLUS_RIVER_LAB_VARIANTS", "raw,carve,median-carve,median-fill-carve,leaky").split(",");
      for (String variant : variants) {
         double[] level = RiverLabProfiles.solve(variant, graph, ground, fixed, meters);
         report(label, variant, graph, ground, level, out);
      }
   }

   private static void report(String label, String variant, RiverGraph graph, double[] ground, double[] level, Path out) throws Exception {
      int upSteps = 0, downSteps = 0, upBlocks = 0;
      for (int c = 0; c < graph.count; c++) {
         int here = (int)Math.floor(level[c]);
         for (int k = graph.downStart[c]; k < graph.downStart[c + 1]; k++) {
            int next = (int)Math.floor(level[graph.down[k]]);
            if (next > here) { upSteps++; upBlocks += next - here; }
            else if (next < here) downSteps++;
         }
      }
      double[] carve = new double[graph.count], perch = new double[graph.count];
      int n = 0;
      for (int c = 0; c < graph.count; c++) {
         if (!Double.isFinite(ground[c])) continue;
         carve[n] = Math.max(0, ground[c] - level[c]);
         perch[n] = Math.max(0, level[c] - ground[c]);
         n++;
      }
      Arrays.sort(carve, 0, n);
      Arrays.sort(perch, 0, n);
      System.out.printf(Locale.ROOT, "LAB_PROFILE %s %-18s upSteps=%d upBlocks=%d downSteps=%d carve[p50=%.1f p90=%.1f max=%.1f] perch[p90=%.1f p99=%.1f max=%.1f]%n",
         label, variant, upSteps, upBlocks, downSteps, carve[n / 2], carve[n * 9 / 10], n > 0 ? carve[n - 1] : 0,
         perch[n * 9 / 10], perch[Math.min(n - 1, n * 99 / 100)], n > 0 ? perch[n - 1] : 0);
      renderMap(graph, level, out.resolve(variant + "-map.png"));
      renderProfile(graph, ground, level, out.resolve(variant + "-profile.png"));
   }

   /** Cells coloured by level band (a new colour every block), uphill links in red. */
   private static void renderMap(RiverGraph graph, double[] level, Path file) throws Exception {
      int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (int c = 0; c < graph.count; c++) {
         minX = Math.min(minX, graph.cellX[c]); maxX = Math.max(maxX, graph.cellX[c]);
         minZ = Math.min(minZ, graph.cellZ[c]); maxZ = Math.max(maxZ, graph.cellZ[c]);
      }
      if (graph.count == 0) return;
      int w = maxX - minX + 1, h = maxZ - minZ + 1;
      int zoom = Math.max(1, Math.min(4, 1400 / Math.max(w, h)));
      BufferedImage image = new BufferedImage(w * zoom, h * zoom, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setColor(new Color(30, 34, 30));
      g.fillRect(0, 0, image.getWidth(), image.getHeight());
      for (int c = 0; c < graph.count; c++) {
         int band = Math.floorMod((int)Math.floor(level[c]), 12);
         g.setColor(Color.getHSBColor(band / 12.0F, 0.55F, 0.95F));
         g.fillRect((graph.cellX[c] - minX) * zoom, (graph.cellZ[c] - minZ) * zoom, zoom, zoom);
      }
      g.setColor(Color.RED);
      for (int c = 0; c < graph.count; c++) {
         for (int k = graph.downStart[c]; k < graph.downStart[c + 1]; k++) {
            if (Math.floor(level[graph.down[k]]) > Math.floor(level[c])) {
               int x = (graph.cellX[c] - minX) * zoom, z = (graph.cellZ[c] - minZ) * zoom;
               g.fillRect(x - zoom, z - zoom, zoom * 3, zoom * 3);
            }
         }
      }
      g.dispose();
      ImageIO.write(image, "png", file.toFile());
   }

   /** Terrain observations and levels along the longest downstream path. */
   private static void renderProfile(RiverGraph graph, double[] ground, double[] level, Path file) throws Exception {
      int[] longest = new int[graph.count];
      int[] next = new int[graph.count];
      Arrays.fill(longest, -1);
      int best = -1;
      for (int c = 0; c < graph.count; c++) {
         if (graph.upStart[c + 1] - graph.upStart[c] != 0) continue;
         int length = pathLength(graph, c, longest, next);
         if (best < 0 || length > longest[best]) best = c;
      }
      if (best < 0) return;
      List<Integer> path = new ArrayList<>();
      for (int c = best, guard = 0; c >= 0 && guard < graph.count; c = next[c], guard++) path.add(c);
      int width = 1600, height = 500;
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = image.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, width, height);
      double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
      for (int c : path) {
         if (Double.isFinite(ground[c])) { low = Math.min(low, ground[c]); high = Math.max(high, ground[c]); }
         low = Math.min(low, level[c]); high = Math.max(high, level[c]);
      }
      double range = Math.max(4.0, high - low);
      int n = path.size();
      int[] gx = new int[n], gy = new int[n], lx = new int[n], ly = new int[n];
      for (int i = 0; i < n; i++) {
         int c = path.get(i);
         int x = 20 + (int)Math.round((width - 40) * i / Math.max(1.0, n - 1));
         gx[i] = x; lx[i] = x;
         gy[i] = height - 20 - (int)Math.round((height - 40) * ((Double.isFinite(ground[c]) ? ground[c] : level[c]) - low) / range);
         ly[i] = height - 20 - (int)Math.round((height - 40) * (Math.floor(level[c]) - low) / range);
      }
      g.setColor(new Color(150, 120, 80));
      g.setStroke(new BasicStroke(1.0F));
      g.drawPolyline(gx, gy, n);
      g.setColor(new Color(20, 90, 230));
      g.setStroke(new BasicStroke(2.0F));
      g.drawPolyline(lx, ly, n);
      g.setColor(Color.BLACK);
      g.drawString(String.format(Locale.ROOT, "%d cells, %.0f..%.0f blocks", n, low, high), 25, 18);
      g.dispose();
      ImageIO.write(image, "png", file.toFile());
   }

   private static int pathLength(RiverGraph graph, int start, int[] longest, int[] next) {
      // Iterative longest downstream path (the graph may hold small cycles where lines disagree).
      java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
      byte[] state = new byte[graph.count];
      stack.push(start);
      while (!stack.isEmpty()) {
         int c = stack.peek();
         if (longest[c] >= 0) { stack.pop(); continue; }
         if (state[c] == 0) {
            state[c] = 1;
            for (int k = graph.downStart[c]; k < graph.downStart[c + 1]; k++) {
               int d = graph.down[k];
               if (longest[d] < 0 && state[d] == 0) stack.push(d);
            }
         } else {
            int bestLength = 0, bestNext = -1;
            for (int k = graph.downStart[c]; k < graph.downStart[c + 1]; k++) {
               int d = graph.down[k];
               if (longest[d] >= 0 && longest[d] + 1 > bestLength) { bestLength = longest[d] + 1; bestNext = d; }
            }
            longest[c] = bestLength;
            next[c] = bestNext;
            stack.pop();
         }
      }
      return longest[start];
   }
}
