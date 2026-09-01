package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.Arrays;

/**
 * Shapes a watercourse that Overture maps only as a centreline into a channel Minecraft can hold.
 *
 * <p>A mapped centreline says where a stream runs, not how wide it is or how the DEM disagrees with
 * it block by block. Three pure rules turn the line into a channel:
 *
 * <ul>
 *   <li><b>Width</b> comes from the mapped kind (river, canal, stream, ditch) in metres and the world
 *       scale, so a stream is three blocks at 1:1 and a single block at 1:30.</li>
 *   <li><b>Depth</b> of the bed below the water surface grows with the width.</li>
 *   <li><b>Surface</b>: the line is sampled at one-block stations along its length; each station's
 *       terrain value is the lowest ground across the channel and its banks. The water surface at a
 *       station is the lowest such value within {@code lookback} stations upstream, so DEM noise
 *       never makes water climb, banks are never lower than the water beside them, and every column
 *       across the channel shares one height. Where the terrain rises more than {@code maxCut} above
 *       that running level the mapped line is climbing through a hump the channel may not cut: the
 *       station stays dry as a sill, the water resumes at the crest and follows the ground down the
 *       far side. Sills keep an upstream pool from spilling and keep the higher downstream reach
 *       from flowing backwards over it.</li>
 * </ul>
 *
 * <p>Stations are ordered in the mapped direction of the line; OSM draws waterways downstream, which
 * Overture preserves. The rules depend only on stations within {@code 2 * lookback} of each other,
 * so overlapping analysis windows agree wherever they both see the line.
 */
final class StreamChannelProfile {
   /** Widest channel a centreline may carve, in blocks, however fine the world scale. */
   static final int MAX_WIDTH_BLOCKS = OsmWaterKind.MAX_CENTERLINE_WIDTH_BLOCKS;
   /** Stations upstream a surface may look back to; twice this must fit inside the analysis margin. */
   static final int LOOKBACK_STATIONS = 32;
   /** Station carries no water: the channel would have to cut deeper than allowed through a hump. */
   static final int DRY = Integer.MIN_VALUE;
   /** No terrain sample is available for the station (outside the analysed grid). */
   static final int UNKNOWN = Integer.MAX_VALUE;

   private StreamChannelProfile() {
   }

   /** Channel width in whole blocks for a kind of watercourse at the given ground scale. */
   static int widthBlocks(OsmWaterKind kind, double groundMetersPerBlock) {
      return (kind == null ? OsmWaterKind.UNKNOWN : kind).centerlineWidthBlocks(groundMetersPerBlock);
   }

   /**
    * Distance from the centreline within which a block belongs to the channel. A block's centre
    * within this distance is wet; a width of one keeps the historical half-block line.
    */
   static double halfWidthBlocks(int widthBlocks) {
      return Math.max(1, widthBlocks) / 2.0;
   }

   /** Bed depth below the water surface, in blocks, for a channel of the given width. */
   static int depthBlocks(int widthBlocks) {
      if (widthBlocks <= 2) {
         return 1;
      } else if (widthBlocks <= 6) {
         return 2;
      } else {
         return widthBlocks <= 12 ? 3 : 4;
      }
   }

   /**
    * Water surface per station from the lowest ground across the channel at each station.
    *
    * @param stationMin lowest terrain height across the channel and its banks at each station in flow
    *        order, or {@link #UNKNOWN} where no terrain was sampled
    * @param lookback how many upstream stations the running level may remember
    * @param maxCut deepest cut below the local ground before a hump is left as a sill
    * @return the surface per station: a height, {@link #DRY} for a sill, or {@link #UNKNOWN} where the
    *         station had no sample (callers follow the terrain there)
    */
   static int[] surfaces(int[] stationMin, int lookback, int maxCut) {
      int count = stationMin.length;
      int[] out = new int[count];
      int window = Math.max(0, lookback);
      int cut = Math.max(0, maxCut);
      int windowStart = 0;
      boolean climbing = false;

      for (int k = 0; k < count; k++) {
         int here = stationMin[k];
         if (here == UNKNOWN) {
            out[k] = UNKNOWN;
            // No sample breaks the memory of the reach: the next known station starts fresh.
            windowStart = k + 1;
            climbing = false;
            continue;
         }
         if (climbing) {
            // Stay dry while the mapped line keeps rising; resume on the crest or the far slope so
            // the last dry station stands at least as high as the water that follows it.
            if (k > 0 && stationMin[k - 1] != UNKNOWN && here > stationMin[k - 1]) {
               out[k] = DRY;
               continue;
            }
            climbing = false;
            windowStart = k;
         }
         int level = UNKNOWN;
         for (int j = Math.max(windowStart, k - window); j <= k; j++) {
            int sample = stationMin[j];
            if (sample != UNKNOWN && sample < level) {
               level = sample;
            }
         }
         if (here - level > cut) {
            out[k] = DRY;
            climbing = true;
            continue;
         }
         out[k] = level;
      }
      return out;
   }

   /** Lowest known value, or {@link #UNKNOWN} when no station has a sample. */
   static int lowestKnown(int[] stationMin) {
      int lowest = UNKNOWN;
      for (int value : stationMin) {
         if (value != UNKNOWN && value < lowest) {
            lowest = value;
         }
      }
      return lowest;
   }

   /** Counts stations that carry water. */
   static int wetStations(int[] surfaces) {
      int wet = 0;
      for (int value : surfaces) {
         if (value != DRY && value != UNKNOWN) {
            wet++;
         }
      }
      return wet;
   }

   static int[] filled(int count, int value) {
      int[] values = new int[count];
      Arrays.fill(values, value);
      return values;
   }
}
