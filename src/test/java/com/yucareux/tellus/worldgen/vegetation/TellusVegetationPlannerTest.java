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
   void unsuitableSurfacesNeverProducePlacements() {
      TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorForCell(
         TellusVegetationPlanner.Stratum.SHRUB, 0, 0, 7L
      );
      TellusVegetationPlanner.Environment blocked = new TellusVegetationPlanner.Environment(
         MountainSurfaceRules.ESA_SHRUBLAND,
         VegetationCommunity.MEDITERRANEAN_SCRUB,
         TellusProceduralTreeGenerator.Profile.MEDITERRANEAN,
         1.0,
         3.0,
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
         worldScale,
         24.0,
         0.35,
         0.4,
         8,
         1,
         false,
         true
      );
   }
}
