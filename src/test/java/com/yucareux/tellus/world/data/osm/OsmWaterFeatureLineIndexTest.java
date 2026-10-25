package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The bucketed line index must answer exactly like a scan of every projected segment. */
class OsmWaterFeatureLineIndexTest {
   private static final double HALF_WIDTH = 0.5;

   @Test
   void indexedLineContainmentMatchesFullSegmentScan() {
      Random random = new Random(0x5EED_1234L);
      for (double worldScale : new double[]{1.0, 3.5, 30.0}) {
         double blocksPerDegree = WorldProjection.global(worldScale).equatorialBlocksPerDegree();
         for (int trial = 0; trial < 40; trial++) {
            int parts = 1 + random.nextInt(3);
            double[][] lons = new double[parts][];
            double[][] lats = new double[parts][];
            double startLon = -103.4 + random.nextDouble() * 0.2;
            double startLat = 20.6 + random.nextDouble() * 0.2;
            for (int part = 0; part < parts; part++) {
               int points = 2 + random.nextInt(60);
               lons[part] = new double[points];
               lats[part] = new double[points];
               double lon = startLon + random.nextGaussian() * 0.001;
               double lat = startLat + random.nextGaussian() * 0.001;
               for (int point = 0; point < points; point++) {
                  // Mix sub-block and long segments.
                  double step = random.nextBoolean() ? 0.5 / blocksPerDegree : (5 + random.nextInt(200)) / blocksPerDegree;
                  lon += (random.nextDouble() - 0.5) * step;
                  lat += (random.nextDouble() - 0.5) * step;
                  lons[part][point] = lon;
                  lats[part][point] = lat;
               }
            }
            OsmWaterFeature feature = new OsmWaterFeature(trial, true, false, OsmWaterKind.STREAM, lons, lats);
            int centerX = (int)Math.floor(lons[0][0] * blocksPerDegree);
            int centerZ = (int)Math.floor(WorldProjection.global(worldScale).latToBlockZ(lats[0][0]));
            for (int sample = 0; sample < 3000; sample++) {
               int x = centerX + random.nextInt(801) - 400;
               int z = centerZ + random.nextInt(801) - 400;
               assertEquals(reference(lons, lats, x, z, worldScale), feature.containsBlock(x, z, WorldProjection.global(worldScale)),
                  "scale=" + worldScale + " trial=" + trial + " at " + x + "," + z);
            }
            // Probe right next to every vertex, where the reach boundary matters most.
            for (int part = 0; part < parts; part++) {
               for (int point = 0; point < lons[part].length; point++) {
                  int x = (int)Math.round(lons[part][point] * blocksPerDegree);
                  int z = (int)Math.round(WorldProjection.global(worldScale).latToBlockZ(lats[part][point]));
                  for (int dz = -2; dz <= 2; dz++) {
                     for (int dx = -2; dx <= 2; dx++) {
                        assertEquals(reference(lons, lats, x + dx, z + dz, worldScale), feature.containsBlock(x + dx, z + dz, WorldProjection.global(worldScale)));
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean reference(double[][] lons, double[][] lats, int blockX, int blockZ, double worldScale) {
      double blocksPerDegree = WorldProjection.global(worldScale).equatorialBlocksPerDegree();
      double maxDistanceSq = HALF_WIDTH * HALF_WIDTH + 1.0E-6;
      for (int part = 0; part < lons.length; part++) {
         for (int point = 1; point < lons[part].length; point++) {
            double ax = lons[part][point - 1] * blocksPerDegree;
            double az = WorldProjection.global(worldScale).latToBlockZ(lats[part][point - 1]);
            double bx = lons[part][point] * blocksPerDegree;
            double bz = WorldProjection.global(worldScale).latToBlockZ(lats[part][point]);
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            double distanceSq;
            if (lengthSq <= 1.0E-9) {
               distanceSq = (blockX - ax) * (blockX - ax) + (blockZ - az) * (blockZ - az);
            } else {
               double t = Math.max(0.0, Math.min(1.0, ((blockX - ax) * dx + (blockZ - az) * dz) / lengthSq));
               double px = blockX - (ax + t * dx);
               double pz = blockZ - (az + t * dz);
               distanceSq = px * px + pz * pz;
            }
            if (distanceSq <= maxDistanceSq) {
               return true;
            }
         }
      }
      return false;
   }
}
