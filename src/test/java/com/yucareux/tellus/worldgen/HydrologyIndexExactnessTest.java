package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The acceleration structures must reproduce the plain scans exactly, not approximately. */
class HydrologyIndexExactnessTest {
   @Test void indexedShapeQueriesMatchLinearScans() {
      Random random = new Random(0x51EA7L);
      for (int trial = 0; trial < 40; trial++) {
         boolean line = trial % 3 == 0;
         OsmWaterFeature feature = line ? randomLine(random) : randomPolygon(random, trial % 2 == 0);
         HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, WorldProjection.global(1.0));
         for (int sample = 0; sample < 2000; sample++) {
            double x = shape.minX - 200 + random.nextInt((int)(shape.maxX - shape.minX) + 400);
            double z = shape.minZ - 200 + random.nextInt((int)(shape.maxZ - shape.minZ) + 400);
            if (sample % 5 == 0) { x += random.nextDouble(); z += random.nextDouble(); }
            double maximum = new double[]{0.0, 2.999, 8.0, 48.0, 96.0, 160.0}[sample % 6];
            double expected = linearDistance(shape, x, z, maximum);
            assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(shape.distance(x, z, maximum)),
               "distance at " + x + "," + z + " within " + maximum);
            assertEquals(expected <= maximum, shape.within(x, z, maximum), "within at " + x + "," + z);
            if (!line) assertEquals(linearRings(shape, x, z), shape.ringsContaining(x, z), "rings at " + x + "," + z);
         }
         for (int box = 0; box < 200; box++) {
            int minX = (int)shape.minX - 40 + random.nextInt((int)(shape.maxX - shape.minX) + 80);
            int minZ = (int)shape.minZ - 40 + random.nextInt((int)(shape.maxZ - shape.minZ) + 80);
            int size = 1 + random.nextInt(64);
            List<String> visited = new ArrayList<>();
            shape.segments(minX, minZ, minX + size - 1, minZ + size - 1, (ax, az, bx, bz) -> visited.add(ax + "," + az + "," + bx + "," + bz));
            for (int p = 0; p < shape.xs.length; p++) for (int i = 1; i < shape.xs[p].length; i++) {
               double ax = shape.xs[p][i - 1], az = shape.zs[p][i - 1], bx = shape.xs[p][i], bz = shape.zs[p][i];
               boolean touches = Math.max(ax, bx) >= minX && Math.min(ax, bx) <= minX + size - 1
                  && Math.max(az, bz) >= minZ && Math.min(az, bz) <= minZ + size - 1;
               if (touches) assertTrue(visited.contains(ax + "," + az + "," + bx + "," + bz), "missing segment near box");
            }
         }
      }
   }

   @Test void rowContainmentMatchesPointContainment() {
      Random random = new Random(7);
      for (int trial = 0; trial < 30; trial++) {
         OsmWaterFeature feature = randomPolygon(random, trial % 2 == 1);
         double[] lons = new double[257];
         double west = feature.minLon() - 0.001, east = feature.maxLon() + 0.001;
         for (int i = 0; i < lons.length; i++) lons[i] = west + (east - west) * i / (lons.length - 1);
         boolean[] inside = new boolean[lons.length];
         for (int row = 0; row <= 64; row++) {
            double lat = feature.minLat() - 0.001 + (feature.maxLat() - feature.minLat() + 0.002) * row / 64.0;
            feature.containsLonLatRow(lat, lons, 3, lons.length - 4, inside);
            for (int i = 3; i <= lons.length - 4; i++)
               assertEquals(feature.containsLonLat(lons[i], lat), inside[i], "row " + row + " cell " + i);
         }
      }
   }

   private static OsmWaterFeature randomPolygon(Random random, boolean withHole) {
      int count = 30 + random.nextInt(200);
      double cx = random.nextInt(4000) - 2000, cz = random.nextInt(4000) - 2000, radius = 100 + random.nextInt(900);
      List<double[]> outer = ring(random, cx, cz, radius, count);
      List<List<double[]>> parts = new ArrayList<>(List.of(outer));
      if (withHole) parts.add(ring(random, cx, cz, radius * 0.3, 12 + random.nextInt(30)));
      return feature(false, parts);
   }

   private static OsmWaterFeature randomLine(Random random) {
      int count = 30 + random.nextInt(300);
      double x = random.nextInt(4000), z = random.nextInt(4000), heading = random.nextDouble() * Math.PI * 2;
      List<double[]> points = new ArrayList<>();
      for (int i = 0; i < count; i++) {
         points.add(new double[]{x, z});
         heading += random.nextGaussian() * 0.4;
         double length = 2 + random.nextInt(40);
         x += Math.cos(heading) * length;
         z += Math.sin(heading) * length;
      }
      return feature(true, List.of(points));
   }

   private static List<double[]> ring(Random random, double cx, double cz, double radius, int count) {
      List<double[]> points = new ArrayList<>();
      for (int i = 0; i < count; i++) {
         double angle = Math.PI * 2 * i / count, r = radius * (0.6 + 0.4 * random.nextDouble());
         // Some vertices sit exactly on integer block coordinates.
         double x = cx + Math.cos(angle) * r, z = cz + Math.sin(angle) * r;
         if (random.nextInt(4) == 0) { x = Math.rint(x); z = Math.rint(z); }
         points.add(new double[]{x, z});
      }
      points.add(points.get(0).clone());
      return points;
   }

   private static OsmWaterFeature feature(boolean line, List<? extends List<double[]>> parts) {
      double[][] lon = new double[parts.size()][], lat = new double[parts.size()][];
      for (int p = 0; p < parts.size(); p++) {
         List<double[]> points = parts.get(p);
         lon[p] = new double[points.size()];
         lat[p] = new double[points.size()];
         for (int i = 0; i < points.size(); i++) {
            lon[p][i] = WorldProjection.global(1.0).blockXToLon(points.get(i)[0]);
            lat[p][i] = WorldProjection.global(1.0).blockZToLat(points.get(i)[1]);
         }
      }
      return new OsmWaterFeature(7L, line, false, line ? OsmWaterKind.STREAM : OsmWaterKind.LAKE, lon, lat);
   }

   /** The original unindexed distance scan. */
   private static double linearDistance(HydrologyWaterMap.Shape shape, double x, double z, double maximum) {
      if (x < shape.minX - maximum || x > shape.maxX + maximum || z < shape.minZ - maximum || z > shape.maxZ + maximum)
         return Double.POSITIVE_INFINITY;
      double best = Double.POSITIVE_INFINITY;
      for (int p = 0; p < shape.xs.length; p++) {
         for (int i = 1; i < shape.xs[p].length; i++) {
            double ax = shape.xs[p][i - 1], az = shape.zs[p][i - 1], bx = shape.xs[p][i], bz = shape.zs[p][i];
            if (x < Math.min(ax, bx) - maximum || x > Math.max(ax, bx) + maximum
               || z < Math.min(az, bz) - maximum || z > Math.max(az, bz) + maximum) continue;
            double dx = bx - ax, dz = bz - az;
            double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(x - ax - t * dx, z - az - t * dz));
         }
      }
      return best;
   }

   /** The original unindexed even-odd ring count. */
   private static int linearRings(HydrologyWaterMap.Shape shape, double x, double z) {
      if (x < shape.minX || z < shape.minZ || x > shape.maxX || z > shape.maxZ) return 0;
      int rings = 0;
      for (int p = 0; p < shape.xs.length; p++) {
         boolean inside = false;
         double[] xs = shape.xs[p], zs = shape.zs[p];
         for (int i = 0, j = xs.length - 1; i < xs.length; j = i++)
            if ((zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside;
         if (inside) rings++;
      }
      return rings;
   }
}
