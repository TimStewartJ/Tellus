package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OsmWaterFeature#containsBlock} gained bounding-box rejects and {@link OsmWaterFeature.RowScanner}
 * replaces per-sample tests in the DH rasterizer; both must match the original brute-force geometry
 * tests bit for bit, for both the historical global projection and a centered (spawn-projection) world.
 */
class OsmWaterFeatureRowScannerTest {
   @Test
   void lineContainsMatchesBruteForceAcrossScalesAndLatitudes() {
      Random random = new Random(0x5eed5);
      double[] worldScales = {1.0, 4.0, 30.0, 1000.0};
      double[] latitudes = {0.2, 37.75, -33.9, 64.1};
      int mismatches = 0;
      int hits = 0;
      for (double worldScale : worldScales) {
         WorldProjection projection = WorldProjection.global(worldScale);
         for (double latitude : latitudes) {
            for (int trial = 0; trial < 6; trial++) {
               OsmWaterFeature feature = randomLine(random, projection, latitude, -119.6 + trial, 3 + random.nextInt(40));
               int[] result = compareAgainstBruteForce(feature, projection, random, true);
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
         WorldProjection projection = WorldProjection.global(worldScale);
         for (double latitude : latitudes) {
            for (int trial = 0; trial < 6; trial++) {
               OsmWaterFeature feature = randomPolygon(random, projection, latitude, 10.0 + trial, 4 + random.nextInt(24));
               int[] result = compareAgainstBruteForce(feature, projection, random, false);
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
      WorldProjection projection = WorldProjection.global(1.0);
      OsmWaterFeature feature = randomLine(random, projection, 85.03, 20.0, 12);
      int[] result = compareAgainstBruteForce(feature, projection, random, true);
      assertEquals(0, result[0]);
   }

   @Test
   void pointGeometryNeverContainsBlocks() {
      WorldProjection projection = WorldProjection.global(1.0);
      OsmWaterFeature marker = OsmWaterFeature.waterfallMarker(1L, -119.5, 37.7);
      OsmWaterFeature.RowScanner scanner = marker.rowScanner(projection);
      int blockX = (int)Math.round(projection.lonToBlockX(-119.5));
      int blockZ = (int)Math.round(projection.latToBlockZ(37.7));
      scanner.beginRow(blockZ);
      assertFalse(scanner.contains(blockX));
      assertFalse(marker.containsBlock(blockX, blockZ, projection));
   }

   @Test
   void lineContainsMatchesBruteForceUnderCenteredProjection() {
      Random random = new Random(0x5eed5 ^ 0xC3);
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      int mismatches = 0;
      int hits = 0;
      for (int trial = 0; trial < 8; trial++) {
         OsmWaterFeature feature = randomLine(
            random, projection, 37.7459 + trial * 0.4, -119.5332 + trial * 0.3, 3 + random.nextInt(40)
         );
         int[] result = compareAgainstBruteForce(feature, projection, random, true);
         mismatches += result[0];
         hits += result[1];
      }
      assertEquals(0, mismatches);
      assertTrue(hits > 50, "expected the probe grids to hit the lines often under a centered projection, got " + hits);
   }

   @Test
   void polygonContainsMatchesBruteForceUnderCenteredProjection() {
      Random random = new Random(0xca5cade ^ 0xC3);
      WorldProjection projection = WorldProjection.centeredOn(30.0, 37.7459, -119.5332);
      int mismatches = 0;
      int hits = 0;
      for (int trial = 0; trial < 8; trial++) {
         OsmWaterFeature feature = randomPolygon(
            random, projection, 37.7459 + trial * 0.4, -119.5332 + trial * 0.3, 4 + random.nextInt(24)
         );
         int[] result = compareAgainstBruteForce(feature, projection, random, false);
         mismatches += result[0];
         hits += result[1];
      }
      assertEquals(0, mismatches);
      assertTrue(
         hits > 50, "expected the probe grids to land inside the polygons often under a centered projection, got " + hits
      );
   }

   @Test
   void rowScannerSkipsSeamCrossingFeaturesLikeContainsBlock() {
      // A line whose two endpoints straddle the antimeridian relative to a centered origin crosses the
      // world seam; containsBlock always answers false for it and the scanner must stay dry too.
      WorldProjection projection = WorldProjection.centeredOn(30.0, 0.0, 0.0);
      OsmWaterFeature feature = new OsmWaterFeature(
         42L, true, false, OsmWaterKind.RIVER, new double[][]{{170.0, -170.0}}, new double[][]{{10.0, 10.0}}
      );
      assertTrue(feature.crossesWorldSeam(projection));

      OsmWaterFeature.RowScanner scanner = feature.rowScanner(projection);
      int blockZ = (int)Math.round(projection.latToBlockZ(10.0));
      scanner.beginRow(blockZ);
      int blockX = (int)Math.round(projection.lonToBlockX(170.0));
      assertFalse(scanner.contains(blockX));
      assertFalse(feature.containsBlock(blockX, blockZ, projection));
   }

   /** Returns {mismatches, hits}. */
   private static int[] compareAgainstBruteForce(
      OsmWaterFeature feature, WorldProjection projection, Random random, boolean line
   ) {
      double minLonX = projection.lonToBlockX(feature.minLon());
      double maxLonX = projection.lonToBlockX(feature.maxLon());
      int minX = (int)Math.floor(Math.min(minLonX, maxLonX)) - 3;
      int maxX = (int)Math.ceil(Math.max(minLonX, maxLonX)) + 3;
      double zA = projection.latToBlockZ(feature.minLat());
      double zB = projection.latToBlockZ(feature.maxLat());
      int minZ = (int)Math.floor(Math.min(zA, zB)) - 3;
      int maxZ = (int)Math.ceil(Math.max(zA, zB)) + 3;
      // Keep the probe grid bounded for very large features by striding.
      int strideX = Math.max(1, (maxX - minX) / 96);
      int strideZ = Math.max(1, (maxZ - minZ) / 96);
      int mismatches = 0;
      int hits = 0;
      OsmWaterFeature.RowScanner scanner = feature.rowScanner(projection);
      for (int blockZ = minZ; blockZ <= maxZ; blockZ += strideZ) {
         scanner.beginRow(blockZ);
         for (int blockX = minX; blockX <= maxX; blockX += strideX) {
            boolean expected = line
               ? bruteForceTouchesLine(feature, blockX, blockZ, projection)
               : bruteForceContainsPolygon(feature, blockX, blockZ, projection);
            boolean direct = feature.containsBlock(blockX, blockZ, projection);
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
            ? bruteForceTouchesLine(feature, blockX, blockZ, projection)
            : bruteForceContainsPolygon(feature, blockX, blockZ, projection);
         if (feature.containsBlock(blockX, blockZ, projection) != expected || scanner.contains(blockX) != expected) {
            mismatches++;
         }
      }
      return new int[]{mismatches, hits};
   }

   /**
    * The original touchesBlockLine: every segment projected and tested, no rejects. The half width is the
    * feature's own (a river centreline is a 14-block channel at 1:1, one block at 1:30).
    */
   private static boolean bruteForceTouchesLine(OsmWaterFeature feature, int blockX, int blockZ, WorldProjection projection) {
      double halfWidth = feature.lineHalfWidthBlocks(projection);
      double maxDistanceSq = halfWidth * halfWidth + 1.0E-6;
      for (int part = 0; part < feature.partCount(); part++) {
         for (int point = 1; point < feature.pointCount(part); point++) {
            double startX = projection.lonToBlockX(feature.lonAt(part, point - 1));
            double startZ = projection.latToBlockZ(feature.latAt(part, point - 1));
            double endX = projection.lonToBlockX(feature.lonAt(part, point));
            double endZ = projection.latToBlockZ(feature.latAt(part, point));
            if (distanceToSegmentSq(blockX, blockZ, startX, startZ, endX, endZ) <= maxDistanceSq) {
               return true;
            }
         }
      }
      return false;
   }

   /** The original containsBlock polygon path: bbox check then ray casting over every edge. */
   private static boolean bruteForceContainsPolygon(
      OsmWaterFeature feature, int blockX, int blockZ, WorldProjection projection
   ) {
      double lon = projection.blockXToLon(blockX);
      double lat = projection.blockZToLat(blockZ);
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
   private static OsmWaterFeature randomLine(
      Random random, WorldProjection projection, double latitude, double longitude, int points
   ) {
      int parts = 1 + random.nextInt(2);
      double[][] lons = new double[parts][];
      double[][] lats = new double[parts][];
      for (int part = 0; part < parts; part++) {
         int count = Math.max(2, points / parts);
         lons[part] = new double[count];
         lats[part] = new double[count];
         double x = projection.lonToBlockX(longitude) + random.nextDouble() * 20.0;
         double z = projection.latToBlockZ(latitude) + random.nextDouble() * 20.0;
         for (int i = 0; i < count; i++) {
            x += (random.nextDouble() - 0.4) * 6.0;
            z += (random.nextDouble() - 0.5) * 6.0;
            lons[part][i] = projection.blockXToLon(x);
            lats[part][i] = projection.blockZToLat(z);
         }
      }
      return new OsmWaterFeature(random.nextLong(), true, false, OsmWaterKind.RIVER, lons, lats);
   }

   /** A random star-shaped polygon (optionally with a hole) about 30-90 blocks across. */
   private static OsmWaterFeature randomPolygon(
      Random random, WorldProjection projection, double latitude, double longitude, int points
   ) {
      double centerX = projection.lonToBlockX(longitude);
      double centerZ = projection.latToBlockZ(latitude);
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
            lons[part][i] = projection.blockXToLon(x);
            lats[part][i] = projection.blockZToLat(z);
         }
      }
      return new OsmWaterFeature(random.nextLong(), false, false, OsmWaterKind.LAKE, lons, lats);
   }
}
