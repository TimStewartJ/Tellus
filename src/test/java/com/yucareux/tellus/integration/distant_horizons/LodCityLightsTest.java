package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.osm.RoadSurfaceStyle;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LodCityLightsTest {
   private static final int GRASSLAND = 30;

   @Test
   void townStreetsLightUpWhileCountryRoadsStayDark() {
      int size = 32;
      byte[] roadClass = new byte[size * size];
      byte[] roadStyle = new byte[size * size];
      int[] cover = new int[size * size];
      Arrays.fill(cover, GRASSLAND);
      for (int x = 0; x < size; x++) {
         roadClass[10 * size + x] = 2;
         roadStyle[10 * size + x] = RoadSurfaceStyle.STYLE_PAVED_DARK;
      }
      // Houses only along the west end of the street.
      for (int x = 0; x < 4; x++) {
         cover[9 * size + x] = LodCityLights.ESA_BUILT_UP;
         cover[11 * size + x] = LodCityLights.ESA_BUILT_UP;
      }

      // 16 m cells reach ceil(60 / 16) = 4 cells for built-up land.
      boolean[] lit = LodCityLights.litStreets(roadClass, roadStyle, cover, size, 16.0);

      assertNotNull(lit);
      assertTrue(lit[10 * size]);
      assertTrue(lit[10 * size + 7]);
      assertFalse(lit[10 * size + 8]);
      assertFalse(lit[10 * size + size - 1]);
      assertFalse(lit[9 * size], "only road cells are streets");
   }

   @Test
   void noLitStreetMaskWithoutBuiltUpLand() {
      int size = 8;
      byte[] roadClass = new byte[size * size];
      Arrays.fill(roadClass, (byte)1);
      int[] cover = new int[size * size];
      Arrays.fill(cover, GRASSLAND);

      assertNull(LodCityLights.litStreets(roadClass, null, cover, size, 32.0));
      assertNull(LodCityLights.litStreets(null, null, cover, size, 32.0));
   }

   @Test
   void tracksAndUnpavedRoadsStayDark() {
      assertFalse(LodCityLights.isLitRoadType(3, RoadSurfaceStyle.STYLE_PAVED_DARK));
      assertFalse(LodCityLights.isLitRoadType(0, RoadSurfaceStyle.STYLE_PAVED_DARK));
      assertFalse(LodCityLights.isLitRoadType(2, RoadSurfaceStyle.STYLE_GRAVEL));
      assertFalse(LodCityLights.isLitRoadType(2, RoadSurfaceStyle.STYLE_DIRT));
      assertTrue(LodCityLights.isLitRoadType(1, (byte)0));
      assertTrue(LodCityLights.isLitRoadType(2, RoadSurfaceStyle.STYLE_COBBLESTONE));
   }

   @Test
   void lampPoolsFallOffOneLevelPerBlockLikeVanillaLight() {
      int size = 9;
      int[] baseY = new int[size * size];
      Arrays.fill(baseY, Integer.MIN_VALUE);
      byte[] poleCount = new byte[size * size];
      int lamp = 4 * size + 4;
      baseY[lamp] = 64;
      // Head six blocks up: the air over the ground beside the pole is five blocks below it.
      poleCount[lamp] = 4;

      byte[] pools = LodCityLights.lampPools(baseY, poleCount, size, 1);

      assertEquals(10, pools[lamp]);
      assertEquals(9, pools[lamp + 1]);
      assertEquals(8, pools[lamp + size + 1]);
      assertEquals(6, pools[lamp + 4]);
      assertEquals(2, pools[0]);
      byte[] coarsePools = LodCityLights.lampPools(baseY, poleCount, size, 2);
      assertEquals(8, coarsePools[lamp + 1]);
      assertEquals(2, coarsePools[lamp + 4]);
      assertNull(LodCityLights.lampPools(null, null, size, 1));
   }

   @Test
   void fixtureCellsKeepOnlyTheLightOfTheirLamps() {
      assertEquals(7, LodCityLights.street(100, 200, 1.0, 1.0, true, 7));
      assertEquals(0, LodCityLights.street(100, 200, 2.0, 1.0, true, 0));
      // Cells finer than a few metres show lamps one by one, so streets do not glow as a whole.
      assertEquals(3, LodCityLights.street(100, 200, 2.0, 1.0, false, 3));
   }

   @Test
   void streetsGlowWithAboutOneLampPerLampSpacing() {
      int lamps = 0;
      int cells = 0;
      for (int x = 0; x < 200; x++) {
         for (int z = 0; z < 200; z++) {
            int packed = LodCityLights.street(x * 8 + 4, z * 8 + 4, 8.0, 1.0, false, 0);
            cells++;
            assertTrue(LodCityLights.light(packed) >= 11 && LodCityLights.light(packed) <= 14);
            if (LodCityLights.source(packed) != LodCityLights.SOURCE_NONE) {
               lamps++;
               assertEquals(LodCityLights.SOURCE_LED, LodCityLights.source(packed));
               assertEquals(2, LodCityLights.sourceHeight(packed), "lamp heads stand a quarter of their cell over the street");
            }
         }
      }
      // 8 m cells of a 10 m street hold 8 * 8 / (35 * 10) of a lamp.
      assertEquals(64.0 / 350.0, lamps / (double)cells, 0.02);

      lamps = 0;
      for (int x = 0; x < 200; x++) {
         for (int z = 0; z < 200; z++) {
            int packed = LodCityLights.street(x * 128 + 64, z * 128 + 64, 128.0, 1.0, false, 0);
            if (LodCityLights.source(packed) != LodCityLights.SOURCE_NONE) {
               lamps++;
               assertEquals(32, LodCityLights.sourceHeight(packed), "distant lamps keep about a pixel of height");
            }
         }
      }
      assertEquals(0.4, lamps / (double)cells, 0.02);
   }

   @Test
   void builtUpLandGlowsOnlyInCellsWideEnoughToHoldSeveralStreets() {
      assertEquals(0, LodCityLights.ground(0, 0, LodCityLights.ESA_BUILT_UP, 8.0, 1.0, 0));
      assertEquals(5, LodCityLights.ground(0, 0, LodCityLights.ESA_BUILT_UP, 8.0, 1.0, 5));
      assertEquals(3, LodCityLights.ground(0, 0, GRASSLAND, 256.0, 1.0, 3));

      int cells = 0;
      int sparkles = 0;
      int white = 0;
      for (int x = 0; x < 300; x++) {
         for (int z = 0; z < 300; z++) {
            int packed = LodCityLights.ground(x * 256 + 128, z * 256 + 128, LodCityLights.ESA_BUILT_UP, 256.0, 1.0, 0);
            cells++;
            int light = LodCityLights.light(packed);
            assertTrue(light >= 12 && light <= 14, "glow " + light);
            if (LodCityLights.source(packed) != LodCityLights.SOURCE_NONE) {
               sparkles++;
               assertEquals(64, LodCityLights.sourceHeight(packed));
               if (LodCityLights.source(packed) == LodCityLights.SOURCE_LED) {
                  white++;
               }
            }
         }
      }
      double sparkleShare = sparkles / (double)cells;
      assertTrue(sparkleShare > 0.22 && sparkleShare < 0.34, "sparkle share " + sparkleShare);
      double whiteShare = white / (double)sparkles;
      assertTrue(whiteShare > 0.4 && whiteShare < 0.8, "white share " + whiteShare);
   }

   @Test
   void districtBrightnessChangesSmoothlyAcrossTheCity() {
      double min = 1.0;
      double max = -1.0;
      double previous = LodCityLights.district(0, 7_000, 1.0);
      for (int x = 1; x < 40_000; x++) {
         double value = LodCityLights.district(x, 7_000, 1.0);
         assertTrue(Math.abs(value - previous) < 0.01);
         assertTrue(value >= -1.0 && value <= 1.0);
         min = Math.min(min, value);
         max = Math.max(max, value);
         previous = value;
      }
      assertTrue(min < -0.3 && max > 0.3, "districts differ: " + min + " .. " + max);
   }

   @Test
   void cellWidthFollowsTheWorldScale() {
      assertEquals(64.0, LodCityLights.cellMeters(64, 1.0));
      assertEquals(60.0, LodCityLights.cellMeters(2, 30.0));
   }

   @Test
   void sourceHeightsFollowTheCellWidthInBlocks() {
      // Screen size depends on blocks, so a 1:30 cell of 32 blocks stands as high as a 1:1 one.
      assertEquals(8, firstStreetLampHeight(32, 30.0));
      assertEquals(8, firstStreetLampHeight(32, 1.0));
      assertEquals(1, firstStreetLampHeight(2, 30.0));
      assertEquals(1, firstStreetLampHeight(4, 1.0));
   }

   @Test
   void townsLightUpOnlyWithRoadsOrBuildings() {
      assertTrue(LodCityLights.lightsTowns(true, true, 1.0));
      assertTrue(LodCityLights.lightsTowns(true, false, 1.0));
      assertTrue(LodCityLights.lightsTowns(false, true, 15.0));
      assertFalse(LodCityLights.lightsTowns(false, false, 1.0));
      // Coarser than 1:15 Tellus builds neither, whatever the saved toggles say.
      assertFalse(LodCityLights.lightsTowns(true, true, 30.0));
   }

   @Test
   void lightsReachTheCityLightsDistanceAndNoFurther() {
      List<int[]> player = List.of(new int[]{0, 0});
      int limit = 256 * 16;
      assertSame(LodCityLights.Reach.ALL, LodCityLights.reach(256, player, 100, 100, 1_100, 1_100));
      LodCityLights.Reach beyond = LodCityLights.reach(256, player, limit + 10, 0, limit + 1_000, 1_000);
      assertFalse(beyond.any());
      assertFalse(beyond.covers(limit + 500, 500));
      // Unlimited, or no known player, lights every section.
      assertSame(LodCityLights.Reach.ALL, LodCityLights.reach(EarthGeneratorSettings.DH_CITY_LIGHTS_UNLIMITED, player, limit * 4, 0, limit * 5, 10));
      assertSame(LodCityLights.Reach.ALL, LodCityLights.reach(256, List.of(), limit * 4, 0, limit * 5, 10));

      LodCityLights.Reach edge = LodCityLights.reach(256, player, limit / 2, -2_000, limit + 2_000, 2_000);
      assertTrue(edge.any());
      assertTrue(edge.covers(limit / 2 + 1, 0));
      assertTrue(edge.covers((int)(limit * 0.87), 0));
      assertFalse(edge.covers(limit + 1, 0));
      // Lights thin out over the last eighth of the distance: keep (limit - d) / (limit / 8) of the columns.
      assertEquals(0.84, litShare(edge, limit * 0.895), 0.06);
      assertEquals(0.16, litShare(edge, limit * 0.98), 0.06);
   }

   @Test
   void theNearestPlayerDecides() {
      List<int[]> players = List.of(new int[]{0, 0}, new int[]{100_000, 0});
      assertSame(LodCityLights.Reach.ALL, LodCityLights.reach(256, players, 97_000, -10, 99_000, 10));
      assertFalse(LodCityLights.reach(256, players, 40_000, -10, 60_000, 10).any());
      LodCityLights.Reach between = LodCityLights.reach(256, players, 3_000, -10, 97_000, 10);
      assertTrue(between.covers(3_000, 0) && between.covers(97_000, 0));
      assertFalse(between.covers(50_000, 0));
   }

   /** Share of columns about {@code distance} blocks east of the player that keep their lights. */
   private static double litShare(LodCityLights.Reach reach, double distance) {
      int lit = 0;
      int total = 0;
      for (int x = (int)distance - 20; x <= (int)distance + 20; x++) {
         for (int z = -150; z <= 150; z++) {
            total++;
            lit += reach.covers(x, z) ? 1 : 0;
         }
      }
      return lit / (double)total;
   }

   private static int firstStreetLampHeight(int cellBlocks, double worldScale) {
      double cellMeters = LodCityLights.cellMeters(cellBlocks, worldScale);
      for (int x = 0; x < 10_000; x++) {
         int packed = LodCityLights.street(x * cellBlocks, 0, cellMeters, worldScale, false, 0);
         if (LodCityLights.source(packed) != LodCityLights.SOURCE_NONE) {
            return LodCityLights.sourceHeight(packed);
         }
      }
      throw new AssertionError("no lamp in 10 000 street cells");
   }
}
