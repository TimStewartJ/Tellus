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
 *       terrain value is the lowest ground across the channel and its banks. Both source orientations
 *       are scored against that terrain because vector-tile clipping does not guarantee downstream
 *       vertex order. The lower-conflict direction becomes the reach direction. Its wet surface never
 *       climbs; bounded short DEM humps may be cut through with a larger hydro-flattening budget, while
 *       unresolved conflicts stay dry until the terrain returns to the established reach level.</li>
 * </ul>
 *
 * <p>This class profiles one assembled geometry part. Direction and surface decisions are deterministic
 * for the complete sampled part; callers must provide the same part and terrain halo regardless of the
 * chunk being generated.
 */
final class StreamChannelProfile {
   /** Widest channel a centreline may carve, in blocks, however fine the world scale. */
   static final int MAX_WIDTH_BLOCKS = OsmWaterKind.MAX_CENTERLINE_WIDTH_BLOCKS;
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
    * Infers the lower-conflict flow direction and profiles a non-climbing wet reach.
    *
    * @param stationMin terrain minima in source geometry order
    * @param ordinaryMaxCut maximum persistent cut below terrain
    * @param shortHumpMaxCut larger cut allowed only for a bracketed short DEM hump
    * @param shortHumpMaxLength maximum stations from a hump to terrain returning near the reach level
    */
   static Profile profile(
      int[] stationMin,
      int ordinaryMaxCut,
      int shortHumpMaxCut,
      int shortHumpMaxLength
   ) {
      return profile(
         stationMin,
         ordinaryMaxCut,
         shortHumpMaxCut,
         shortHumpMaxLength,
         false
      );
   }

   static Profile profile(
      int[] stationMin,
      int ordinaryMaxCut,
      int shortHumpMaxCut,
      int shortHumpMaxLength,
      boolean reverseOnTie
   ) {
      int cut = Math.max(0, ordinaryMaxCut);
      int bridgeCut = Math.max(cut, shortHumpMaxCut);
      int bridgeLength = Math.max(0, shortHumpMaxLength);
      int[] sourceSurfaces = profileDirected(stationMin, cut, bridgeCut, bridgeLength);
      int[] reversedTerrain = reversed(stationMin);
      int[] reversedSurfaces = profileDirected(reversedTerrain, cut, bridgeCut, bridgeLength);
      Conflict sourceConflict = profileConflict(stationMin, sourceSurfaces);
      Conflict reversedConflict = profileConflict(reversedTerrain, reversedSurfaces);
      int comparison = reversedConflict.compareTo(sourceConflict);
      boolean reversed = comparison < 0 || comparison == 0 && reverseOnTie;
      int[] surfaces = reversed ? reversed(reversedSurfaces) : sourceSurfaces;
      return new Profile(
         surfaces,
         reversed ? Direction.REVERSED : Direction.SOURCE_ORDER,
         sourceConflict,
         reversedConflict
      );
   }

   static int[] profileDirected(
      int[] terrain,
      int ordinaryMaxCut,
      int shortHumpMaxCut,
      int shortHumpMaxLength
   ) {
      int[] surfaces = new int[terrain.length];
      int reachLevel = UNKNOWN;
      for (int station = 0; station < terrain.length; station++) {
         int here = terrain[station];
         if (here == UNKNOWN) {
            surfaces[station] = UNKNOWN;
            reachLevel = UNKNOWN;
            continue;
         }
         if (reachLevel == UNKNOWN || here < reachLevel) {
            reachLevel = here;
            surfaces[station] = here;
            continue;
         }
         int requiredCut = here - reachLevel;
         if (requiredCut <= ordinaryMaxCut
            || requiredCut <= shortHumpMaxCut
               && shortHumpReturns(
                  terrain,
                  station,
                  reachLevel,
                  ordinaryMaxCut,
                  shortHumpMaxCut,
                  shortHumpMaxLength
               )) {
            surfaces[station] = reachLevel;
         } else {
            surfaces[station] = DRY;
         }
      }
      return surfaces;
   }

   private static boolean shortHumpReturns(
      int[] terrain,
      int start,
      int reachLevel,
      int ordinaryMaxCut,
      int shortHumpMaxCut,
      int maxLength
   ) {
      int end = Math.min(terrain.length - 1, start + maxLength);
      for (int station = start; station <= end; station++) {
         int sample = terrain[station];
         if (sample == UNKNOWN || sample - reachLevel > shortHumpMaxCut) {
            return false;
         }
         if (station > start && sample - reachLevel <= ordinaryMaxCut) {
            return true;
         }
      }
      return false;
   }

   private static Conflict profileConflict(int[] terrain, int[] surfaces) {
      long barriers = 0L;
      long cutCost = 0L;
      long ascent = 0L;
      int previous = UNKNOWN;
      for (int station = 0; station < terrain.length; station++) {
         int sample = terrain[station];
         if (sample == UNKNOWN) {
            previous = UNKNOWN;
            continue;
         }
         if (previous != UNKNOWN && sample > previous) {
            ascent = saturatedAdd(ascent, sample - previous);
         }
         previous = sample;
         int surface = surfaces[station];
         if (surface == DRY) {
            barriers++;
            continue;
         }
         if (surface != UNKNOWN) {
            long cut = Math.max(0L, (long)sample - surface);
            cutCost = saturatedAdd(cutCost, cut * cut);
         }
      }
      int start = endpointMedian(terrain, false);
      int end = endpointMedian(terrain, true);
      long endpointRise = start == UNKNOWN || end == UNKNOWN ? 0L : Math.max(0L, (long)end - start);
      return new Conflict(barriers, endpointRise, cutCost, ascent);
   }

   private static int endpointMedian(int[] terrain, boolean fromEnd) {
      int band = Math.min(8, Math.max(1, terrain.length / 3));
      int[] values = new int[band];
      int count = 0;
      for (int offset = 0; offset < terrain.length && count < values.length; offset++) {
         int index = fromEnd ? terrain.length - 1 - offset : offset;
         int sample = terrain[index];
         if (sample != UNKNOWN) {
            values[count++] = sample;
         }
      }
      if (count == 0) {
         return UNKNOWN;
      }
      Arrays.sort(values, 0, count);
      return values[count / 2];
   }

   private static long saturatedAdd(long left, long right) {
      return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
   }

   private static int[] reversed(int[] values) {
      int[] reversed = new int[values.length];
      for (int i = 0; i < values.length; i++) {
         reversed[i] = values[values.length - 1 - i];
      }
      return reversed;
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

   enum Direction {
      SOURCE_ORDER,
      REVERSED
   }

   record Profile(
      int[] surfaces,
      Direction direction,
      Conflict sourceOrderConflict,
      Conflict reversedConflict
   ) {
      Profile {
         surfaces = surfaces.clone();
      }

      @Override
      public int[] surfaces() {
         return this.surfaces.clone();
      }

      int stationCount() {
         return this.surfaces.length;
      }
   }

   record Conflict(long barriers, long endpointRise, long cutCost, long ascent)
      implements Comparable<Conflict> {
      @Override
      public int compareTo(Conflict other) {
         int result = Long.compare(this.barriers, other.barriers);
         if (result == 0) {
            result = Long.compare(this.endpointRise, other.endpointRise);
         }
         if (result == 0) {
            result = Long.compare(this.cutCost, other.cutCost);
         }
         return result == 0 ? Long.compare(this.ascent, other.ascent) : result;
      }
   }
}
