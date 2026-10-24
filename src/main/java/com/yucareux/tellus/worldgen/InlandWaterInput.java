package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.List;

/** The map data needed by the inland solver, independent of network/cache I/O. */
interface InlandWaterInput {
   TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(int minX, int minZ, int maxX, int maxZ,
      double scale, int margin, OsmQueryMode mode);
   TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale);
   TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale);

   /**
    * A water body when its fragments lie in at most {@code maxTiles} map tiles, else an empty complete result.
    * Defaults to the unbounded assembly.
    */
   default TellusOsmWaterSource.WaterQueryResult connectedWaterBodyWithin(OsmWaterFeature seed, double scale, int maxTiles) {
      return connectedWaterBody(seed, scale);
   }

   /**
    * Water for an area read from map tiles at least {@code minTileWidthBlocks} wide: a coarser zoom, which keeps
    * only larger waterways and holds far less data per area. Defaults to the regular query.
    */
   default TellusOsmWaterSource.WaterQueryResult waterForAreaAtTileWidth(int minX, int minZ, int maxX, int maxZ,
      double scale, int minTileWidthBlocks) {
      return waterForAreaWithStatus(minX, minZ, maxX, maxZ, scale, 0, OsmQueryMode.BLOCKING);
   }

   /** Fetches together what upcoming area queries ({minX, minZ, maxX, maxZ} each) will read; results do not change. */
   default void preload(List<int[]> areas, double scale, int margin) {
   }

   static InlandWaterInput from(TellusOsmWaterSource source) {
      return new InlandWaterInput() {
         public TellusOsmWaterSource.WaterQueryResult waterForAreaWithStatus(int minX, int minZ, int maxX, int maxZ,
            double scale, int margin, OsmQueryMode mode) {
            return source.waterForAreaWithStatus(minX, minZ, maxX, maxZ, scale, margin, mode);
         }
         public TellusOsmWaterSource.WaterQueryResult waterForAreaAtTileWidth(int minX, int minZ, int maxX, int maxZ,
            double scale, int minTileWidthBlocks) {
            return source.waterForAreaWithStatus(minX, minZ, maxX, maxZ, scale, 0, OsmQueryMode.BLOCKING, 64, minTileWidthBlocks);
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBody(OsmWaterFeature seed, double scale) {
            return source.connectedWaterBody(seed, scale);
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterfall(OsmWaterFeature seed, double scale) {
            return source.connectedWaterfall(seed, scale);
         }
         public TellusOsmWaterSource.WaterQueryResult connectedWaterBodyWithin(OsmWaterFeature seed, double scale, int maxTiles) {
            return source.connectedWaterBodyWithin(seed, scale, maxTiles);
         }
         public void preload(List<int[]> areas, double scale, int margin) {
            source.preloadAreas(areas, scale, margin);
         }
      };
   }
}
