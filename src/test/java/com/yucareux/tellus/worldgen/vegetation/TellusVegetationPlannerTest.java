package com.yucareux.tellus.worldgen.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import org.junit.jupiter.api.Test;

class TellusVegetationPlannerTest {
   @Test
   void anchorsAreDeterministicAndStayInsideNegativeCells() {
      TellusVegetationPlanner.Anchor first = TellusVegetationPlanner.anchorForCell(
         TellusVegetationPlanner.Stratum.SHRUB, -3, 5, 912341L
      );
      TellusVegetationPlanner.Anchor second = TellusVegetationPlanner.anchorForCell(
         TellusVegetationPlanner.Stratum.SHRUB, -3, 5, 912341L
      );

      assertEquals(first, second);
      assertTrue(first.worldX() >= -12 && first.worldX() <= -9);
      assertTrue(first.worldZ() >= 20 && first.worldZ() <= 23);
   }

   @Test
   void communityResolutionMakesShrublandStructurallyDistinct() {
      assertEquals(
         VegetationCommunity.MEDITERRANEAN_SCRUB,
         VegetationCommunity.resolve(
            MountainSurfaceRules.ESA_SHRUBLAND,
            TellusProceduralTreeGenerator.Profile.MEDITERRANEAN
         )
      );
      assertEquals(
         VegetationCommunity.MALLEE,
         VegetationCommunity.resolve(
            MountainSurfaceRules.ESA_SHRUBLAND,
            TellusProceduralTreeGenerator.Profile.MALLEE
         )
      );
      assertEquals(
         VegetationCommunity.GRASSLAND,
         VegetationCommunity.resolve(
            MountainSurfaceRules.ESA_GRASSLAND,
            TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF
         )
      );
   }

   @Test
   void shrublandProducesManyMoreShrubsThanGrassland() {
      int shrubland = count(
         TellusVegetationPlanner.Stratum.SHRUB,
         environment(MountainSurfaceRules.ESA_SHRUBLAND, VegetationCommunity.MEDITERRANEAN_SCRUB, 1.0)
      );
      int grassland = count(
         TellusVegetationPlanner.Stratum.SHRUB,
         environment(MountainSurfaceRules.ESA_GRASSLAND, VegetationCommunity.GRASSLAND, 1.0)
      );

      assertTrue(shrubland > 450, "unexpected shrubland placements " + shrubland);
      assertTrue(shrubland > grassland * 5, "shrubland=" + shrubland + ", grassland=" + grassland);
   }

   @Test
   void compressedScalesReduceFineVegetationRepresentation() {
      int fullScale = count(
         TellusVegetationPlanner.Stratum.HERB,
         environment(MountainSurfaceRules.ESA_GRASSLAND, VegetationCommunity.GRASSLAND, 1.0)
      );
      int compressed = count(
         TellusVegetationPlanner.Stratum.HERB,
         environment(MountainSurfaceRules.ESA_GRASSLAND, VegetationCommunity.GRASSLAND, 30.0)
      );

      assertTrue(fullScale > compressed * 2, "fullScale=" + fullScale + ", compressed=" + compressed);
      assertTrue(compressed > 0);
   }

   @Test
   void moderateCompressionNeverIncreasesWoodyVegetation() {
      for (TellusVegetationPlanner.Stratum stratum : new TellusVegetationPlanner.Stratum[]{
         TellusVegetationPlanner.Stratum.SUBCANOPY,
         TellusVegetationPlanner.Stratum.SHRUB,
         TellusVegetationPlanner.Stratum.DEADWOOD
      }) {
         double previous = TellusVegetationPlanner.representationFactor(stratum, 2.0);
         assertEquals(1.0, previous);
         for (double scale : new double[]{2.01, 3.0, 4.0, 15.0, 30.0}) {
            double current = TellusVegetationPlanner.representationFactor(stratum, scale);
            assertTrue(current <= 1.0, stratum + " amplified at 1:" + scale);
            assertTrue(current <= previous, stratum + " increased at 1:" + scale);
            previous = current;
         }
      }
   }

