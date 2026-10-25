package com.yucareux.tellus.world.data.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.Mth;

public final class OsmWaterTile {
   private static final int GRID_X = 8;
   private static final int GRID_Y = 8;
   private static final int BUCKET_COUNT = GRID_X * GRID_Y;
   private static final OsmWaterTile EMPTY = new OsmWaterTile(List.of());
   private final List<OsmWaterFeature> features;
   private final double tileSouth;
   private final double tileWest;
   private final double tileNorth;
   private final double tileEast;
   private final int[][] bucketFeatureIndices;

   public OsmWaterTile(List<OsmWaterFeature> features) {
      this(features, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
   }

   public OsmWaterTile(List<OsmWaterFeature> features, double tileSouth, double tileWest, double tileNorth, double tileEast) {
      this.features = List.copyOf(Objects.requireNonNull(features, "features"));
      this.tileSouth = tileSouth;
      this.tileWest = tileWest;
      this.tileNorth = tileNorth;
      this.tileEast = tileEast;
      for (OsmWaterFeature feature : this.features) feature.attachTileBounds(tileSouth, tileWest, tileNorth, tileEast);
      this.bucketFeatureIndices = this.buildSpatialIndex();
   }

   public static OsmWaterTile empty() {
      return Objects.requireNonNull(EMPTY, "emptyOsmWaterTile");
   }

   public List<OsmWaterFeature> features() {
      return this.features;
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

   public boolean isEmpty() {
      return this.features.isEmpty();
   }

   public List<OsmWaterFeature> featuresInBounds(double south, double west, double north, double east) {
      if (this.features.isEmpty()) {
         return List.of();
      } else {
         double minSouth = Math.min(south, north);
         double maxNorth = Math.max(south, north);
         double minWest = Math.min(west, east);
         double maxEast = Math.max(west, east);
         if (this.bucketFeatureIndices != null && this.bucketFeatureIndices.length != 0) {
            if (!(maxEast < this.tileWest) && !(minWest > this.tileEast) && !(maxNorth < this.tileSouth) && !(minSouth > this.tileNorth)) {
               double clampedWest = Mth.clamp(minWest, this.tileWest, this.tileEast);
               double clampedEast = Mth.clamp(maxEast, this.tileWest, this.tileEast);
               double clampedSouth = Mth.clamp(minSouth, this.tileSouth, this.tileNorth);
               double clampedNorth = Mth.clamp(maxNorth, this.tileSouth, this.tileNorth);
               int minX = this.bucketXForLon(clampedWest);
               int maxX = this.bucketXForLon(clampedEast);
               int minY = this.bucketYForLat(clampedNorth);
               int maxY = this.bucketYForLat(clampedSouth);
               if (maxX >= minX && maxY >= minY) {
                  boolean[] seen = new boolean[this.features.size()];
                  List<OsmWaterFeature> matches = new ArrayList<>();

                  for (int y = minY; y <= maxY; y++) {
                     for (int x = minX; x <= maxX; x++) {
                        int[] indexes = this.bucketFeatureIndices[this.bucketIndex(x, y)];
                        if (indexes != null && indexes.length != 0) {
                           for (int featureIndex : indexes) {
                              if (featureIndex >= 0 && featureIndex < seen.length && !seen[featureIndex]) {
                                 seen[featureIndex] = true;
                                 OsmWaterFeature feature = this.features.get(featureIndex);
                                 if (feature.intersects(minSouth, minWest, maxNorth, maxEast)) {
                                    matches.add(feature);
                                 }
                              }
                           }
                        }
                     }
                  }

                  return matches.isEmpty() ? List.of() : matches;
               } else {
                  return List.of();
               }
            } else {
               return List.of();
            }
         } else {
            List<OsmWaterFeature> matches = new ArrayList<>(this.features.size());

            for (OsmWaterFeature feature : this.features) {
               if (feature.intersects(minSouth, minWest, maxNorth, maxEast)) {
                  matches.add(feature);
               }
            }

            return matches.isEmpty() ? List.of() : matches;
         }
      }
   }

   private int[][] buildSpatialIndex() {
      if (!this.features.isEmpty()
         && Double.isFinite(this.tileSouth)
         && Double.isFinite(this.tileWest)
         && Double.isFinite(this.tileNorth)
         && Double.isFinite(this.tileEast)
         && !(this.tileNorth <= this.tileSouth)
         && !(this.tileEast <= this.tileWest)) {
         int[] counts = new int[BUCKET_COUNT];

         for (int featureIndex = 0; featureIndex < this.features.size(); featureIndex++) {
            OsmWaterFeature feature = this.features.get(featureIndex);
            int minX = this.bucketXForLon(feature.minLon());
            int maxX = this.bucketXForLon(feature.maxLon());
            int minY = this.bucketYForLat(feature.maxLat());
            int maxY = this.bucketYForLat(feature.minLat());

            for (int y = minY; y <= maxY; y++) {
               for (int x = minX; x <= maxX; x++) {
                  counts[this.bucketIndex(x, y)]++;
               }
            }
         }

         int[][] buckets = new int[BUCKET_COUNT][];

         for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            if (counts[bucket] > 0) {
               buckets[bucket] = new int[counts[bucket]];
            }
         }

         int[] offsets = new int[BUCKET_COUNT];

         for (int featureIndex = 0; featureIndex < this.features.size(); featureIndex++) {
            OsmWaterFeature feature = this.features.get(featureIndex);
            int minX = this.bucketXForLon(feature.minLon());
            int maxX = this.bucketXForLon(feature.maxLon());
            int minY = this.bucketYForLat(feature.maxLat());
            int maxY = this.bucketYForLat(feature.minLat());

            for (int y = minY; y <= maxY; y++) {
               for (int x = minX; x <= maxX; x++) {
                  int bucket = this.bucketIndex(x, y);
                  int[] values = buckets[bucket];
                  if (values != null) {
                     int offset = offsets[bucket];
                     if (offset < values.length) {
                        values[offset] = featureIndex;
                        offsets[bucket] = offset + 1;
                     }
                  }
               }
            }
         }

         return buckets;
      } else {
         return null;
      }
   }

   private int bucketIndex(int x, int y) {
      return y * GRID_X + x;
   }

   private int bucketXForLon(double lon) {
      double clamped = Mth.clamp(lon, this.tileWest, this.tileEast);
      double range = this.tileEast - this.tileWest;
      if (range <= 1.0E-12) {
         return 0;
      } else {
         double normalized = (clamped - this.tileWest) / range;
         int bucket = (int)Math.floor(normalized * GRID_X);
         if (bucket >= GRID_X) {
            bucket = GRID_X - 1;
         }

         return Mth.clamp(bucket, 0, GRID_X - 1);
      }
   }

   private int bucketYForLat(double lat) {
      double clamped = Mth.clamp(lat, this.tileSouth, this.tileNorth);
      double range = this.tileNorth - this.tileSouth;
      if (range <= 1.0E-12) {
         return 0;
      } else {
         double normalized = (this.tileNorth - clamped) / range;
         int bucket = (int)Math.floor(normalized * GRID_Y);
         if (bucket >= GRID_Y) {
            bucket = GRID_Y - 1;
         }

         return Mth.clamp(bucket, 0, GRID_Y - 1);
      }
   }
}
