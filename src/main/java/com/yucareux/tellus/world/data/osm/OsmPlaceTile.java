package com.yucareux.tellus.world.data.osm;

import java.util.ArrayList;
import java.util.List;

public final class OsmPlaceTile {
   private static final OsmPlaceTile EMPTY = new OsmPlaceTile(List.of());
   private final List<OsmPlaceFeature> features;

   public OsmPlaceTile(List<OsmPlaceFeature> features) {
      this.features = List.copyOf(features == null ? List.of() : features);
   }

   public static OsmPlaceTile empty() {
      return EMPTY;
   }

   public List<OsmPlaceFeature> features() {
      return this.features;
   }

   public boolean isEmpty() {
      return this.features.isEmpty();
   }

   public List<OsmPlaceFeature> featuresInBounds(double south, double west, double north, double east) {
      if (this.features.isEmpty()) {
         return List.of();
      }
      double minSouth = Math.min(south, north);
      double maxNorth = Math.max(south, north);
      double minWest = Math.min(west, east);
      double maxEast = Math.max(west, east);
      List<OsmPlaceFeature> matches = new ArrayList<>();
      for (OsmPlaceFeature feature : this.features) {
         double latitude = feature.latitude();
         double longitude = feature.longitude();
         if (latitude >= minSouth && latitude <= maxNorth && longitude >= minWest && longitude <= maxEast) {
            matches.add(feature);
         }
      }
      return matches.isEmpty() ? List.of() : matches;
   }
}
