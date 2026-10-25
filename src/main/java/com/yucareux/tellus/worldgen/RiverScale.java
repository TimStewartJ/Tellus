package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterKind;

/**
 * How large rivers are at a world scale, and which ones are drawn at all. Map widths are ground metres;
 * channels, depths and the distances the river network reads are converted to blocks here, so a river keeps
 * its proportions from 1:1 to 1:1000 instead of keeping fixed block sizes.
 *
 * <p>A waterway is drawn while its real width is at least a fraction of a block: rivers may be exaggerated
 * up to {@link #RIVER_EXAGGERATION} times (as maps do), minor streams and ditches only
 * {@link #STREAM_EXAGGERATION} times. So streams vanish beyond about 1:6, untagged rivers beyond about 1:50,
 * and at 1:1000 only rivers several hundred metres wide remain.</p>
 */
final class RiverScale {
   static final double RIVER_EXAGGERATION = 6.0;
   static final double STREAM_EXAGGERATION = 2.0;
   /** Narrowest drawn channel: a face-connected path of one block even on a diagonal. */
   static final double MIN_HALF_WIDTH = 0.75;
   /** Terrain resolution the network observes rivers at; finer models would cost far more tiles per area. */
   static final double NETWORK_TERRAIN_METERS = 30.0;
   /** Ground length of a network cell. */
   static final double NETWORK_CELL_METERS = 32.0;
   /** Upstream window of the profile carve, ground metres. Shorter windows step uphill in canyons. */
   static final double CARVE_METERS = 4096.0;
   /** Downstream window that raises a reach to a later higher fixed level (the sea, a main river). */
   static final double FILL_METERS = 1024.0;

   final double scale;
   final WorldProjection projection;

   RiverScale(WorldProjection projection) {
      double scale = projection.worldScale();
      this.scale = scale > 0.0 && Double.isFinite(scale) ? scale : 1.0;
      this.projection = this.scale == scale ? projection : projection.withWorldScale(this.scale);
   }

   /** Ground metres per block at a block row. */
   double metersPerBlock(double z) {
      double meters = this.projection.groundMetersPerBlockX(z);
      return meters > 0.0 && Double.isFinite(meters) ? meters : scale;
   }

   /** Ground metres to blocks at the equator, for distances that must not vary with latitude. */
   double blocks(double meters) {
      return meters / scale;
   }

   int networkCellSize() {
      return Math.max(1, (int)Math.round(NETWORK_CELL_METERS / scale));
   }

   /** Width of a waterway the map does not tag, by kind. */
   static double defaultWidthMeters(OsmWaterKind kind) {
      return switch (kind) {
         case RIVER -> 9.0;
         case CANAL -> 10.0;
         case STREAM -> 3.0;
         case DRAIN -> 2.5;
         case DITCH -> 1.5;
         default -> 3.0;
      };
   }

   static boolean minor(OsmWaterKind kind) {
      return kind == OsmWaterKind.STREAM || kind == OsmWaterKind.DITCH || kind == OsmWaterKind.DRAIN;
   }

   /** Whether a waterway of this kind and real width is drawn at this scale. */
   boolean visible(OsmWaterKind kind, double widthMeters, double z) {
      double blocks = widthMeters / metersPerBlock(z);
      return blocks * (minor(kind) ? STREAM_EXAGGERATION : RIVER_EXAGGERATION) >= 1.0;
   }

   /** Half-width of the drawn channel in blocks. */
   double halfWidth(double widthMeters, double z) {
      return Math.max(MIN_HALF_WIDTH, widthMeters / metersPerBlock(z) * 0.5);
   }

   /**
    * Depth at the deepest point of a channel, in blocks: a typical depth for the real width (about 4 m at 100 m
    * wide, 16 m at a kilometre, 40 m at five), never less than looks like a river for the drawn width (a wide river
    * a block or two deep reads as a puddle).
    */
   int depth(double widthMeters, double halfWidthBlocks, double z) {
      double meters = Math.max(0.8, 4.0 * Math.pow(Math.max(1.0, widthMeters) / 100.0, 0.6));
      double real = meters / metersPerBlock(z);
      double visual = 1.0 + 0.75 * Math.log(Math.max(1.0, halfWidthBlocks * 2.0)) / Math.log(2.0);
      return (int)Math.max(1, Math.min(48, Math.round(Math.max(real, visual))));
   }
}
