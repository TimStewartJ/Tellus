package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Arrays;
import java.util.Objects;

public final class OsmWaterFeature {
   /** Slack over the half width that absorbs projection round-off in the bounding-box rejects. */
   private static final double LINE_REJECT_SLACK_BLOCKS = 0.01;
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

   public boolean containsBlock(int blockX, int blockZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return false;
      } else if (this.crossesWorldSeam(projection)) {
         return false;
      } else if (this.pointGeometry) {
         return false;
      } else if (this.lineGeometry) {
         return this.touchesBlockLine(blockX, blockZ, projection);
      } else {
         double lon = projection.blockXToLon(blockX);
         double lat = projection.blockZToLat(blockZ);
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

   /**
    * Half the channel width a centreline of this feature's kind occupies at this projection's scale, in
    * blocks; a block whose centre is within this distance of the line is water. Unknown kinds keep the
    * historical half-block line.
    */
   public double lineHalfWidthBlocks(WorldProjection projection) {
      if (!this.lineGeometry) {
         return 0.0;
      }
      double midLatitude = (this.minLat + this.maxLat) * 0.5;
      double metersPerBlock = projection.groundMetersPerBlockX(projection.latToBlockZ(midLatitude));
      return this.kind.centerlineWidthBlocks(metersPerBlock) / 2.0;
   }

   private boolean touchesBlockLine(int blockX, int blockZ, WorldProjection projection) {
      double queryX = blockX;
      double queryZ = blockZ;
      double halfWidth = this.lineHalfWidthBlocks(projection);
      double maxDistanceSq = halfWidth * halfWidth + 1.0E-6;
      double rejectBlocks = halfWidth + LINE_REJECT_SLACK_BLOCKS;
      double blocksPerDegree = projection.equatorialBlocksPerDegree();
      // Reject in degrees before paying for any projection: one block is exactly
      // 1/blocksPerDegree degrees of longitude and at most that much latitude.
      double rejectDegrees = rejectBlocks / blocksPerDegree;
      double lon = projection.blockXToLon(blockX);
      if (lon < this.minLon - rejectDegrees || lon > this.maxLon + rejectDegrees) {
         return false;
      }
      double lat = projection.blockZToLat(blockZ);
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
            double startX = projection.lonToBlockX(lonA);
            double startZ = projection.latToBlockZ(latA);
            double endX = projection.lonToBlockX(lonB);
            double endZ = projection.latToBlockZ(latB);
            if (distanceToSegmentSq(queryX, queryZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }

      return false;
   }

   public boolean crossesWorldSeam(WorldProjection projection) {
      for (int part = 0; part < this.partCount(); part++) {
         int points = this.pointCount(part);
         if (points < 2) {
            continue;
         }
         double previousX = projection.lonToBlockX(this.lonAt(part, this.lineGeometry ? 0 : points - 1));
         int firstPoint = this.lineGeometry ? 1 : 0;
         for (int point = firstPoint; point < points; point++) {
            double currentX = projection.lonToBlockX(this.lonAt(part, point));
            if (projection.crossesWorldSeam(previousX, currentX)) {
               return true;
            }
            previousX = currentX;
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
    * The scanner is single-threaded and must be re-created when {@code projection} changes. Seam-crossing
    * features are always dry, matching {@link #containsBlock}'s seam skip.
    */
   public RowScanner rowScanner(WorldProjection projection) {
      return new RowScanner(this, projection);
   }

   /**
    * Row-sweep evaluator for {@link #containsBlock}. Line geometry is projected once and only the
    * segments whose Z range can reach the current row are tested per block; polygon geometry keeps
    * the sorted ray-cast crossings of the current row so each block is a binary search. Rasterizing
    * a tile therefore costs O(rows x vertices) instead of O(blocks x vertices) while returning exactly
    * what {@code containsBlock} returns for every block, including seam skips and centered origins.
    */
   public static final class RowScanner {
      private final OsmWaterFeature feature;
      private final WorldProjection projection;
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
      private final double lineMaxDistanceSq;
      private final double lineRejectBlocks;

      private RowScanner(OsmWaterFeature feature, WorldProjection projection) {
         this.feature = feature;
         this.projection = projection;
         this.blocksPerDegree = projection.equatorialBlocksPerDegree();
         this.alwaysDry = !(projection.worldScale() > 0.0) || feature.pointGeometry || feature.crossesWorldSeam(projection);
         double halfWidth = feature.lineGeometry ? feature.lineHalfWidthBlocks(projection) : 0.0;
         this.lineMaxDistanceSq = halfWidth * halfWidth + 1.0E-6;
         this.lineRejectBlocks = halfWidth + LINE_REJECT_SLACK_BLOCKS;
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
                  double startX = projection.lonToBlockX(lonPart[point - 1]);
                  double startZ = projection.latToBlockZ(latPart[point - 1]);
                  double endX = projection.lonToBlockX(lonPart[point]);
                  double endZ = projection.latToBlockZ(latPart[point]);
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

      /** Equivalent to {@code feature.containsBlock(blockX, rowBlockZ, projection)}. */
      public boolean contains(int blockX) {
         if (this.rowDry) {
            return false;
         }
         return this.feature.lineGeometry ? this.lineContains(blockX) : this.polygonContains(blockX);
      }

      private void beginLineRow(int blockZ) {
         double queryZ = blockZ;
         this.rowZ = queryZ;
         if (queryZ < this.minBlockZ - this.lineRejectBlocks || queryZ > this.maxBlockZ + this.lineRejectBlocks) {
            this.rowDry = true;
            return;
         }
         int count = 0;
         for (int segment = 0; segment < this.segmentStartZ.length; segment++) {
            double startZ = this.segmentStartZ[segment];
            double endZ = this.segmentEndZ[segment];
            if (queryZ >= Math.min(startZ, endZ) - this.lineRejectBlocks && queryZ <= Math.max(startZ, endZ) + this.lineRejectBlocks) {
               this.activeSegments[count++] = segment;
            }
         }
         this.activeCount = count;
         this.rowDry = count == 0;
      }

      private boolean lineContains(int blockX) {
         double queryX = blockX;
         if (queryX < this.minBlockX - this.lineRejectBlocks || queryX > this.maxBlockX + this.lineRejectBlocks) {
            return false;
         }
         for (int i = 0; i < this.activeCount; i++) {
            int segment = this.activeSegments[i];
            double startX = this.segmentStartX[segment];
            double endX = this.segmentEndX[segment];
            if (queryX < Math.min(startX, endX) - this.lineRejectBlocks || queryX > Math.max(startX, endX) + this.lineRejectBlocks) {
               continue;
            }
            if (distanceToSegmentSq(
                  queryX, this.rowZ, startX, this.segmentStartZ[segment], endX, this.segmentEndZ[segment]
               )
               <= this.lineMaxDistanceSq) {
               return true;
            }
         }
         return false;
      }

      private void beginPolygonRow(int blockZ) {
         double lat = this.projection.blockZToLat(blockZ);
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
         double lon = this.projection.blockXToLon(blockX);
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
