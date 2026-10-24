package com.yucareux.tellus.worldgen.landmark;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/**
 * One entry of {@code tellus/landmarks/landmarks.json}: a landmark mesh, where it stands and what it replaces.
 *
 * @param rotationDegrees counter-clockwise turn of the mesh seen from above (north up), applied about its centre
 * @param bandTops        top of each material band as a fraction of the landmark's height, ascending, the last one 1;
 *                        only used by meshes without their own blocks
 * @param bandBlocks      block id path (minecraft namespace) of each band
 * @param seaLevelAnchor  whether the mesh stands on the world's sea level, its base {@code baseElevationMeters} below
 *                        or above it, instead of on the median ground of its base; for structures over water such as
 *                        bridges
 * @param baseElevationMeters real elevation of the mesh's base (y = 0) when anchored to sea level
 * @param suppressBuildingIds Overture building ids (footprints, and parts through their building id) the landmark replaces
 * @param suppressMarginMeters the landmark also replaces every building whose centroid lies within its base (see
 *                             {@code footprintSuppression}) grown by this many metres
 * @param footprintSuppression whether "within its base" means the ground the mesh covers seen from above, instead of
 *                             the mesh's bounding rectangle; for long or open shapes such as bridges
 * @param replacesBridgeRoads whether the map's bridge roads that run along the landmark are left out, so the
 *                            landmark's own deck carries them
 * @param flattenGround   whether the ground under the base rectangle is levelled to the landmark's base
 */
public record LandmarkDefinition(
   String id,
   String name,
   double latitude,
   double longitude,
   double rotationDegrees,
   String mesh,
   double[] bandTops,
   String[] bandBlocks,
   boolean seaLevelAnchor,
   double baseElevationMeters,
   Set<String> suppressBuildingIds,
   double suppressMarginMeters,
   boolean footprintSuppression,
   boolean replacesBridgeRoads,
   boolean flattenGround
) {
   static LandmarkDefinition fromJson(JsonObject json) {
      String id = json.get("id").getAsString();
      JsonArray materials = json.has("materials") ? json.getAsJsonArray("materials") : new JsonArray();
      double[] bandTops = new double[Math.max(1, materials.size())];
      String[] bandBlocks = new String[bandTops.length];
      bandTops[0] = 1.0;
      bandBlocks[0] = "stone";
      for (int i = 0; i < materials.size(); i++) {
         JsonObject band = materials.get(i).getAsJsonObject();
         bandTops[i] = i == materials.size() - 1 ? 1.0 : band.get("below").getAsDouble();
         bandBlocks[i] = band.get("block").getAsString();
      }
      Set<String> suppress = new HashSet<>();
      JsonArray ids = json.has("suppress_overture_buildings") ? json.getAsJsonArray("suppress_overture_buildings") : new JsonArray();
      for (JsonElement element : ids) {
         suppress.add(element.getAsString());
      }
      String anchor = json.has("anchor") ? json.get("anchor").getAsString() : "ground";
      String suppressArea = json.has("suppress_area") ? json.get("suppress_area").getAsString() : "rectangle";
      return new LandmarkDefinition(
         id,
         json.has("name") ? json.get("name").getAsString() : id,
         json.get("latitude").getAsDouble(),
         json.get("longitude").getAsDouble(),
         json.has("rotation_degrees") ? json.get("rotation_degrees").getAsDouble() : 0.0,
         json.get("mesh").getAsString(),
         bandTops,
         bandBlocks,
         "sea_level".equals(anchor),
         json.has("base_elevation_meters") ? json.get("base_elevation_meters").getAsDouble() : 0.0,
         Set.copyOf(suppress),
         json.has("suppress_margin_meters") ? json.get("suppress_margin_meters").getAsDouble() : 2.0,
         "footprint".equals(suppressArea),
         json.has("replace_bridge_roads") && json.get("replace_bridge_roads").getAsBoolean(),
         !json.has("flatten_ground") || json.get("flatten_ground").getAsBoolean()
      );
   }

   /** Index of the material band holding {@code fraction} of the landmark's height. */
   int bandAt(double fraction) {
      for (int i = 0; i < this.bandTops.length - 1; i++) {
         if (fraction < this.bandTops[i]) {
            return i;
         }
      }
      return this.bandTops.length - 1;
   }
}
