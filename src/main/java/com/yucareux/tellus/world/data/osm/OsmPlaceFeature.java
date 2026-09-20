package com.yucareux.tellus.world.data.osm;

public record OsmPlaceFeature(
   String id,
   String name,
   String layer,
   String subtype,
   String placeClass,
   double latitude,
   double longitude,
   double elevationMeters,
   String wikidata
) {
   public OsmPlaceFeature {
      if (id == null || id.isBlank()) {
         throw new IllegalArgumentException("Place feature id is required");
      }
      if (name == null || name.isBlank()) {
         throw new IllegalArgumentException("Place feature name is required");
      }
      if (layer == null || layer.isBlank()) {
         throw new IllegalArgumentException("Place feature layer is required");
      }
   }
}
