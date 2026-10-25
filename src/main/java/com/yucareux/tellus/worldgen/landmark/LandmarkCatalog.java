package com.yucareux.tellus.worldgen.landmark;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The landmarks shipped in {@code tellus/landmarks}: hand-made models that replace the extruded Overture buildings of
 * famous structures. Turn them off with {@code -Dtellus.landmarks=false}.
 */
public final class LandmarkCatalog {
   private static final String ROOT = "/tellus/landmarks/";
   private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("tellus.landmarks", "true"));
   static final double METERS_PER_DEGREE = 111319.49166666667;
   /** Map features farther than this from every landmark are never checked against a mesh (which is read on first use). */
   private static final double MAX_REACH_METERS = 5_000.0;
   /** A bridge road is the landmark's own when at least this share of its length runs over the landmark. */
   private static final double BRIDGE_ROAD_SHARE = 0.5;
   /** How far a bridge road's centreline may stray from the landmark's footprint and still count as on it. */
   private static final double BRIDGE_ROAD_MARGIN_METERS = 8.0;
   /**
    * A gap in a carried road's bridge tagging goes with the landmark only this far from the ends of its longer side: a
    * bridge comes back to the ground there, where its deck can sit below the road the map draws.
    */
   private static final double GAP_INSET_METERS = 100.0;
   private static final ThreadLocal<Boolean> SUPPRESSION_OFF = ThreadLocal.withInitial(() -> Boolean.FALSE);
   private static volatile List<Entry> entries;

   private LandmarkCatalog() {
   }

   public static boolean enabled() {
      return ENABLED;
   }

   static List<Entry> entries() {
      List<Entry> loaded = entries;
      if (loaded == null) {
         synchronized (LandmarkCatalog.class) {
            loaded = entries;
            if (loaded == null) {
               loaded = ENABLED ? load() : List.of();
               entries = loaded;
            }
         }
      }
      return loaded;
   }

   private static List<Entry> load() {
      List<Entry> result = new ArrayList<>();
      try (InputStream stream = LandmarkCatalog.class.getResourceAsStream(ROOT + "landmarks.json")) {
         if (stream == null) {
            return List.of();
         }
         try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("landmarks")) {
               try {
                  result.add(new Entry(LandmarkDefinition.fromJson(element.getAsJsonObject())));
               } catch (RuntimeException error) {
                  Tellus.LOGGER.warn("Skipping malformed landmark entry {}", element, error);
               }
            }
         }
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.warn("Failed to read the Tellus landmark catalog", error);
         return List.of();
      }
      return List.copyOf(result);
   }

   /**
    * Whether a landmark replaces this Overture building: it is one of the landmark's own buildings (or a part of
    * one), or its centroid lies within the landmark's base.
    */
   public static boolean suppressesBuilding(OsmBuildingFeature feature) {
      for (Entry entry : entries()) {
         if (entry.suppresses(feature)) {
            return true;
         }
      }
      return false;
   }

   /** Runs {@code query} with landmark suppression off on this thread; for diagnostics that compare both. */
   static <T> T withoutSuppression(Supplier<T> query) {
      SUPPRESSION_OFF.set(Boolean.TRUE);
      try {
         return query.get();
      } finally {
         SUPPRESSION_OFF.remove();
      }
   }

   /** {@code features} without the buildings a landmark replaces; the same list when none is replaced. */
   public static List<OsmBuildingFeature> withoutSuppressedBuildings(List<OsmBuildingFeature> features) {
      if (features.isEmpty() || entries().isEmpty() || SUPPRESSION_OFF.get()) {
         return features;
      }
      List<OsmBuildingFeature> kept = null;
      for (int i = 0; i < features.size(); i++) {
         OsmBuildingFeature feature = features.get(i);
         if (suppressesBuilding(feature)) {
            if (kept == null) {
               kept = new ArrayList<>(features.subList(0, i));
            }
         } else if (kept != null) {
            kept.add(feature);
         }
      }
      return kept == null ? features : kept;
   }

   /** Whether a landmark carries this road itself: a bridge road running along a landmark that replaces bridge roads. */
   public static boolean replacesRoad(RoadFeature road) {
      if (road.mode() != RoadMode.BRIDGE) {
         return false;
      }
      for (Entry entry : entries()) {
         if (entry.definition().replacesBridgeRoads() && entry.carries(road)) {
            return true;
         }
      }
      return false;
   }

   /**
    * {@code roads} without the bridge roads a landmark carries, nor the other pieces of their ways that run along it
    * (the map splits a road where its bridge tagging has gaps, which would otherwise lie on the ground under the
    * deck); the same list when none is left out.
    */
   public static List<RoadFeature> withoutReplacedRoads(List<RoadFeature> roads) {
      if (roads.isEmpty() || entries().isEmpty() || SUPPRESSION_OFF.get()) {
         return roads;
      }
      boolean[] replaced = null;
      Set<Long> carriedWays = null;
      for (int i = 0; i < roads.size(); i++) {
         RoadFeature road = roads.get(i);
         if (replacesRoad(road)) {
            if (replaced == null) {
               replaced = new boolean[roads.size()];
               carriedWays = new HashSet<>();
            }
            replaced[i] = true;
            // -1 is every road without an id.
            if (road.wayId() != -1L) {
               carriedWays.add(road.wayId());
            }
         }
      }
      if (replaced == null) {
         return roads;
      }
      // shortcut: a gap goes only where the query also holds a carried bridge piece of its way, which a chunk drawing it
      // always does for gaps under twice the 64-block margin (one of its ends is in reach); look per road tile if longer
      // gaps turn up.
      List<RoadFeature> kept = new ArrayList<>(roads.size());
      for (int i = 0; i < roads.size(); i++) {
         RoadFeature road = roads.get(i);
         if (!replaced[i] && !(carriedWays.contains(road.wayId()) && carriesGap(road))) {
            kept.add(road);
         }
      }
      return kept;
   }

   /** Whether a landmark that replaces bridge roads carries this road away from its ends. */
   private static boolean carriesGap(RoadFeature road) {
      for (Entry entry : entries()) {
         if (entry.definition().replacesBridgeRoads() && entry.carries(road) && entry.awayFromEnds(road)) {
            return true;
         }
      }
      return false;
   }

   /** A catalogue entry: the definition and, once needed, its mesh and the ground it covers. */
   static final class Entry {
      private final LandmarkDefinition definition;
      private final double cos;
      private final double sin;
      private final double metersPerLonDegree;
      private volatile LandmarkMesh mesh;
      private volatile boolean meshFailed;
      private volatile Footprint footprint;

      private Entry(LandmarkDefinition definition) {
         this.definition = definition;
         this.cos = Math.cos(Math.toRadians(definition.rotationDegrees()));
         this.sin = Math.sin(Math.toRadians(definition.rotationDegrees()));
         this.metersPerLonDegree = METERS_PER_DEGREE * Math.cos(Math.toRadians(definition.latitude()));
      }

      LandmarkDefinition definition() {
         return this.definition;
      }

      /** The mesh, read on first use; null when it cannot be read. */
      LandmarkMesh mesh() {
         LandmarkMesh loaded = this.mesh;
         if (loaded == null && !this.meshFailed) {
            synchronized (this) {
               loaded = this.mesh;
               if (loaded == null && !this.meshFailed) {
                  try (InputStream stream = LandmarkCatalog.class.getResourceAsStream(ROOT + this.definition.mesh())) {
                     if (stream == null) {
                        throw new IOException("missing resource " + ROOT + this.definition.mesh());
                     }
                     loaded = LandmarkMesh.read(stream);
                     this.mesh = loaded;
                  } catch (IOException | RuntimeException error) {
                     this.meshFailed = true;
                     Tellus.LOGGER.warn("Failed to read landmark mesh {}", this.definition.mesh(), error);
                  }
               }
            }
         }
         return loaded;
      }

      /** The ground the mesh covers seen from above, worked out on first use. */
      private Footprint footprint(LandmarkMesh mesh) {
         Footprint built = this.footprint;
         if (built == null) {
            synchronized (this) {
               built = this.footprint;
               if (built == null) {
                  built = Footprint.of(mesh);
                  this.footprint = built;
               }
            }
         }
         return built;
      }

      /** Mesh-frame metres (x east, z south) of a map position, or null when it is too far to matter. */
      private double[] meshPoint(double lon, double lat) {
         double east = (lon - this.definition.longitude()) * this.metersPerLonDegree;
         double north = (lat - this.definition.latitude()) * METERS_PER_DEGREE;
         if (Math.abs(east) > MAX_REACH_METERS || Math.abs(north) > MAX_REACH_METERS) {
            return null;
         }
         // Into the mesh frame: undo the counter-clockwise turn.
         double modelEast = east * this.cos + north * this.sin;
         double modelNorth = -east * this.sin + north * this.cos;
         return new double[]{modelEast, -modelNorth};
      }

      private boolean suppresses(OsmBuildingFeature feature) {
         String buildingId = feature.buildingId();
         if (buildingId != null && this.definition.suppressBuildingIds().contains(buildingId)) {
            return true;
         }
         double[] point = this.meshPoint(feature.centroidLon(), feature.centroidLat());
         if (point == null) {
            return false;
         }
         LandmarkMesh mesh = this.mesh();
         if (mesh == null) {
            return false;
         }
         double margin = this.definition.suppressMarginMeters();
         if (point[0] < mesh.min(0) - margin || point[0] > mesh.max(0) + margin || point[1] < mesh.min(2) - margin || point[1] > mesh.max(2) + margin) {
            return false;
         }
         return !this.definition.footprintSuppression() || this.footprint(mesh).covers(point[0], point[1], margin);
      }

      /** Whether at least half of the road's length runs over the landmark's footprint. */
      private boolean carries(RoadFeature road) {
         LandmarkMesh mesh = null;
         double inside = 0.0;
         double total = 0.0;
         double[] previous = null;
         boolean previousInside = false;
         for (int i = 0; i < road.pointCount(); i++) {
            double[] point = this.meshPoint(road.lonAt(i), road.latAt(i));
            if (point == null) {
               return false;
            }
            if (mesh == null) {
               mesh = this.mesh();
               if (mesh == null) {
                  return false;
               }
            }
            boolean pointInside = this.footprint(mesh).covers(point[0], point[1], BRIDGE_ROAD_MARGIN_METERS);
            if (previous != null) {
               double length = Math.hypot(point[0] - previous[0], point[1] - previous[1]);
               // Long segments are sampled; a segment is as much "on" the landmark as its samples are.
               int samples = Math.max(1, (int)Math.ceil(length / 4.0));
               int on = 0;
               for (int s = 0; s < samples; s++) {
                  double t = (s + 0.5) / samples;
                  if (this.footprint(mesh).covers(previous[0] + (point[0] - previous[0]) * t, previous[1] + (point[1] - previous[1]) * t,
                     BRIDGE_ROAD_MARGIN_METERS)) {
                     on++;
                  }
               }
               inside += length * on / samples;
               total += length;
            }
            previous = point;
            previousInside = pointInside;
         }
         return total > 0.0 ? inside >= total * BRIDGE_ROAD_SHARE : previousInside;
      }

      /** Whether all of the road lies more than {@link #GAP_INSET_METERS} from the ends of the mesh's longer side. */
      private boolean awayFromEnds(RoadFeature road) {
         LandmarkMesh mesh = this.mesh();
         if (mesh == null) {
            return false;
         }
         int axis = mesh.max(0) - mesh.min(0) >= mesh.max(2) - mesh.min(2) ? 0 : 2;
         for (int i = 0; i < road.pointCount(); i++) {
            double[] point = this.meshPoint(road.lonAt(i), road.latAt(i));
            if (point == null) {
               return false;
            }
            double along = axis == 0 ? point[0] : point[1];
            if (along < mesh.min(axis) + GAP_INSET_METERS || along > mesh.max(axis) - GAP_INSET_METERS) {
               return false;
            }
         }
         return true;
      }
   }

   /** The ground a mesh covers seen from above, on a grid of {@link #CELL} metres in the mesh frame. */
   static final class Footprint {
      static final double CELL = 2.0;
      private final double minX;
      private final double minZ;
      private final int width;
      private final int depth;
      private final long[] bits;

      private Footprint(double minX, double minZ, int width, int depth, long[] bits) {
         this.minX = minX;
         this.minZ = minZ;
         this.width = width;
         this.depth = depth;
         this.bits = bits;
      }

      static Footprint of(LandmarkMesh mesh) {
         double minX = mesh.min(0) - CELL;
         double minZ = mesh.min(2) - CELL;
         int width = (int)Math.ceil((mesh.max(0) - minX) / CELL) + 2;
         int depth = (int)Math.ceil((mesh.max(2) - minZ) / CELL) + 2;
         long[] bits = new long[(int)(((long)width * depth + 63) >>> 6)];
         for (int t = 0, triangles = mesh.triangleCount(); t < triangles; t++) {
            double ax = (mesh.coordinate(t, 0, 0) - minX) / CELL, az = (mesh.coordinate(t, 0, 2) - minZ) / CELL;
            double bx = (mesh.coordinate(t, 1, 0) - minX) / CELL, bz = (mesh.coordinate(t, 1, 2) - minZ) / CELL;
            double cx = (mesh.coordinate(t, 2, 0) - minX) / CELL, cz = (mesh.coordinate(t, 2, 2) - minZ) / CELL;
            int x0 = Math.max(0, (int)Math.floor(Math.min(ax, Math.min(bx, cx))));
            int x1 = Math.min(width - 1, (int)Math.floor(Math.max(ax, Math.max(bx, cx))));
            int z0 = Math.max(0, (int)Math.floor(Math.min(az, Math.min(bz, cz))));
            int z1 = Math.min(depth - 1, (int)Math.floor(Math.max(az, Math.max(bz, cz))));
            for (int z = z0; z <= z1; z++) {
               for (int x = x0; x <= x1; x++) {
                  if (overlapsCell(ax, az, bx, bz, cx, cz, x, z)) {
                     int bit = z * width + x;
                     bits[bit >>> 6] |= 1L << (bit & 63);
                  }
               }
            }
         }
         return new Footprint(minX, minZ, width, depth, bits);
      }

      /** Separating-axis test of a 2D triangle (possibly degenerate, seen edge-on) against the unit cell (x, z). */
      private static boolean overlapsCell(double ax, double az, double bx, double bz, double cx, double cz, int x, int z) {
         double[] xs = {ax, bx, cx};
         double[] zs = {az, bz, cz};
         for (int e = 0; e < 3; e++) {
            double ex = xs[(e + 1) % 3] - xs[e];
            double ez = zs[(e + 1) % 3] - zs[e];
            // Edge normal; for an edge-on triangle the edges still separate thin slivers from far cells.
            double nx = -ez, nz = ex;
            if (nx == 0.0 && nz == 0.0) {
               continue;
            }
            double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
            for (int v = 0; v < 3; v++) {
               double p = nx * xs[v] + nz * zs[v];
               low = Math.min(low, p);
               high = Math.max(high, p);
            }
            double cellLow = Double.MAX_VALUE, cellHigh = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
               double p = nx * (x + (corner & 1)) + nz * (z + (corner >> 1));
               cellLow = Math.min(cellLow, p);
               cellHigh = Math.max(cellHigh, p);
            }
            if (high < cellLow || low > cellHigh) {
               return false;
            }
         }
         return true;
      }

      /** Whether the point (mesh-frame metres) lies within {@code margin} metres (a square) of a covered cell. */
      boolean covers(double x, double z, double margin) {
         int reach = (int)Math.ceil(margin / CELL);
         int cellX = (int)Math.floor((x - this.minX) / CELL);
         int cellZ = (int)Math.floor((z - this.minZ) / CELL);
         for (int dz = -reach; dz <= reach; dz++) {
            int row = cellZ + dz;
            if (row < 0 || row >= this.depth) {
               continue;
            }
            for (int dx = -reach; dx <= reach; dx++) {
               int column = cellX + dx;
               if (column >= 0 && column < this.width) {
                  int bit = row * this.width + column;
                  if ((this.bits[bit >>> 6] & 1L << (bit & 63)) != 0L) {
                     return true;
                  }
               }
            }
         }
         return false;
      }
   }
}
