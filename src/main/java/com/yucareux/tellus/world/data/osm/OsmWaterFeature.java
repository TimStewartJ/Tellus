package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Arrays;
import java.util.Objects;

public final class OsmWaterFeature {
   private static final double LINE_HALF_WIDTH_BLOCKS = 0.5;
   private static final double LINE_MAX_DISTANCE_SQ = LINE_HALF_WIDTH_BLOCKS * LINE_HALF_WIDTH_BLOCKS + 1.0E-6;
   /**
    * Anything farther than this from a segment's bounding box cannot be within
    * {@link #LINE_MAX_DISTANCE_SQ}; the slack over sqrt(LINE_MAX_DISTANCE_SQ) absorbs projection
    * round-off.
    */
   private static final double LINE_REJECT_BLOCKS = 0.51;
   /**
    * Latitude rejects rely on Mercator stretching latitude (one block never spans more than
    * 1/blocksPerDegree degrees of latitude), which stops holding inside the polar clamp.
    */
   private static final double LAT_REJECT_LIMIT_DEGREES = 85.0;
   private final long featureId;
   private final boolean lineGeometry;
   private final boolean pointGeometry;
   private final boolean oceanHint;
   private final OsmWaterKind kind;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;

   public OsmWaterFeature(long featureId, boolean lineGeometry, boolean oceanHint, double[][] longitudes, double[][] latitudes) {
      this(featureId, lineGeometry, oceanHint, OsmWaterKind.UNKNOWN, longitudes, latitudes);
   }

   public OsmWaterFeature(
      long featureId, boolean lineGeometry, boolean oceanHint, OsmWaterKind kind, double[][] longitudes, double[][] latitudes
   ) {
      this(featureId, lineGeometry, false, oceanHint, kind, longitudes, latitudes);
   }

   OsmWaterFeature(
      long featureId,
      boolean lineGeometry,
      boolean pointGeometry,
      boolean oceanHint,
      OsmWaterKind kind,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.featureId = featureId;
      this.lineGeometry = lineGeometry;
      this.pointGeometry = pointGeometry;
      if (lineGeometry && pointGeometry) {
         throw new IllegalArgumentException("Water feature cannot be both a line and a point");
      }
      this.kind = Objects.requireNonNullElse(kind, OsmWaterKind.UNKNOWN);
      this.oceanHint = oceanHint || this.kind.ocean();
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Water feature requires matching geometry parts");
      } else {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int minPoints = this.pointGeometry ? 1 : this.lineGeometry ? 2 : 4;
            if (lonPart.length != latPart.length || lonPart.length < minPoints) {
               throw new IllegalArgumentException("Water feature part has invalid point count");
            }

            for (int point = 0; point < lonPart.length; point++) {
               double lon = lonPart[point];
               double lat = latPart[point];
               lowLon = Math.min(lowLon, lon);
               highLon = Math.max(highLon, lon);
               lowLat = Math.min(lowLat, lat);
               highLat = Math.max(highLat, lat);
            }
         }

