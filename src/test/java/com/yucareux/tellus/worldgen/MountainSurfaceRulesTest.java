package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountainSurfaceRulesTest {
   @Test
   void snowEdgesFollowTheBlendedBorderButNeverCoverWaterOrMissingData() {
      // Raw 10 m pixels left square rock holes in glaciers, so snow bends both ways like any other cover.
      for (int coverClass : new int[]{10, 20, 30, 40, 50, 60, 90, 100}) {
         assertEquals(
            MountainSurfaceRules.ESA_SNOW_ICE,
            MountainSurfaceRules.resolveSurfaceCoverClass(coverClass, MountainSurfaceRules.ESA_SNOW_ICE)
         );
         assertEquals(
            coverClass,
            MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_SNOW_ICE, coverClass)
         );
      }
      for (int coverClass : new int[]{0, 80, 95}) {
         assertEquals(
            coverClass,
            MountainSurfaceRules.resolveSurfaceCoverClass(coverClass, MountainSurfaceRules.ESA_SNOW_ICE)
         );
      }
   }

   @Test
   void forestAndTownEdgesFollowTheBlendedBorder() {
      // Trees and forest floor follow the blended border, so forest edges are not raster staircases.
      assertEquals(
         MountainSurfaceRules.ESA_BARE,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_TREE_COVER, MountainSurfaceRules.ESA_BARE)
      );
      assertEquals(
         MountainSurfaceRules.ESA_TREE_COVER,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_BUILT, MountainSurfaceRules.ESA_TREE_COVER)
      );
      assertEquals(
         MountainSurfaceRules.ESA_BUILT,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_BUILT)
      );
   }

   @Test
   void waterLikeTerrainKeepsTerrainClass() {
      assertEquals(
         MountainSurfaceRules.ESA_WATER,
         MountainSurfaceRules.resolveSurfaceCoverClass(MountainSurfaceRules.ESA_WATER, MountainSurfaceRules.ESA_TREE_COVER)
      );
   }

   @Test
   void highRuggedBareMountainsDefaultToStoneWithoutSnowInfluence() {
      int stoneBacked = 0;
      int sampledMountains = 0;

      for (int x = 0; x < 512; x += 64) {
         for (int z = 0; z < 512; z += 64) {
            MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
               MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 245, 4, -1, false, 0.0F, x, z
            );
            if (surface.isMountain()) {
               sampledMountains++;
               if (surface.palette() == MountainSurfaceRules.ApproximatePalette.STONE) {
                  stoneBacked++;
               }
            }
         }
      }

      assertEquals(64, sampledMountains);
      assertEquals(64, stoneBacked);
   }

   @Test
   void exposedMountainRockUsesStone() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 180, 3, 0, false, 0.0F, 16, 32
      );

      assertEquals(MountainSurfaceRules.ApproximatePalette.STONE, surface.palette());
   }

   @Test
   void desertAndBadlandsPalettesStayAuthoritativeOverBareGroundMountainRules() {
      assertTrue(MountainSurfaceRules.preservesBiomeSurfacePalette(true, false));
      assertTrue(MountainSurfaceRules.preservesBiomeSurfacePalette(false, true));
      assertFalse(MountainSurfaceRules.preservesBiomeSurfacePalette(false, false));
   }

   @Test
   void snowRequiresExistingSnowSource() {
      MountainSurfaceRules.ApproximateSurface dryHighBowl = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 260, 1, 2, false, 0.0F, 96, 128
      );
      MountainSurfaceRules.ApproximateSurface snowDataBowl = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_BARE, MountainSurfaceRules.ESA_BARE, 260, 1, 2, true, 0.0F, 96, 128
      );

      assertNotEquals(MountainSurfaceRules.ApproximatePalette.SNOW, dryHighBowl.palette());
      assertEquals(MountainSurfaceRules.ApproximatePalette.SNOW, snowDataBowl.palette());
   }

   @Test
   void steepSnowDataExposesRockOrSnowStreaks() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE, 250, 6, 0, false, 0.0F, 192, 224
      );

      assertNotEquals(MountainSurfaceRules.ApproximatePalette.SNOW, surface.palette());
      assertTrue(surface.isMountain() || surface.palette() == MountainSurfaceRules.ApproximatePalette.SNOW_STREAK);
   }

   @Test
   void ruggedNoDataHighlandFallsBackToMountainPalette() {
      MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_NO_DATA, MountainSurfaceRules.ESA_NO_DATA, 135, 3, 0, false, 0.0F, 32, 48
      );

      assertTrue(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_NO_DATA, 135, 3, 0));
      assertFalse(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_NO_DATA, 105, 2, 0));
      assertTrue(surface.isMountain());
   }

   @Test
   void vegetatedLandCoverDoesNotBecomeMountainStoneFromElevationOrRuggedness() {
      int[] vegetatedCoverClasses = new int[]{
         MountainSurfaceRules.ESA_TREE_COVER,
         MountainSurfaceRules.ESA_SHRUBLAND,
         MountainSurfaceRules.ESA_GRASSLAND,
         MountainSurfaceRules.ESA_CROPLAND,
         MountainSurfaceRules.ESA_MOSS_LICHEN
      };

      for (int coverClass : vegetatedCoverClasses) {
         assertFalse(
            MountainSurfaceRules.qualifiesForMountainPalette(coverClass, 260, 6, -2),
            "Vegetated land-cover class " + coverClass + " should keep its land-cover palette"
         );

         MountainSurfaceRules.ApproximateSurface surface = MountainSurfaceRules.classifyApproximateSurface(
            coverClass, coverClass, 260, 6, -2, false, 0.0F, 64, 96
         );
         assertEquals(
            MountainSurfaceRules.ApproximatePalette.NONE,
            surface.palette(),
            "Vegetated land-cover class " + coverClass + " should not be reclassified as stone"
         );
      }
   }

   @Test
   void exposedRockIsSortedIntoLandforms() {
      int bare = MountainSurfaceRules.ESA_BARE;
      assertEquals(MountainSurfaceRules.MountainForm.TALUS_SLOPE, MountainSurfaceRules.landform(bare, 150, 2, 1, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.TALUS_SLOPE, MountainSurfaceRules.landform(bare, 150, 3, 2, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.DRAINAGE_CHUTE, MountainSurfaceRules.landform(bare, 150, 4, 1, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.CLIFF_FACE, MountainSurfaceRules.landform(bare, 150, 6, 1, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.STEEP_ROCKY_SLOPE, MountainSurfaceRules.landform(bare, 150, 5, 0, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.GLACIER_BASIN, MountainSurfaceRules.landform(bare, 210, 1, 1, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.RIDGE, MountainSurfaceRules.landform(bare, 210, 2, -2, 0, 0));
      assertEquals(MountainSurfaceRules.MountainForm.VALLEY_WALL, MountainSurfaceRules.landform(bare, 150, 3, 0, 0, 0));
      // Scree is a highland feature; lowland bare slopes stay valley walls.
      assertEquals(MountainSurfaceRules.MountainForm.VALLEY_WALL, MountainSurfaceRules.landform(bare, 60, 3, 1, 0, 0));
   }

   @Test
   void mappedSnowInAGentleHollowIsGlacierIce() {
      MountainSurfaceRules.ApproximateSurface hollow = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE, 260, 1, 2, false, 0.0F, 96, 128
      );
      MountainSurfaceRules.ApproximateSurface plateau = MountainSurfaceRules.classifyApproximateSurface(
         MountainSurfaceRules.ESA_SNOW_ICE, MountainSurfaceRules.ESA_SNOW_ICE, 260, 1, 0, false, 0.0F, 96, 128
      );

      assertEquals(MountainSurfaceRules.ApproximatePalette.SNOW, hollow.palette());
      assertEquals(MountainSurfaceRules.MountainForm.GLACIER_BASIN, hollow.form());
      assertEquals(MountainSurfaceRules.MountainForm.SNOWFIELD, plateau.form());
      assertEquals(MountainSurfaceRules.MountainForm.GLACIER_BASIN, MountainSurfaceRules.snowForm(2, 1));
      assertEquals(MountainSurfaceRules.MountainForm.SNOWFIELD, MountainSurfaceRules.snowForm(3, 1));
   }

   @Test
   void landformsUseDistinctMaterialMixes() {
      int[] talus = materialCounts(MountainSurfaceRules.MountainForm.TALUS_SLOPE);
      int[] cliff = materialCounts(MountainSurfaceRules.MountainForm.CLIFF_FACE);
      int[] chute = materialCounts(MountainSurfaceRules.MountainForm.DRAINAGE_CHUTE);
      int samples = 128 * 128;

      assertEquals(0, talus[MountainSurfaceRules.MATERIAL_STONE], "Scree is loose debris");
      assertEquals(0.55, (double)talus[MountainSurfaceRules.MATERIAL_GRAVEL] / samples, 0.08);
      assertTrue(talus[MountainSurfaceRules.MATERIAL_DIORITE] > 0, "Scree carries lighter fragments");
      assertEquals(0, cliff[MountainSurfaceRules.MATERIAL_GRAVEL]);
      assertTrue(cliff[MountainSurfaceRules.MATERIAL_STONE] > samples * 0.7);
      assertTrue(chute[MountainSurfaceRules.MATERIAL_GRAVEL] > samples * 0.6);
      assertTrue(MountainSurfaceRules.isLooseDebrisForm(MountainSurfaceRules.MountainForm.TALUS_SLOPE));
      assertFalse(MountainSurfaceRules.isLooseDebrisForm(MountainSurfaceRules.MountainForm.CLIFF_FACE));
   }

   @Test
   void glacierHollowsAreMostlyIce() {
      int ice = 0;
      int samples = 0;
      for (int z = 0; z < 4096; z += 32) {
         for (int x = 0; x < 4096; x += 32) {
            int material = MountainSurfaceRules.glacierSurfaceMaterial(x, z);
            if (material == MountainSurfaceRules.MATERIAL_PACKED_ICE || material == MountainSurfaceRules.MATERIAL_BLUE_ICE) {
               ice++;
            } else {
               assertEquals(MountainSurfaceRules.MATERIAL_SNOW, material);
            }
            samples++;
         }
      }

      assertEquals(0.7, (double)ice / samples, 0.08);
   }

   @Test
   void steepTurfShowsMoreRockAsItSteepens() {
      assertEquals(0, MountainSurfaceRules.exposedRockPercent(2));
      int previous = 0;
      for (int slope = 3; slope <= 8; slope++) {
         int percent = MountainSurfaceRules.exposedRockPercent(slope);
         assertTrue(percent >= previous && percent < 100, "slope " + slope);
         previous = percent;
      }
      assertEquals(0, MountainSurfaceRules.alpineMeadowScreePercent(1));
      assertTrue(MountainSurfaceRules.alpineMeadowScreePercent(2) < 50);
   }

   private static int[] materialCounts(MountainSurfaceRules.MountainForm form) {
      int[] counts = new int[8];
      for (int z = 0; z < 128; z++) {
         for (int x = 0; x < 128; x++) {
            counts[MountainSurfaceRules.formSurfaceMaterial(form, x * 29, z * 31)]++;
         }
      }
      return counts;
   }

   @Test
   void mountainPaletteStillHandlesBareSnowAndNoDataTerrain() {
      assertTrue(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_BARE, 260, 1, 0));
      assertTrue(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_SNOW_ICE, 0, 0, 0));
      assertTrue(MountainSurfaceRules.qualifiesForMountainPalette(MountainSurfaceRules.ESA_NO_DATA, 260, 1, 0));

      int[] otherCoverClasses = new int[]{
         MountainSurfaceRules.ESA_BUILT,
         MountainSurfaceRules.ESA_WATER,
         MountainSurfaceRules.ESA_WETLAND,
         MountainSurfaceRules.ESA_MANGROVES
      };
      for (int coverClass : otherCoverClasses) {
         assertFalse(
            MountainSurfaceRules.qualifiesForMountainPalette(coverClass, 260, 6, -2),
            "Land-cover class " + coverClass + " should not enter the mountain palette"
         );
      }
   }

}
