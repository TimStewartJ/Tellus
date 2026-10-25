package com.yucareux.tellus.worldgen;

import java.util.Arrays;

/** Geometry rules shared by terrain, map previews and sampled terrain consumers. */
final class InlandWaterProfile {
   private static final int[] CARDINAL = {1, 0, -1, 0, 0, 1, 0, -1};

   private InlandWaterProfile() {
   }

   static int riverDepth(double shoreDistance, int worldX, int worldZ, int maximumDepth, int depthStep) {
      double broad = noise(worldX, worldZ, 73, 0x4B17A631L);
      double bars = noise(worldX, worldZ, 23, 0x912F6DABL);
      double slope = (0.85 + 0.35 * broad + 0.2 * bars) / Math.max(1, depthStep);
      double depth = 1.0 + Math.max(0.0, shoreDistance - 0.5) * slope;
      // Broad pools and bars change the submerged channel without moving water
      // outside the mapped footprint. Opposite banks sample different fields.
      return Math.max(1, Math.min(maximumDepth, (int)Math.round(depth)));
   }

   static int bankSurface(
      int base, int water, double distance, int radius, boolean limitSlope, int worldX, int worldZ
   ) {
      if (radius <= 0 || distance > radius || base <= water) {
         return base;
      }
      double broad = noise(worldX, worldZ, 79, 0x5718EF23L);
      double local = noise(worldX, worldZ, 19, 0xA331B927L);
      double effectiveRadius = Math.max(2.0, radius * (0.78 + 0.18 * broad));
      double progress = Math.min(1.0, distance / effectiveRadius);
      double recovery = smooth(progress);
      double bankSlope = (limitSlope ? 0.42 : 0.65) + 0.22 * broad + 0.14 * local;
      double toe = water + 0.35 + Math.max(0.0, distance - 1.0) * bankSlope;
      // Recover the original terrain with zero derivative at the outer edge.
      // A hard slope cap at that edge used to leave a vertical ring of terrain.
      double shaped = Math.min(base, toe) + (base - Math.min(base, toe)) * recovery;
      return Math.max(water, Math.min(base, (int)Math.round(shaped)));
   }

   /**
    * Seal dry banks against the highest touching water, and connect the beds of
    * neighboring wet columns vertically. All readers then see complete geometry
    * immediately, including readers that never run Minecraft fluid ticks.
    * Ocean columns and dry columns touching the ocean are deliberately untouched.
    */
   static void finishColumns(int[] terrain, int[] water, boolean[] wet, boolean[] ocean, int size) {
      finishColumns(terrain, water, wet, ocean, size, null);
   }

   static void finishColumns(
      int[] terrain, int[] water, boolean[] wet, boolean[] ocean, int size, boolean[] protectedCoast
   ) {
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            int cell = z * size + x;
            if (ocean[cell]) {
               continue;
            }
            int lowest = water[cell];
            int highest = Integer.MIN_VALUE;
            boolean touchesOcean = false;
            for (int n = 0; n < CARDINAL.length; n += 2) {
               int nx = x + CARDINAL[n];
               int nz = z + CARDINAL[n + 1];
               if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                  continue;
               }
               int neighbor = nz * size + nx;
               touchesOcean |= ocean[neighbor];
               if (wet[neighbor] && !ocean[neighbor]) {
                  lowest = Math.min(lowest, water[neighbor]);
                  highest = Math.max(highest, water[neighbor]);
               }
            }
            if (wet[cell]) {
               terrain[cell] = Math.min(terrain[cell], lowest - 1);
            } else if (!touchesOcean && (protectedCoast == null || !protectedCoast[cell]) && highest != Integer.MIN_VALUE) {
               terrain[cell] = Math.max(terrain[cell], highest);
            }
         }
      }
   }

   static boolean hasSurfaceStep(int cell, int size, int[] water, boolean[] wet) {
      int x = cell % size;
      int z = cell / size;
      for (int n = 0; n < CARDINAL.length; n += 2) {
         int nx = x + CARDINAL[n];
         int nz = z + CARDINAL[n + 1];
         if (nx >= 0 && nz >= 0 && nx < size && nz < size) {
            int neighbor = nz * size + nx;
            if (wet[neighbor] && water[neighbor] != water[cell]) {
               return true;
            }
         }
      }
      return false;
   }

   static boolean[] coastalLand(boolean[] ocean, int size, int radiusCells) {
      int area = size * size;
      boolean[] protectedLand = new boolean[area];
      int[] distance = new int[area];
      int[] queue = new int[area];
      Arrays.fill(distance, -1);
      int head = 0;
      int tail = 0;
      for (int cell = 0; cell < area; cell++) {
         if (ocean[cell]) {
            distance[cell] = 0;
            queue[tail++] = cell;
         }
      }
      while (head < tail) {
         int cell = queue[head++];
         if (distance[cell] >= radiusCells) {
            continue;
         }
         int x = cell % size;
         int z = cell / size;
         for (int n = 0; n < CARDINAL.length; n += 2) {
            int nx = x + CARDINAL[n];
            int nz = z + CARDINAL[n + 1];
            if (nx >= 0 && nz >= 0 && nx < size && nz < size) {
               int neighbor = nz * size + nx;
               if (distance[neighbor] < 0) {
                  distance[neighbor] = distance[cell] + 1;
                  protectedLand[neighbor] = true;
                  queue[tail++] = neighbor;
               }
            }
         }
      }
      return protectedLand;
   }

   static double noise(long x, long z, int scale, long salt) {
      long cx = Math.floorDiv(x, scale);
      long cz = Math.floorDiv(z, scale);
      double fx = smooth(Math.floorMod(x, scale) / (double)scale);
      double fz = smooth(Math.floorMod(z, scale) / (double)scale);
      double north = mix(hash(cx, cz, salt), hash(cx + 1, cz, salt), fx);
      double south = mix(hash(cx, cz + 1, salt), hash(cx + 1, cz + 1, salt), fx);
      return mix(north, south, fz);
   }

   private static double hash(long x, long z, long salt) {
      long value = x * -7046029254386353131L ^ z * -4417276706812531889L ^ salt;
      value = (value ^ value >>> 30) * -4658895280553007687L;
      value = (value ^ value >>> 27) * -7723592293110705685L;
      return ((value ^ value >>> 31) >>> 11) * 0x1.0p-52 - 1.0;
   }

   private static double mix(double a, double b, double t) {
      return a + (b - a) * t;
   }

   private static double smooth(double t) {
      return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
   }
}
