package com.yucareux.tellus.api;

import com.yucareux.tellus.world.data.osm.OsmWaterKind;

public record WaterFlow(
   OsmWaterKind kind,
   double headingX,
   double headingZ,
   double distanceBlocks,
   int widthBlocks,
   boolean terrainConfirmed
) {
}
