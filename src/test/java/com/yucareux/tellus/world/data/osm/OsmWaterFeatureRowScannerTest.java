package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OsmWaterFeature#containsBlock} gained bounding-box rejects and {@link OsmWaterFeature.RowScanner}
 * replaces per-sample tests in the DH rasterizer; both must match the original brute-force geometry
 * tests bit for bit.
 */
class OsmWaterFeatureRowScannerTest {
   private static final double LINE_HALF_WIDTH_BLOCKS = 0.5;

   @Test
   void lineContainsMatchesBruteForceAcrossScalesAndLatitudes() {
      Random random = new Random(0x5eed5);
      double[] worldScales = {1.0, 4.0, 30.0, 1000.0};
      double[] latitudes = {0.2, 37.75, -33.9, 64.1};
      int mismatches = 0;
      int hits = 0;
      for (double worldScale : worldScales) {
         for (double latitude : latitudes) {
            for (int trial = 0; trial < 6; trial++) {
               OsmWaterFeature feature = randomLine(random, latitude, -119.6 + trial, worldScale, 3 + random.nextInt(40));
               int[] result = compareAgainstBruteForce(feature, worldScale, random, true);
               mismatches += result[0];
               hits += result[1];
            }
         }
      }
      assertEquals(0, mismatches);
      assertTrue(hits > 200, "expected the probe grids to hit the lines often, got " + hits);
   }

   @Test
   void polygonContainsMatchesBruteForceAcrossScalesAndLatitudes() {
      Random random = new Random(0xca5cade);
      double[] worldScales = {1.0, 4.0, 30.0, 1000.0};
      double[] latitudes = {0.2, 37.75, -33.9, 64.1};
      int mismatches = 0;
      int hits = 0;
      for (double worldScale : worldScales) {
         for (double latitude : latitudes) {
            for (int trial = 0; trial < 6; trial++) {
               OsmWaterFeature feature = randomPolygon(random, latitude, 10.0 + trial, worldScale, 4 + random.nextInt(24));
               int[] result = compareAgainstBruteForce(feature, worldScale, random, false);
               mismatches += result[0];
               hits += result[1];
            }
         }
      }
      assertEquals(0, mismatches);
      assertTrue(hits > 200, "expected the probe grids to land inside the polygons often, got " + hits);
   }

   @Test
   void lineRejectsStayExactInsidePolarClamp() {
      Random random = new Random(7);
      double worldScale = 1.0;
      OsmWaterFeature feature = randomLine(random, 85.03, 20.0, worldScale, 12);
      int[] result = compareAgainstBruteForce(feature, worldScale, random, true);
      assertEquals(0, result[0]);
   }

   @Test
   void pointGeometryNeverContainsBlocks() {
      OsmWaterFeature marker = OsmWaterFeature.waterfallMarker(1L, -119.5, 37.7);
      OsmWaterFeature.RowScanner scanner = marker.rowScanner(1.0);
      int blockX = (int)Math.round(-119.5 * EarthProjection.blocksPerDegree(1.0));
      int blockZ = (int)Math.round(EarthProjection.latToBlockZ(37.7, 1.0));
      scanner.beginRow(blockZ);
      assertFalse(scanner.contains(blockX));
      assertFalse(marker.containsBlock(blockX, blockZ, 1.0));
   }

