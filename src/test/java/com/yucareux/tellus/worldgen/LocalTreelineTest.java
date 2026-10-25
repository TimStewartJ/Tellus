package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import org.junit.jupiter.api.Test;

class LocalTreelineTest {
   private static final double PIXEL = 1.0 / 120.0;
   private static final TellusKoppenSource.PixelGrid GRID = new TellusKoppenSource.PixelGrid(-180.0, 90.0, PIXEL, PIXEL, 43200, 21600);
   private static final int DFB = 26;
   // A cone peaking at 4,000 m whose climate turns to tundra above 2,600 m, set
   // across a one-degree tile edge so the field is checked for seams too.
   private static final double PEAK_LATITUDE = 46.5;
   private static final double PEAK_LONGITUDE = 10.98;
   private static final double TUNDRA_METERS = 2600.0;

   @Test
   void treelineFollowsTheLocalTundraBorder() {
      LocalTreeline treeline = new LocalTreeline(raster(TUNDRA_METERS), LocalTreelineTest::cone);
      // The latitude curve puts this treeline near 2,050 m.
      assertEquals(2050.0, MountainElevationZones.treelineMeters(PEAK_LATITUDE), 1.0);
      assertEquals(TUNDRA_METERS, treeline.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE), 100.0);
      assertEquals(TUNDRA_METERS, treeline.treelineMeters(PEAK_LATITUDE + 0.1, PEAK_LONGITUDE - 0.1), 150.0);
   }

   @Test
   void farFromAnyTundraTheLatitudeCurveRemains() {
      LocalTreeline treeline = new LocalTreeline(raster(TUNDRA_METERS), LocalTreelineTest::cone);
      double latitude = PEAK_LATITUDE + 1.5;
      assertEquals(MountainElevationZones.treelineMeters(latitude), treeline.treelineMeters(latitude, PEAK_LONGITUDE + 2.0), 1.0E-6);
   }

   @Test
   void theFieldMovesSmoothlyAcrossTileEdges() {
      LocalTreeline treeline = new LocalTreeline(raster(TUNDRA_METERS), LocalTreelineTest::cone);
      double previous = treeline.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE - 0.6);
      for (int step = 1; step <= 120; step++) {
         double current = treeline.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE - 0.6 + step * 0.01);
         assertTrue(Math.abs(current - previous) < 40.0, "east step " + step + ": " + previous + " -> " + current);
         previous = current;
      }
      // North across the 47th parallel, where the neighbouring tile starts.
      previous = treeline.treelineMeters(PEAK_LATITUDE - 0.2, PEAK_LONGITUDE);
      for (int step = 1; step <= 90; step++) {
         double current = treeline.treelineMeters(PEAK_LATITUDE - 0.2 + step * 0.01, PEAK_LONGITUDE);
         assertTrue(Math.abs(current - previous) < 40.0, "north step " + step + ": " + previous + " -> " + current);
         previous = current;
      }
   }

   @Test
   void missingDataFallsBackToTheLatitudeCurve() {
      double curve = MountainElevationZones.treelineMeters(PEAK_LATITUDE);
      LocalTreeline noElevation = new LocalTreeline(raster(TUNDRA_METERS), (latitude, longitude) -> Double.NaN);
      assertEquals(curve, noElevation.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE), 1.0E-6);
      LocalTreeline noClimate = new LocalTreeline(
         new LocalTreeline.ClimateRaster() {
            @Override
            public TellusKoppenSource.PixelGrid grid() {
               return null;
            }

            @Override
            public int classIndex(int pixelX, int pixelY) {
               return 0;
            }
         },
         LocalTreelineTest::cone
      );
      assertEquals(curve, noClimate.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE), 1.0E-6);
   }

   @Test
   void strayBordersMoveTheTreelineOnlySoFar() {
      // A tundra border at sea level, far below anything the climate allows here.
      LocalTreeline treeline = new LocalTreeline(raster(-1.0E9), (latitude, longitude) -> 50.0);
      double curve = MountainElevationZones.treelineMeters(PEAK_LATITUDE);
      assertEquals(curve, treeline.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE), 1.0E-6);
      LocalTreeline halfTundra = new LocalTreeline(
         new LocalTreeline.ClimateRaster() {
            @Override
            public TellusKoppenSource.PixelGrid grid() {
               return GRID;
            }

            @Override
            public int classIndex(int pixelX, int pixelY) {
               return GRID.centerLongitude(pixelX) < PEAK_LONGITUDE ? TellusKoppenSource.CLASS_ET : DFB;
            }
         },
         (latitude, longitude) -> 50.0
      );
      assertEquals(curve - LocalTreeline.MAX_DEVIATION_METERS, halfTundra.treelineMeters(PEAK_LATITUDE, PEAK_LONGITUDE), 1.0);
   }

   @Test
   void zonesFollowTheTreeline() {
      MountainElevationZones.Band curve = MountainElevationZones.band(PEAK_LATITUDE, 30.0, 1.0, 1.0, false, true);
      MountainElevationZones.Band raised = MountainElevationZones.bandForTreeline(
         MountainElevationZones.treelineMeters(PEAK_LATITUDE) + 500.0, PEAK_LATITUDE, 30.0, 1.0, 1.0, false, true
      );
      assertTrue(raised.highlandBlocks() > curve.highlandBlocks());
      assertTrue(raised.alpineBlocks() > curve.alpineBlocks());
      MountainElevationZones.Band same = MountainElevationZones.bandForTreeline(
         MountainElevationZones.treelineMeters(PEAK_LATITUDE), PEAK_LATITUDE, 30.0, 1.0, 1.0, false, true
      );
      assertEquals(curve, same);
   }

   private static LocalTreeline.ClimateRaster raster(double tundraMeters) {
      return new LocalTreeline.ClimateRaster() {
         @Override
         public TellusKoppenSource.PixelGrid grid() {
            return GRID;
         }

         @Override
         public int classIndex(int pixelX, int pixelY) {
            double elevation = cone(GRID.centerLatitude(pixelY), GRID.centerLongitude(pixelX));
            return elevation > tundraMeters ? TellusKoppenSource.CLASS_ET : DFB;
         }
      };
   }

   /** 4,000 m at the peak, falling 100 m per kilometre to a 600 m plain. */
   private static double cone(double latitude, double longitude) {
      double northKm = (latitude - PEAK_LATITUDE) * 111.32;
      double eastKm = (longitude - PEAK_LONGITUDE) * 111.32 * Math.cos(Math.toRadians(latitude));
      return Math.max(600.0, 4000.0 - 100.0 * Math.hypot(northKm, eastKm));
   }
}
