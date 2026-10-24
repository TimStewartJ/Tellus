package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Objects;

public final class OsmBuildingFeature {
   private final OsmBuildingKind kind;
   private final long featureId;
   private final String buildingId;
   private final boolean hasParts;
   private final OsmBuildingMetadata metadata;
   private final double heightMeters;
   private final double minHeightMeters;
   private final double[][] longitudes;
   private final double[][] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;
   private final double centroidLon;
   private final double centroidLat;
   private final double areaSquareMeters;

   public OsmBuildingFeature(
      OsmBuildingKind kind,
      long featureId,
      String buildingId,
      boolean hasParts,
      OsmBuildingMetadata metadata,
      double heightMeters,
      double minHeightMeters,
      double[][] longitudes,
      double[][] latitudes
   ) {
      this.kind = Objects.requireNonNull(kind, "kind");
      this.featureId = featureId;
      this.buildingId = buildingId == null || buildingId.isBlank() ? null : buildingId;
      this.hasParts = hasParts;
      this.metadata = Objects.requireNonNull(metadata, "metadata");
      this.heightMeters = heightMeters;
      this.minHeightMeters = minHeightMeters;
      this.longitudes = copyParts(Objects.requireNonNull(longitudes, "longitudes"));
      this.latitudes = copyParts(Objects.requireNonNull(latitudes, "latitudes"));
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length == 0) {
         throw new IllegalArgumentException("Building feature requires matching geometry parts");
      } else {
         double lowLon = Double.POSITIVE_INFINITY;
         double highLon = Double.NEGATIVE_INFINITY;
         double lowLat = Double.POSITIVE_INFINITY;
         double highLat = Double.NEGATIVE_INFINITY;

         for (int part = 0; part < this.longitudes.length; part++) {
            double[] lonPart = this.longitudes[part];
            double[] latPart = this.latitudes[part];
            if (lonPart.length != latPart.length || lonPart.length < 4) {
               throw new IllegalArgumentException("Building feature part has invalid point count");
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
         double[] centroid = computeCentroid(this.longitudes, this.latitudes);
         this.centroidLon = centroid[0];
         this.centroidLat = centroid[1];
         this.areaSquareMeters = Math.max(1.0, approximateAreaSquareMeters(this.longitudes, this.latitudes, this.centroidLat));
      }
   }

   public OsmBuildingKind kind() {
      return this.kind;
   }

   public long featureId() {
      return this.featureId;
   }

   public String buildingId() {
      return this.buildingId;
   }

   public boolean hasParts() {
      return this.hasParts;
   }

   public OsmBuildingMetadata metadata() {
      return this.metadata;
   }

   public double heightMeters() {
      return this.heightMeters;
   }

   public double minHeightMeters() {
      return this.minHeightMeters;
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

   public double centroidLon() {
      return this.centroidLon;
   }

   public double centroidLat() {
      return this.centroidLat;
   }

   public double areaSquareMeters() {
      return this.areaSquareMeters;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   public boolean containsWorld(double worldX, double worldZ, double worldScale) {
      if (worldScale <= 0.0) {
         return false;
      } else {
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
         double lon = worldX / blocksPerDegree;
         double lat = EarthProjection.blockZToLat(worldZ, worldScale);
         return this.containsLonLat(lon, lat);
      }
   }

   public boolean containsLonLat(double lon, double lat) {
      if (lon < this.minLon || lon > this.maxLon || lat < this.minLat || lat > this.maxLat) {
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

   public double minBlockX(double blocksPerDegree) {
      return this.minLon * blocksPerDegree;
   }

   public double minBlockXForScale(double worldScale) {
      return this.minBlockX(EarthProjection.blocksPerDegree(worldScale));
   }

   public double maxBlockX(double blocksPerDegree) {
      return this.maxLon * blocksPerDegree;
   }

   public double maxBlockXForScale(double worldScale) {
      return this.maxBlockX(EarthProjection.blocksPerDegree(worldScale));
   }

   public double minBlockZ(double worldScale) {
      double z1 = EarthProjection.latToBlockZ(this.minLat, worldScale);
      double z2 = EarthProjection.latToBlockZ(this.maxLat, worldScale);
      return Math.min(z1, z2);
   }

   public double maxBlockZ(double worldScale) {
      double z1 = EarthProjection.latToBlockZ(this.minLat, worldScale);
      double z2 = EarthProjection.latToBlockZ(this.maxLat, worldScale);
      return Math.max(z1, z2);
   }

   public double[] centroidWorld(double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      return new double[]{this.centroidLon * blocksPerDegree, EarthProjection.latToBlockZ(this.centroidLat, worldScale)};
   }

   public boolean widthLongerThanDepth() {
      double metersPerLonDegree = 111319.49166666667 * Math.cos(Math.toRadians(this.centroidLat));
      double widthMeters = Math.abs(this.maxLon - this.minLon) * Math.max(1.0, metersPerLonDegree);
      double depthMeters = Math.abs(this.maxLat - this.minLat) * 111319.49166666667;
      return widthMeters >= depthMeters;
   }

   private static double[][] copyParts(double[][] input) {
      double[][] copy = new double[input.length][];

      for (int i = 0; i < input.length; i++) {
         copy[i] = input[i].clone();
      }

      return copy;
   }

   /**
    * Area-weighted centroid of the parts. Coordinates are taken relative to each part's first point: the shoelace
    * products of raw longitudes and latitudes (thousands) cancel down to a building's area (around 1e-8 square
    * degrees), which in doubles moved small buildings' centroids by tens of metres far from longitude 0.
    */
   private static double[] computeCentroid(double[][] longitudes, double[][] latitudes) {
      double centroidLon = 0.0;
      double centroidLat = 0.0;
      double totalWeight = 0.0;

      for (int part = 0; part < longitudes.length; part++) {
         double[] lonPart = longitudes[part];
         double[] latPart = latitudes[part];
         double originLon = lonPart[0];
         double originLat = latPart[0];
         double signedArea = polygonSignedArea(lonPart, latPart);
         double weight = Math.abs(signedArea);
         if (weight <= 1.0E-12) {
            for (int point = 0; point < lonPart.length - 1; point++) {
               centroidLon += lonPart[point];
               centroidLat += latPart[point];
               totalWeight++;
            }
         } else {
            double factor = 0.0;
            double cx = 0.0;
            double cy = 0.0;

            for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
               double lonJ = lonPart[j] - originLon;
               double latJ = latPart[j] - originLat;
               double lonI = lonPart[i] - originLon;
               double latI = latPart[i] - originLat;
               double cross = lonJ * latI - lonI * latJ;
               factor += cross;
               cx += (lonJ + lonI) * cross;
               cy += (latJ + latI) * cross;
            }

            if (Math.abs(factor) > 1.0E-12) {
               centroidLon += (originLon + cx / (3.0 * factor)) * weight;
               centroidLat += (originLat + cy / (3.0 * factor)) * weight;
               totalWeight += weight;
            }
         }
      }

      if (totalWeight <= 1.0E-12) {
         return new double[]{longitudes[0][0], latitudes[0][0]};
      }

      return new double[]{centroidLon / totalWeight, centroidLat / totalWeight};
   }

   private static double approximateAreaSquareMeters(double[][] longitudes, double[][] latitudes, double centroidLat) {
      double metersPerLon = 111319.49166666667 * Math.cos(Math.toRadians(centroidLat));
      double total = 0.0;

      for (int part = 0; part < longitudes.length; part++) {
         double[] lonPart = longitudes[part];
         double[] latPart = latitudes[part];
         double area = 0.0;

         for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
            double x1 = (lonPart[j] - lonPart[0]) * metersPerLon;
            double y1 = (latPart[j] - latPart[0]) * 111319.49166666667;
            double x2 = (lonPart[i] - lonPart[0]) * metersPerLon;
            double y2 = (latPart[i] - latPart[0]) * 111319.49166666667;
            area += x1 * y2 - x2 * y1;
         }

         total += Math.abs(area) * 0.5;
      }

      return total;
   }

   private static double polygonSignedArea(double[] lonPart, double[] latPart) {
      double area = 0.0;
      double originLon = lonPart[0];
      double originLat = latPart[0];

      for (int i = 0, j = lonPart.length - 1; i < lonPart.length; j = i++) {
         area += (lonPart[j] - originLon) * (latPart[i] - originLat) - (lonPart[i] - originLon) * (latPart[j] - originLat);
      }

      return area * 0.5;
   }
}
