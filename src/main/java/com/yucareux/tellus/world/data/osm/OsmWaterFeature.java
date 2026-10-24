package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Objects;

public final class OsmWaterFeature {
   private static final double LINE_HALF_WIDTH_BLOCKS = 0.5;
   private final long featureId;
   private final boolean lineGeometry;
   private final boolean pointGeometry;
   private final boolean oceanHint;
   private final OsmWaterKind kind;
   private final double widthMeters;
   private final double dropMeters;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;
   // Lazily projected line geometry; features are immutable, so a racing rebuild is harmless.
   private volatile LineIndex lineIndex;
   // Bounds of the vector tile this feature was read from, NaN when unknown. Tiles clip their features with
   // a buffer; inside its own tile a fragment holds the original geometry, so the pieces of adjacent tiles
   // join exactly, without the clipped overlap. Set once, before the tile is published.
   private double tileSouth = Double.NaN;
   private double tileWest = Double.NaN;
   private double tileNorth = Double.NaN;
   private double tileEast = Double.NaN;

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
      this(featureId, lineGeometry, pointGeometry, oceanHint, kind, longitudes, latitudes, Double.NaN, Double.NaN);
   }

   OsmWaterFeature(
      long featureId, boolean lineGeometry, boolean pointGeometry, boolean oceanHint, OsmWaterKind kind,
      double[][] longitudes, double[][] latitudes, double widthMeters, double dropMeters
   ) {
      this.featureId = featureId;
      this.lineGeometry = lineGeometry;
      this.pointGeometry = pointGeometry;
      if (lineGeometry && pointGeometry) {
         throw new IllegalArgumentException("Water feature cannot be both a line and a point");
      }
      this.kind = Objects.requireNonNullElse(kind, OsmWaterKind.UNKNOWN);
      this.widthMeters = positiveDimension(widthMeters);
      this.dropMeters = positiveDimension(dropMeters);
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

   public boolean waterfall() { return this.kind == OsmWaterKind.WATERFALL; }

   public double widthMeters() { return this.widthMeters; }

   public double dropMeters() { return this.dropMeters; }

   OsmWaterFeature withDimensions(double widthMeters, double dropMeters) {
      return new OsmWaterFeature(this.featureId, this.lineGeometry, this.pointGeometry, this.oceanHint, this.kind,
         this.longitudes, this.latitudes, widthMeters, dropMeters);
   }

   /** The same feature with the points of the parts {@code flip} marks in reverse order: waterways drawn the other way. */
   public OsmWaterFeature reversed(boolean[] flip) {
      double[][] lons = new double[this.longitudes.length][];
      double[][] lats = new double[lons.length][];

      for (int part = 0; part < lons.length; part++) {
         int count = this.longitudes[part].length;
         lons[part] = new double[count];
         lats[part] = new double[count];

         for (int point = 0; point < count; point++) {
            int from = flip[part] ? count - 1 - point : point;
            lons[part][point] = this.longitudes[part][from];
            lats[part][point] = this.latitudes[part][from];
         }
      }

      OsmWaterFeature reversed = new OsmWaterFeature(this.featureId, this.lineGeometry, this.pointGeometry, this.oceanHint, this.kind,
         lons, lats, this.widthMeters, this.dropMeters);
      reversed.attachTileBounds(this.tileSouth, this.tileWest, this.tileNorth, this.tileEast);
      return reversed;
   }

   private static double positiveDimension(double value) {
      return Double.isFinite(value) && value > 0.0 && value <= 100000.0 ? value : Double.NaN;
   }

   public boolean oceanHint() {
      return this.oceanHint;
   }

   public OsmWaterKind kind() {
      return this.kind;
   }

   public boolean flowingWater() {
      return !this.pointGeometry && !this.waterfall() && (this.lineGeometry || this.kind.flowing());
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

   void attachTileBounds(double south, double west, double north, double east) {
      if (Double.isFinite(south) && Double.isFinite(west) && Double.isFinite(north) && Double.isFinite(east)) {
         this.tileSouth = south;
         this.tileWest = west;
         this.tileNorth = north;
         this.tileEast = east;
      }
   }

   /** Whether the bounds of the vector tile this fragment came from are known. */
   public boolean hasTileBounds() {
      return !Double.isNaN(this.tileSouth);
   }

   public double tileSouth() {
      return this.tileSouth;
   }

   public double tileWest() {
      return this.tileWest;
   }

   public double tileNorth() {
      return this.tileNorth;
   }

   public double tileEast() {
      return this.tileEast;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsBlock(int blockX, int blockZ, double worldScale) {
      if (worldScale <= 0.0 || this.waterfall()) {
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
      if (this.lineGeometry || this.pointGeometry || this.waterfall() || lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
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
    * Sets {@code inside[i] = containsLonLat(longitudes[i], lat)} for every
    * {@code i} in {@code [from, to]}. Longitudes must not decrease. The edges
    * are crossed once for the whole row and each cell counts the sorted
    * crossing longitudes at or east of it, with the same arithmetic as the
    * per-point test, so both give identical results.
    */
   public void containsLonLatRow(double lat, double[] longitudes, int from, int to, boolean[] inside) {
      boolean area = !this.lineGeometry && !this.pointGeometry && !this.waterfall();
      if (!area || lat < this.minLat || lat > this.maxLat) {
         java.util.Arrays.fill(inside, from, to + 1, false);
         return;
      }
      int edges = 0;
      for (double[] part : this.longitudes) edges += part.length;
      double[] crossings = new double[edges];
      int count = 0;
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
               crossings[count++] = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
            }
         }
      }
      java.util.Arrays.sort(crossings, 0, count);
      int west = 0;
      for (int i = from; i <= to; i++) {
         double lon = longitudes[i];
         if (i > from && lon < longitudes[i - 1]) {
            throw new IllegalArgumentException("Row longitudes must not decrease");
         }
         while (west < count && crossings[west] < lon) {
            west++;
         }
         inside[i] = lon >= this.minLon && lon <= this.maxLon && ((count - west) & 1) != 0;
      }
   }

   private boolean touchesBlockLine(int blockX, int blockZ, double worldScale) {
      double queryX = blockX;
      double queryZ = blockZ;
      double maxDistanceSq = LINE_HALF_WIDTH_BLOCKS * LINE_HALF_WIDTH_BLOCKS + 1.0E-6;
      LineIndex index = this.lineIndex(worldScale);
      if (queryX < index.minX || queryX > index.maxX || queryZ < index.minZ || queryZ > index.maxZ) {
         return false;
      }
      int[] candidates = index.buckets.get(LineIndex.bucketKey(Math.floorDiv(blockX, LineIndex.BUCKET), Math.floorDiv(blockZ, LineIndex.BUCKET)));
      if (candidates == null) {
         return false;
      }
      double[] segments = index.segments;
      for (int candidate : candidates) {
         int offset = candidate * 4;
         if (distanceToSegmentSq(queryX, queryZ, segments[offset], segments[offset + 1], segments[offset + 2], segments[offset + 3]) <= maxDistanceSq) {
            return true;
         }
      }

      return false;
   }

   /**
    * Projected line segments bucketed on a 16-block grid, built once per world scale. Every segment
    * is registered in each bucket its line-width-expanded bounds overlap, so a query tests exactly the
    * segments that can be within reach and returns the same result as a scan of all segments.
    */
   private LineIndex lineIndex(double worldScale) {
      LineIndex index = this.lineIndex;
      if (index != null && index.worldScale == worldScale) {
         return index;
      }
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int segmentCount = 0;
      for (double[] part : this.longitudes) {
         segmentCount += Math.max(0, part.length - 1);
      }
      double[] segments = new double[segmentCount * 4];
      double reach = LINE_HALF_WIDTH_BLOCKS + 1.0;
      double lowX = Double.POSITIVE_INFINITY, lowZ = Double.POSITIVE_INFINITY;
      double highX = Double.NEGATIVE_INFINITY, highZ = Double.NEGATIVE_INFINITY;
      it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> buckets =
         new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
      int segment = 0;
      for (int part = 0; part < this.longitudes.length; part++) {
         double[] lonPart = this.longitudes[part];
         double[] latPart = this.latitudes[part];
         for (int point = 1; point < lonPart.length; point++) {
            double startX = lonPart[point - 1] * blocksPerDegree;
            double startZ = EarthProjection.latToBlockZ(latPart[point - 1], worldScale);
            double endX = lonPart[point] * blocksPerDegree;
            double endZ = EarthProjection.latToBlockZ(latPart[point], worldScale);
            int offset = segment * 4;
            segments[offset] = startX;
            segments[offset + 1] = startZ;
            segments[offset + 2] = endX;
            segments[offset + 3] = endZ;
            double minX = Math.min(startX, endX) - reach, maxX = Math.max(startX, endX) + reach;
            double minZ = Math.min(startZ, endZ) - reach, maxZ = Math.max(startZ, endZ) + reach;
            lowX = Math.min(lowX, minX);
            highX = Math.max(highX, maxX);
            lowZ = Math.min(lowZ, minZ);
            highZ = Math.max(highZ, maxZ);
            int bucketMinX = (int)Math.floor(minX / LineIndex.BUCKET), bucketMaxX = (int)Math.floor(maxX / LineIndex.BUCKET);
            int bucketMinZ = (int)Math.floor(minZ / LineIndex.BUCKET), bucketMaxZ = (int)Math.floor(maxZ / LineIndex.BUCKET);
            for (int bz = bucketMinZ; bz <= bucketMaxZ; bz++) {
               for (int bx = bucketMinX; bx <= bucketMaxX; bx++) {
                  buckets.computeIfAbsent(LineIndex.bucketKey(bx, bz), ignored -> new it.unimi.dsi.fastutil.ints.IntArrayList(2)).add(segment);
               }
            }
            segment++;
         }
      }
      it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> frozen = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(buckets.size());
      for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<it.unimi.dsi.fastutil.ints.IntArrayList> entry : buckets.long2ObjectEntrySet()) {
         frozen.put(entry.getLongKey(), entry.getValue().toIntArray());
      }
      index = new LineIndex(worldScale, segments, frozen, lowX, lowZ, highX, highZ);
      this.lineIndex = index;
      return index;
   }

   private record LineIndex(
      double worldScale,
      double[] segments,
      it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> buckets,
      double minX,
      double minZ,
      double maxX,
      double maxZ
   ) {
      static final int BUCKET = 16;

      static long bucketKey(int x, int z) {
         return (long)x << 32 ^ z & 0xFFFFFFFFL;
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