   @Test
   void unsuitableSurfacesNeverProducePlacements() {
      TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
         TellusVegetationPlanner.Stratum.SHRUB, 0, 0, 7L
      );
      TellusVegetationPlanner.Environment blocked = new TellusVegetationPlanner.Environment(
         MountainSurfaceRules.ESA_SHRUBLAND,
         VegetationCommunity.MEDITERRANEAN_SCRUB,
         TellusProceduralTreeGenerator.Profile.MEDITERRANEAN,
         17L,
         1.0,
         0,
         3.0,
         false,
         0.2,
         0.5,
         8,
         1,
         false,
         false
      );

      assertNull(TellusVegetationPlanner.plan(TellusVegetationPlanner.Stratum.SHRUB, anchor, blocked));
   }

   @Test
   void lowMeasuredCanopySuppressesJuvenileTrees() {
      TellusVegetationPlanner.Environment lowCanopy = new TellusVegetationPlanner.Environment(
         MountainSurfaceRules.ESA_TREE_COVER,
         VegetationCommunity.TEMPERATE_FOREST,
         TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF,
         71L,
         1.0,
         0,
         1.4,
         true,
         0.2,
         0.3,
         9,
         0,
         false,
         true
      );
      for (int cell = 0; cell < 128; cell++) {
         TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
            TellusVegetationPlanner.Stratum.SUBCANOPY, cell, -cell, 71L
         );
         assertNull(
            TellusVegetationPlanner.plan(
               TellusVegetationPlanner.Stratum.SUBCANOPY, anchor, lowCanopy
            )
         );
      }
   }

   @Test
   void acceptedPlacementRetainsItsEcologicalIdentity() {
      TellusVegetationPlanner.Environment environment = environment(
         MountainSurfaceRules.ESA_SHRUBLAND, VegetationCommunity.MALLEE, 1.0
      );
      TellusVegetationPlanner.Placement placement = null;
      for (int cell = 0; cell < 256 && placement == null; cell++) {
         TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
            TellusVegetationPlanner.Stratum.SHRUB, cell, -cell, 83L
         );
         placement = TellusVegetationPlanner.plan(TellusVegetationPlanner.Stratum.SHRUB, anchor, environment);
      }

      assertNotNull(placement);
      assertEquals(VegetationCommunity.MALLEE, placement.community());
      assertTrue(placement.size() >= 2);
   }

   @Test
   void standFieldIsSharedAcrossStrataAndSpatiallyCoherent() {
      long spatialSeed = 998231L;
      TellusVegetationPlanner.StandState atOrigin = TellusVegetationPlanner.standState(
         0, 0, spatialSeed, 1.0
      );
      assertEquals(
         atOrigin,
         TellusVegetationPlanner.standState(0, 0, spatialSeed, 1.0)
      );

      int transitions = 0;
      TellusVegetationPlanner.StandState previous = atOrigin;
      for (int x = 1; x <= 256; x++) {
         TellusVegetationPlanner.StandState current = TellusVegetationPlanner.standState(
            x, 0, spatialSeed, 1.0
         );
         if (current != previous) {
            transitions++;
         }
         previous = current;
      }
      assertTrue(transitions < 20, "stand field changed too often: " + transitions);
   }

   private static int count(
      TellusVegetationPlanner.Stratum stratum, TellusVegetationPlanner.Environment environment
   ) {
      int count = 0;
      for (int z = -16; z < 16; z++) {
         for (int x = -16; x < 16; x++) {
            TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
               stratum, x, z, 7823479L
            );
            if (TellusVegetationPlanner.plan(stratum, anchor, environment) != null) {
               count++;
            }
         }
      }
      return count;
   }

   private static TellusVegetationPlanner.Environment environment(
      int coverClass, VegetationCommunity community, double worldScale
   ) {
      return new TellusVegetationPlanner.Environment(
         coverClass,
         community,
         TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF,
         7823479L,
         worldScale,
         0,
         24.0,
         false,
         0.35,
         0.4,
         8,
         1,
         false,
         true
      );
   }
}
