package com.yucareux.tellus.world.data.osm;

import java.util.List;

public record PlaceQueryResult(List<OsmPlaceFeature> places, boolean complete) {
   public PlaceQueryResult {
      places = places == null ? List.of() : List.copyOf(places);
   }
}