         this.minLon = lowLon;
         this.maxLon = highLon;
         this.minLat = lowLat;
         this.maxLat = highLat;
      }
   }

   public static OsmWaterFeature waterfallMarker(long featureId, double longitude, double latitude) {
      return new OsmWaterFeature(
         featureId,
         false,
         true,
         false,
         OsmWaterKind.WATERFALL,
         new double[][]{{longitude}},
         new double[][]{{latitude}}
      );
   }

   public long featureId() {
      return this.featureId;
   }

   public boolean lineGeometry() {
      return this.lineGeometry;
   }

   public boolean pointGeometry() {
      return this.pointGeometry;
   }

   public boolean waterfallMarker() {
      return this.pointGeometry && this.kind == OsmWaterKind.WATERFALL;
   }

   public boolean oceanHint() {
      return this.oceanHint;
   }

   public OsmWaterKind kind() {
      return this.kind;
   }

   public boolean flowingWater() {
      return !this.pointGeometry && (this.lineGeometry || this.kind.flowing());
   }

   public int partCount() {
      return this.longitudes.length;
   }

   public int pointCount(int partIndex) {
      return this.longitudes[partIndex].length;
   }

   public double lonAt(int partIndex, int pointIndex) {
      return this.longitudes[partIndex][pointIndex];
   }

   public double latAt(int partIndex, int pointIndex) {
      return this.latitudes[partIndex][pointIndex];
   }

   public double minLon() {
      return this.minLon;
   }

   public double maxLon() {
      return this.maxLon;
   }

   public double minLat() {
      return this.minLat;
   }

   public double maxLat() {
      return this.maxLat;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsBlock(int blockX, int blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return false;
      } else if (this.pointGeometry) {
         return false;
      } else if (this.lineGeometry) {
         return this.touchesBlockLine(blockX, blockZ, worldScale);
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = blockX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(blockZ, worldScale);
         return this.containsLonLat(lon, lat);
      }
   }

   public boolean containsLonLat(double lon, double lat) {
      if (this.lineGeometry || this.pointGeometry || lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
         return false;
      } else {
         boolean inside = false;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            int points = lonPart.length;

            for (int i = 0, j = points - 1; i < points; j = i++) {
               double lonA = lonPart[i];
               double latA = latPart[i];
               double lonB = lonPart[j];
               double latB = latPart[j];
               if ((latA > lat) != (latB > lat)) {
                  double crossLon = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
                  if (lon <= crossLon) {
                     inside = !inside;
                  }
               }
            }
         }

         return inside;
      }
   }

   private boolean touchesBlockLine(int blockX, int blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double queryX = blockX;
      double queryZ = blockZ;
      // Reject in degrees before paying for any projection: one block is exactly
      // 1/blocksPerDegree degrees of longitude and at most that much latitude.
      double rejectDegrees = LINE_REJECT_BLOCKS / blocksPerDegree;
      double lon = blockX / blocksPerDegree;
      if (lon < this.minLon - rejectDegrees || lon > this.maxLon + rejectDegrees) {
         return false;
      }
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      boolean latRejects = this.latRejectsAllowed(lat);
      if (latRejects && (lat < this.minLat - rejectDegrees || lat > this.maxLat + rejectDegrees)) {
         return false;
      }

      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];

         for (int point = 1; point < lonPart.length; point++) {
            double lonA = lonPart[point - 1];
            double lonB = lonPart[point];
            if (lon < Math.min(lonA, lonB) - rejectDegrees || lon > Math.max(lonA, lonB) + rejectDegrees) {
               continue;
            }
            double latA = latPart[point - 1];
            double latB = latPart[point];
            if (latRejects && (lat < Math.min(latA, latB) - rejectDegrees || lat > Math.max(latA, latB) + rejectDegrees)) {
               continue;
            }
            double startX = lonA * blocksPerDegree;
            double startZ = EarthProjection.latToBlockZ(latA, worldScale);
            double endX = lonB * blocksPerDegree;
            double endZ = EarthProjection.latToBlockZ(latB, worldScale);
            if (distanceToSegmentSq(queryX, queryZ, startX, startZ, endX, endZ) <= LINE_MAX_DISTANCE_SQ) {
               return true;
            }
         }
      }

      return false;
   }

   private boolean latRejectsAllowed(double queryLat) {
      return Math.abs(queryLat) < LAT_REJECT_LIMIT_DEGREES
         && this.maxLat < LAT_REJECT_LIMIT_DEGREES
         && this.minLat > -LAT_REJECT_LIMIT_DEGREES;
   }

   /**
    * Creates a scanner that answers {@link #containsBlock} for many blocks sharing a Z coordinate.
    * The scanner is single-threaded and must be re-created when {@code worldScale} changes.
    */
   public RowScanner rowScanner(double worldScale) {
      return new RowScanner(this, worldScale);
   }

   /**
    * Row-sweep evaluator for {@link #containsBlock}. Line geometry is projected once and only the
    * segments whose Z range can reach the current row are tested per block; polygon geometry keeps
    * the sorted ray-cast crossings of the current row so each block is a binary search. Rasterizing
    * a tile therefore costs O(rows x vertices) instead of O(blocks x vertices) while returning exactly
    * what {@code containsBlock} returns for every block.
    */
   public static final class RowScanner {
      private final OsmWaterFeature feature;
      private final double worldScale;
      private final double blocksPerDegree;
      private final boolean alwaysDry;
      private final double[] segmentStartX;
      private final double[] segmentStartZ;
      private final double[] segmentEndX;
      private final double[] segmentEndZ;
      private final double minBlockX;
      private final double maxBlockX;
      private final double minBlockZ;
      private final double maxBlockZ;
      private final int[] activeSegments;
      private int activeCount;
      private final double[] crossings;
      private int crossingCount;
      private boolean rowDry = true;
      private double rowZ;

      private RowScanner(OsmWaterFeature feature, double worldScale) {
         this.feature = feature;
         this.worldScale = worldScale;
         this.blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         this.alwaysDry = worldScale <= 0.0 || feature.pointGeometry;
         if (!this.alwaysDry && feature.lineGeometry) {
            int segmentCount = 0;
            for (double[] part : feature.longitudes) {
               segmentCount += Math.max(0, part.length - 1);
            }
            this.segmentStartX = new double[segmentCount];
            this.segmentStartZ = new double[segmentCount];
            this.segmentEndX = new double[segmentCount];
            this.segmentEndZ = new double[segmentCount];
            this.activeSegments = new int[segmentCount];
            this.crossings = null;
            double lowX = Double.POSITIVE_INFINITY;
            double highX = Double.NEGATIVE_INFINITY;
            double lowZ = Double.POSITIVE_INFINITY;
            double highZ = Double.NEGATIVE_INFINITY;
            int cursor = 0;
            for (int part = 0; part < feature.longitudes.length; part++) {
               double[] lonPart = feature.longitudes[part];
               double[] latPart = feature.latitudes[part];
               for (int point = 1; point < lonPart.length; point++) {
                  // Same expressions as touchesBlockLine so the projected doubles match bit for bit.
                  double startX = lonPart[point - 1] * this.blocksPerDegree;
                  double startZ = EarthProjection.latToBlockZ(latPart[point - 1], worldScale);
                  double endX = lonPart[point] * this.blocksPerDegree;
                  double endZ = EarthProjection.latToBlockZ(latPart[point], worldScale);
                  this.segmentStartX[cursor] = startX;
                  this.segmentStartZ[cursor] = startZ;
                  this.segmentEndX[cursor] = endX;
                  this.segmentEndZ[cursor] = endZ;
                  cursor++;
                  lowX = Math.min(lowX, Math.min(startX, endX));
                  highX = Math.max(highX, Math.max(startX, endX));
                  lowZ = Math.min(lowZ, Math.min(startZ, endZ));
                  highZ = Math.max(highZ, Math.max(startZ, endZ));
               }
            }
            this.minBlockX = lowX;
            this.maxBlockX = highX;
            this.minBlockZ = lowZ;
            this.maxBlockZ = highZ;
         } else {
            this.segmentStartX = null;
            this.segmentStartZ = null;
            this.segmentEndX = null;
            this.segmentEndZ = null;
            this.activeSegments = null;
            int edgeCount = 0;
            for (double[] part : feature.longitudes) {
               edgeCount += part.length;
            }
            this.crossings = this.alwaysDry ? null : new double[edgeCount];
            this.minBlockX = 0.0;
            this.maxBlockX = 0.0;
            this.minBlockZ = 0.0;
            this.maxBlockZ = 0.0;
         }
      }

      /** Selects the row of blocks at {@code blockZ}; must precede {@link #contains}. */
      public void beginRow(int blockZ) {
         if (this.alwaysDry) {
            this.rowDry = true;
            return;
         }
         if (this.feature.lineGeometry) {
            this.beginLineRow(blockZ);
         } else {
            this.beginPolygonRow(blockZ);
         }
      }

      /** Equivalent to {@code feature.containsBlock(blockX, rowBlockZ, worldScale)}. */
      public boolean contains(int blockX) {
         if (this.rowDry) {
            return false;
         }
         return this.feature.lineGeometry ? this.lineContains(blockX) : this.polygonContains(blockX);
      }

      private void beginLineRow(int blockZ) {
         double queryZ = blockZ;
         this.rowZ = queryZ;
         if (queryZ < this.minBlockZ - LINE_REJECT_BLOCKS || queryZ > this.maxBlockZ + LINE_REJECT_BLOCKS) {
            this.rowDry = true;
            return;
         }
         int count = 0;
         for (int segment = 0; segment < this.segmentStartZ.length; segment++) {
            double startZ = this.segmentStartZ[segment];
            double endZ = this.segmentEndZ[segment];
            if (queryZ >= Math.min(startZ, endZ) - LINE_REJECT_BLOCKS && queryZ <= Math.max(startZ, endZ) + LINE_REJECT_BLOCKS) {
               this.activeSegments[count++] = segment;
            }
         }
         this.activeCount = count;
         this.rowDry = count == 0;
      }

      private boolean lineContains(int blockX) {
         double queryX = blockX;
         if (queryX < this.minBlockX - LINE_REJECT_BLOCKS || queryX > this.maxBlockX + LINE_REJECT_BLOCKS) {
            return false;
         }
         for (int i = 0; i < this.activeCount; i++) {
            int segment = this.activeSegments[i];
            double startX = this.segmentStartX[segment];
            double endX = this.segmentEndX[segment];
            if (queryX < Math.min(startX, endX) - LINE_REJECT_BLOCKS || queryX > Math.max(startX, endX) + LINE_REJECT_BLOCKS) {
               continue;
            }
            if (distanceToSegmentSq(
                  queryX, this.rowZ, startX, this.segmentStartZ[segment], endX, this.segmentEndZ[segment]
               )
               <= LINE_MAX_DISTANCE_SQ) {
               return true;
            }
         }
         return false;
      }

      private void beginPolygonRow(int blockZ) {
         double lat = EarthProjection.blockZToLat(blockZ, this.worldScale);
         if (lat < this.feature.minLat || lat > this.feature.maxLat) {
            this.rowDry = true;
            return;
         }
         int count = 0;
         for (int part = 0; part < this.feature.longitudes.length; part++) {
            double[] lonPart = this.feature.longitudes[part];
            double[] latPart = this.feature.latitudes[part];
            int points = lonPart.length;
            for (int i = 0, j = points - 1; i < points; j = i++) {
               double latA = latPart[i];
               double latB = latPart[j];
               if ((latA > lat) != (latB > lat)) {
                  double lonA = lonPart[i];
                  double lonB = lonPart[j];
                  // Identical expression to containsLonLat so the crossing doubles match.
                  this.crossings[count++] = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
               }
            }
         }
         Arrays.sort(this.crossings, 0, count);
         this.crossingCount = count;
         this.rowDry = count == 0;
      }

      private boolean polygonContains(int blockX) {
         double lon = blockX / this.blocksPerDegree;
         if (lon < this.feature.minLon || lon > this.feature.maxLon) {
            return false;
         }
         // containsLonLat toggles once per crossing with lon <= crossLon, so the answer is the
         // parity of the crossings at or beyond lon.
         int low = 0;
         int high = this.crossingCount;
         while (low < high) {
            int mid = (low + high) >>> 1;
            if (this.crossings[mid] < lon) {
               low = mid + 1;
            } else {
               high = mid;
            }
         }
         return ((this.crossingCount - low) & 1) != 0;
      }
   }

   private static double distanceToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
      double dx = bx - ax;
      double dz = bz - az;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-9) {
         double distX = px - ax;
         double distZ = pz - az;
         return distX * distX + distZ * distZ;
      } else {
         double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
         t = Math.max(0.0, Math.min(1.0, t));
         double projX = ax + t * dx;
         double projZ = az + t * dz;
         double distX = px - projX;
         double distZ = pz - projZ;
         return distX * distX + distZ * distZ;
      }
   }

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];

      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }

      return copy;
   }
}
