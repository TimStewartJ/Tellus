package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in probe of the river map data around a place: line kinds, fragmenting, and whether the line
 * direction agrees with the terrain (OSM waterways are drawn downstream).
 * {@code TELLUS_RIVER_PROBE="label|gameDir|lat|lon|scale|radiusBlocks"}.
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_RIVER_PROBE", matches = ".+")
class RiverDataProbeTest {
   @Test
   void probe() throws Exception {
      for (String area : System.getenv("TELLUS_RIVER_PROBE").split(";")) probe(area.split("\\|"));
   }

   private static void probe(String[] spec) throws Exception {
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]), scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]);
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
      TellusOsmWaterSource source = TellusWorldgenSources.osmWater();
      int cx = (int)Math.floor(WorldProjection.global(scale).lonToBlockX(lon));
      int cz = (int)Math.floor(WorldProjection.global(scale).latToBlockZ(lat));
      TellusOsmWaterSource.WaterQueryResult query = source.waterForAreaWithStatus(cx - radius, cz - radius, cx + radius, cz + radius,
         WorldProjection.global(scale), 0, OsmQueryMode.BLOCKING, 4096, 1);
      System.out.printf(Locale.ROOT, "PROBE label=%s zoom=%d features=%d status=%s%n", label, query.zoom(), query.features().size(), query.coverageStatus());
      Map<OsmWaterKind, int[]> kinds = new EnumMap<>(OsmWaterKind.class);
      Map<Long, Integer> fragments = new HashMap<>();
      List<OsmWaterFeature> lines = new ArrayList<>();
      for (OsmWaterFeature f : query.features()) {
         int[] counts = kinds.computeIfAbsent(f.kind(), k -> new int[3]);
         counts[f.lineGeometry() ? 0 : f.pointGeometry() ? 2 : 1]++;
         if (f.lineGeometry() && f.flowingWater()) { lines.add(f); fragments.merge(f.featureId(), 1, Integer::sum); }
      }
      kinds.forEach((k, c) -> System.out.printf(Locale.ROOT, "PROBE_KIND %s lines=%d polygons=%d points=%d%n", k, c[0], c[1], c[2]));
      int multi = 0;
      for (int count : fragments.values()) if (count > 1) multi++;
      System.out.printf(Locale.ROOT, "PROBE_IDS lineIds=%d multiFragmentIds=%d%n", fragments.size(), multi);
      // Direction: elevation fall along each line part, from points every few blocks.
      int down = 0, up = 0, flat = 0;
      double downLength = 0, upLength = 0;
      Map<OsmWaterKind, int[]> directionByKind = new EnumMap<>(OsmWaterKind.class);
      int printed = 0;
      for (OsmWaterFeature f : lines) {
         for (int p = 0; p < f.partCount(); p++) {
            int n = f.pointCount(p);
            double[] xs = new double[n], zs = new double[n];
            for (int i = 0; i < n; i++) {
               xs[i] = f.lonAt(p, i) * WorldProjection.global(scale).equatorialBlocksPerDegree();
               zs[i] = WorldProjection.global(scale).latToBlockZ(f.latAt(p, i));
            }
            double length = 0;
            for (int i = 1; i < n; i++) length += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            if (length < 64) continue;
            // Robust ends: the lowest of five samples around the first and last 15% of the line.
            double start = sampleAlong(elevation, resolver, xs, zs, length, 0.0, 0.15);
            double end = sampleAlong(elevation, resolver, xs, zs, length, 0.85, 1.0);
            double fall = start - end;
            int[] byKind = directionByKind.computeIfAbsent(f.kind(), k -> new int[3]);
            double noise = 3.0;
            if (fall > noise) { down++; downLength += length; byKind[0]++; }
            else if (fall < -noise) {
               up++; upLength += length; byKind[1]++;
               if (printed++ < 12) System.out.printf(Locale.ROOT, "PROBE_UPHILL id=%d kind=%s length=%.0f start=%.1f end=%.1f at=%.0f,%.0f lat=%.5f lon=%.5f%n",
                  f.featureId(), f.kind(), length, start, end, xs[0], zs[0], f.latAt(p, 0), f.lonAt(p, 0));
            }
            else { flat++; byKind[2]++; }
         }
      }
      System.out.printf(Locale.ROOT, "PROBE_DIRECTION downhill=%d (%.0f blocks) uphill=%d (%.0f blocks) flat=%d%n", down, downLength, up, upLength, flat);
      directionByKind.forEach((k, c) -> System.out.printf(Locale.ROOT, "PROBE_DIRECTION_KIND %s down=%d up=%d flat=%d%n", k, c[0], c[1], c[2]));
      // Width tags and fragment ends.
      int tagged = 0;
      for (OsmWaterFeature f : lines) if (Double.isFinite(f.widthMeters())) tagged++;
      System.out.printf(Locale.ROOT, "PROBE_WIDTH tagged=%d of %d%n", tagged, lines.size());
      if (System.getenv("TELLUS_RIVER_PROBE_IDS") != null) {
         // Whether river line identities survive at coarser zooms (same OSM way id, similar geometry).
         java.util.Set<Long> fine = new java.util.HashSet<>();
         for (OsmWaterFeature f : query.features()) if (f.lineGeometry() && f.kind() == OsmWaterKind.RIVER) fine.add(f.featureId());
         for (int zoom = 12; zoom >= 8; zoom--) {
            double tileBlocks = 360.0 * WorldProjection.global(scale).equatorialBlocksPerDegree() / (1 << zoom);
            TellusOsmWaterSource.WaterQueryResult z = source.waterForAreaWithStatus(cx - radius, cz - radius, cx + radius, cz + radius,
               WorldProjection.global(scale), 0, OsmQueryMode.BLOCKING, 4096, (int)Math.floor(tileBlocks * 0.99));
            java.util.Set<Long> coarse = new java.util.HashSet<>();
            int vertices = 0;
            for (OsmWaterFeature f : z.features()) if (f.lineGeometry() && f.kind() == OsmWaterKind.RIVER) {
               coarse.add(f.featureId());
               for (int p = 0; p < f.partCount(); p++) vertices += f.pointCount(p);
            }
            int shared = 0;
            for (long id : coarse) if (fine.contains(id)) shared++;
            System.out.printf(Locale.ROOT, "PROBE_IDZOOM %s zoom=%d riverIds=%d sharedWithZ%d=%d fineIds=%d vertices=%d%n",
               label, z.zoom(), coarse.size(), query.zoom(), shared, fine.size(), vertices);
         }
      }
      if (System.getenv("TELLUS_RIVER_PROBE_ZOOMS") != null) {
         // Which kinds each zoom keeps over the same ground: tiles at least this wide select that zoom.
         for (int zoom = 13; zoom >= 5; zoom--) {
            double tileBlocks = 360.0 * WorldProjection.global(scale).equatorialBlocksPerDegree() / (1 << zoom);
            TellusOsmWaterSource.WaterQueryResult z = source.waterForAreaWithStatus(cx - radius, cz - radius, cx + radius, cz + radius,
               WorldProjection.global(scale), 0, OsmQueryMode.BLOCKING, 4096, (int)Math.floor(tileBlocks * 0.99));
            Map<OsmWaterKind, int[]> byKind = new EnumMap<>(OsmWaterKind.class);
            int widthTags = 0;
            for (OsmWaterFeature f : z.features()) {
               int[] counts = byKind.computeIfAbsent(f.kind(), k -> new int[2]);
               counts[f.lineGeometry() ? 0 : 1]++;
               if (f.lineGeometry() && Double.isFinite(f.widthMeters())) widthTags++;
            }
            StringBuilder line = new StringBuilder();
            byKind.forEach((k, c) -> line.append(' ').append(k).append('=').append(c[0]).append('/').append(c[1]));
            System.out.printf(Locale.ROOT, "PROBE_ZOOM %s requested=%d got=%d features=%d widthTags=%d%s%n", label, zoom, z.zoom(), z.features().size(), widthTags, line);
         }
      }
   }

   private static double sampleAlong(Method elevation, WaterSurfaceResolver resolver, double[] xs, double[] zs, double length,
      double from, double to) throws Exception {
      double best = Double.POSITIVE_INFINITY;
      for (int k = 0; k <= 4; k++) {
         double target = length * (from + (to - from) * k / 4.0);
         double walked = 0;
         for (int i = 1; i < xs.length; i++) {
            double segment = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            if (walked + segment >= target || i == xs.length - 1) {
               double t = segment > 0 ? Math.max(0, Math.min(1, (target - walked) / segment)) : 0;
               int x = (int)Math.round(xs[i - 1] + (xs[i] - xs[i - 1]) * t), z = (int)Math.round(zs[i - 1] + (zs[i] - zs[i - 1]) * t);
               InlandHydrologyPlanner.Elevation e = (InlandHydrologyPlanner.Elevation)elevation.invoke(resolver, x, z);
               if (Double.isFinite(e.height())) best = Math.min(best, e.height());
               break;
            }
            walked += segment;
         }
      }
      return best;
   }
}
