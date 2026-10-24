package com.yucareux.tellus.world.data.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TellusResolveSourceWarpTest {
   private static final double WORLD_SCALE = 30.0;

   @Test
   void ecoregionBordersLeaveTheRasterCellEdges() {
      TellusResolveSource source = TellusResolveSource.shared();
      assumeTrue(source.available(), "bundled RESOLVE lookup");
      double cellDegrees = source.arcsecondsPerCell() / 3600.0;

      // Find a border between two cells of one raster row in western Mexico.
      double latitude = (Math.floor(21.65 / cellDegrees) + 0.5) * cellDegrees;
      double edgeLongitude = Double.NaN;
      int westId = -1;
      int eastId = -1;
      for (int cell = 0; cell < 2000 && Double.isNaN(edgeLongitude); cell++) {
         double west = (Math.floor(-104.5 / cellDegrees) + cell + 0.5) * cellDegrees;
         int a = source.sampleAtLonLat(west, latitude).ecoId();
         int b = source.sampleAtLonLat(west + cellDegrees, latitude).ecoId();
         if (a != b && a >= 0 && b >= 0) {
            edgeLongitude = west + cellDegrees * 0.5;
            westId = a;
            eastId = b;
         }
      }
      assumeTrue(Double.isFinite(edgeLongitude), "a border in the sampled row");

      // Within that one raster row the nearest-cell border is a straight line on the cell edge.
      double blocksPerDegree = EarthProjection.blocksPerDegree(WORLD_SCALE);
      double cellBlocks = cellDegrees * blocksPerDegree;
      int edgeX = (int)Math.round(edgeLongitude * blocksPerDegree);
      int rowZ = (int)Math.round(EarthProjection.latToBlockZ(latitude, WORLD_SCALE));
      int reach = (int)Math.ceil(cellBlocks * 2.0);
      Set<Integer> borders = new HashSet<>();
      for (int dz = -(int)(cellBlocks * 0.4); dz <= (int)(cellBlocks * 0.4); dz++) {
         int z = rowZ + dz;
         for (int x = edgeX - reach; x < edgeX + reach; x++) {
            int here = source.sampleEcoregion(x, z, WORLD_SCALE).ecoId();
            int next = source.sampleEcoregion(x + 1, z, WORLD_SCALE).ecoId();
            assertEquals(here, source.sampleEcoregion(x, z, WORLD_SCALE).ecoId(), "lookups are deterministic");
            if (here == westId && next == eastId) {
               borders.add(x - edgeX);
               break;
            }
         }
      }
      assertTrue(borders.size() >= 4, "the border must wander across rows, found " + borders);
      for (int offset : borders) {
         assertTrue(Math.abs(offset) <= reach, "the warp stays within about two cells: " + offset);
      }
   }
}
