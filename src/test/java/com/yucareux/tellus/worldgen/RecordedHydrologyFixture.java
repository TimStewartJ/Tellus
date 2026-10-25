package com.yucareux.tellus.worldgen;

import com.google.gson.*;
import com.yucareux.tellus.world.data.osm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Fixed real-world map/DEM captures; tests never access the network or game cache. */
final class RecordedHydrologyFixture implements InlandWaterInput, InlandHydrologyPlanner.TerrainSampler {
   final int minX, minZ, step, size;
   final double resolution;
   final double[] heights;
   final List<OsmWaterFeature> features = new ArrayList<>();
   final List<HydrologyWaterMap.Shape> shapes = new ArrayList<>();

   RecordedHydrologyFixture(String name) throws IOException {
      JsonObject data;
      try (var stream = getClass().getResourceAsStream("/inland-hydrology/" + name + ".json.gz")) {
         if (stream == null) throw new IOException("Missing hydrology fixture " + name);
         try (var reader = new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8)) {
            data = JsonParser.parseReader(reader).getAsJsonObject();
         }
      }
      minX = data.get("minX").getAsInt(); minZ = data.get("minZ").getAsInt();
      step = data.get("step").getAsInt(); size = data.get("size").getAsInt();
      resolution = data.get("resolutionMeters").getAsDouble();
      JsonArray elevations = data.getAsJsonArray("elevationMeters"); heights = new double[elevations.size()];
      for (int i = 0; i < heights.length; i++) heights[i] = elevations.get(i).getAsDouble();
      for (JsonElement value : data.getAsJsonArray("features")) {
         JsonObject f = value.getAsJsonObject(); JsonArray parts = f.getAsJsonArray("parts");
         double[][] lon = new double[parts.size()][], lat = new double[parts.size()][];
         for (int p = 0; p < parts.size(); p++) {
            JsonArray points = parts.get(p).getAsJsonArray(); lon[p] = new double[points.size()]; lat[p] = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
               JsonArray point = points.get(i).getAsJsonArray();
               lon[p][i] = WorldProjection.global(1).blockXToLon(point.get(0).getAsDouble());
               lat[p][i] = WorldProjection.global(1).blockZToLat(point.get(1).getAsDouble());
            }
         }
         int geometry = f.get("geom").getAsInt(); long id = f.get("id").getAsLong();
         OsmWaterFeature feature = geometry == 2 ? OsmWaterFeature.waterfallMarker(id, lon[0][0], lat[0][0])
            : new OsmWaterFeature(id, geometry == 1, f.get("ocean").getAsInt() != 0,
               OsmWaterKind.fromOrdinal(f.get("kind").getAsInt()), lon, lat);
         features.add(feature); shapes.add(new HydrologyWaterMap.Shape(feature, WorldProjection.global(1)));
      }
   }

   public InlandHydrologyPlanner.Elevation sample(int x, int z) {
      double gx = Math.max(0, Math.min(size - 1.001, (x - (double)minX) / step));
      double gz = Math.max(0, Math.min(size - 1.001, (z - (double)minZ) / step));
      int ix = (int)gx, iz = (int)gz; double fx = gx - ix, fz = gz - iz;
      double height = (heights[iz * size + ix] * (1 - fx) + heights[iz * size + ix + 1] * fx) * (1 - fz)
         + (heights[(iz + 1) * size + ix] * (1 - fx) + heights[(iz + 1) * size + ix + 1] * fx) * fz;
      double correction = WorldProjection.global(1).heightScaleCorrection(z);
      return new InlandHydrologyPlanner.Elevation(64 + height * correction, resolution * correction);
   }

   InlandHydrologyPlanner planner() {
      return new InlandHydrologyPlanner(this, WorldProjection.global(1), 24, this, (x, z) -> Integer.MIN_VALUE, (x, z) -> true,
         (m, z) -> m * WorldProjection.global(1).heightScaleCorrection(z));
   }
   public TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(int x, int z, int xx, int zz,
      WorldProjection scale, int margin, OsmQueryMode mode) {
      List<OsmWaterFeature> selected = new ArrayList<>();
      for (HydrologyWaterMap.Shape s : shapes)
         if (s.maxX >= x - margin && s.minX <= xx + margin && s.maxZ >= z - margin && s.minZ <= zz + margin) selected.add(s.feature);
      return result(selected);
   }
   public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale) {
      return result(features.stream().filter(f -> f.featureId() == seed.featureId()).toList());
   }
   public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale) {
      return connectedWaterBody(seed, scale);
   }
   private static TellusOsmWaterSource.WaterQueryResult result(List<OsmWaterFeature> features) {
      return new TellusOsmWaterSource.WaterQueryResult(features, TellusOsmWaterSource.CoverageStatus.COMPLETE, 13);
   }
}