   /** Returns {mismatches, hits}. */
   private static int[] compareAgainstBruteForce(OsmWaterFeature feature, double worldScale, Random random, boolean line) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int minX = (int)Math.floor(feature.minLon() * blocksPerDegree) - 3;
      int maxX = (int)Math.ceil(feature.maxLon() * blocksPerDegree) + 3;
      double zA = EarthProjection.latToBlockZ(feature.minLat(), worldScale);
      double zB = EarthProjection.latToBlockZ(feature.maxLat(), worldScale);
      int minZ = (int)Math.floor(Math.min(zA, zB)) - 3;
      int maxZ = (int)Math.ceil(Math.max(zA, zB)) + 3;
      // Keep the probe grid bounded for very large features by striding.
      int strideX = Math.max(1, (maxX - minX) / 96);
      int strideZ = Math.max(1, (maxZ - minZ) / 96);
      int mismatches = 0;
      int hits = 0;
      OsmWaterFeature.RowScanner scanner = feature.rowScanner(worldScale);
      for (int blockZ = minZ; blockZ <= maxZ; blockZ += strideZ) {
         scanner.beginRow(blockZ);
         for (int blockX = minX; blockX <= maxX; blockX += strideX) {
            boolean expected = line
               ? bruteForceTouchesLine(feature, blockX, blockZ, worldScale)
               : bruteForceContainsPolygon(feature, blockX, blockZ, worldScale);
            boolean direct = feature.containsBlock(blockX, blockZ, worldScale);
            boolean scanned = scanner.contains(blockX);
            if (direct != expected || scanned != expected) {
               mismatches++;
            }
            if (expected) {
               hits++;
            }
         }
      }
      // A few far-away rows exercise the row-level rejects.
      for (int i = 0; i < 4; i++) {
         int blockZ = (random.nextBoolean() ? minZ : maxZ) + (random.nextBoolean() ? -1 : 1) * (10 + random.nextInt(1000));
         scanner.beginRow(blockZ);
         int blockX = minX + random.nextInt(Math.max(1, maxX - minX + 1));
         boolean expected = line
            ? bruteForceTouchesLine(feature, blockX, blockZ, worldScale)
            : bruteForceContainsPolygon(feature, blockX, blockZ, worldScale);
         if (feature.containsBlock(blockX, blockZ, worldScale) != expected || scanner.contains(blockX) != expected) {
            mismatches++;
         }
      }
      return new int[]{mismatches, hits};
   }

   /** The original touchesBlockLine: every segment projected and tested, no rejects. */
   private static boolean bruteForceTouchesLine(OsmWaterFeature feature, int blockX, int blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double maxDistanceSq = LINE_HALF_WIDTH_BLOCKS * LINE_HALF_WIDTH_BLOCKS + 1.0E-6;
      for (int part = 0; part < feature.partCount(); part++) {
         for (int point = 1; point < feature.pointCount(part); point++) {
            double startX = feature.lonAt(part, point - 1) * blocksPerDegree;
            double startZ = EarthProjection.latToBlockZ(feature.latAt(part, point - 1), worldScale);
            double endX = feature.lonAt(part, point) * blocksPerDegree;
            double endZ = EarthProjection.latToBlockZ(feature.latAt(part, point), worldScale);
            if (distanceToSegmentSq(blockX, blockZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }
      return false;
   }

   /** The original containsBlock polygon path: bbox check then ray casting over every edge. */
   private static boolean bruteForceContainsPolygon(OsmWaterFeature feature, int blockX, int blockZ, double worldScale) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double lon = blockX / blocksPerDegree;
      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      if (lon < feature.minLon() || lon > feature.maxLon() || lat < feature.minLat() || lat > feature.maxLat()) {
         return false;
      }
      boolean inside = false;
      for (int part = 0; part < feature.partCount(); part++) {
         int points = feature.pointCount(part);
         for (int i = 0, j = points - 1; i < points; j = i++) {
            double lonA = feature.lonAt(part, i);
            double latA = feature.latAt(part, i);
            double lonB = feature.lonAt(part, j);
            double latB = feature.latAt(part, j);
            if ((latA > lat) != (latB > lat)) {
               double crossLon = (lonB - lonA) * (lat - latA) / (latB - latA) + lonA;
               if (lon <= crossLon) {
                  inside = !inside;
               }
            }
         }
      }
      return inside;
   }

   private static double distanceToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
      double dx = bx - ax;
      double dz = bz - az;
      double lengthSq = dx * dx + dz * dz;
      if (lengthSq <= 1.0E-9) {
         double distX = px - ax;
         double distZ = pz - az;
         return distX * distX + distZ * distZ;
      }
      double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
      t = Math.max(0.0, Math.min(1.0, t));
      double projX = ax + t * dx;
      double projZ = az + t * dz;
      double distX = px - projX;
      double distZ = pz - projZ;
      return distX * distX + distZ * distZ;
   }

   /** A random walk of {@code points} vertices spanning roughly 40-120 blocks, as a 1-2 part line. */
   private static OsmWaterFeature randomLine(Random random, double latitude, double longitude, double worldScale, int points) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int parts = 1 + random.nextInt(2);
      double[][] lons = new double[parts][];
      double[][] lats = new double[parts][];
      for (int part = 0; part < parts; part++) {
         int count = Math.max(2, points / parts);
         lons[part] = new double[count];
         lats[part] = new double[count];
         double x = longitude * blocksPerDegree + random.nextDouble() * 20.0;
         double z = EarthProjection.latToBlockZ(latitude, worldScale) + random.nextDouble() * 20.0;
         for (int i = 0; i < count; i++) {
            x += (random.nextDouble() - 0.4) * 6.0;
            z += (random.nextDouble() - 0.5) * 6.0;
            lons[part][i] = x / blocksPerDegree;
            lats[part][i] = EarthProjection.blockZToLat(z, worldScale);
         }
      }
      return new OsmWaterFeature(random.nextLong(), true, false, OsmWaterKind.RIVER, lons, lats);
   }

   /** A random star-shaped polygon (optionally with a hole) about 30-90 blocks across. */
   private static OsmWaterFeature randomPolygon(Random random, double latitude, double longitude, double worldScale, int points) {
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double centerX = longitude * blocksPerDegree;
      double centerZ = EarthProjection.latToBlockZ(latitude, worldScale);
      boolean hole = random.nextBoolean();
      double[][] lons = new double[hole ? 2 : 1][];
      double[][] lats = new double[hole ? 2 : 1][];
      for (int part = 0; part < lons.length; part++) {
         int count = Math.max(4, part == 0 ? points : points / 2);
         lons[part] = new double[count];
         lats[part] = new double[count];
         double baseRadius = part == 0 ? 15.0 + random.nextDouble() * 30.0 : 4.0 + random.nextDouble() * 6.0;
         for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            double radius = baseRadius * (0.6 + random.nextDouble() * 0.8);
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            lons[part][i] = x / blocksPerDegree;
            lats[part][i] = EarthProjection.blockZToLat(z, worldScale);
         }
      }
      return new OsmWaterFeature(random.nextLong(), false, false, OsmWaterKind.LAKE, lons, lats);
   }
}
