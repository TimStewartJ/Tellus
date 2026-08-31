package com.yucareux.tellus.worldgen.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.List;
import org.junit.jupiter.api.Test;

class TellusVegetationPlannerTest {
   @Test
   void anchorsAreDeterministicKeepTheirSpacingAndStayInsideTheArea() {
      List<TellusVegetationPlanner.Anchor> first = TellusVegetationPlanner.anchorsIn(
         TellusVegetationPlanner.Stratum.SHRUB, -40, 20, -9, 51, 912341L
      );
      List<TellusVegetationPlanner.Anchor> second = TellusVegetationPlanner.anchorsIn(
         TellusVegetationPlanner.Stratum.SHRUB, -40, 20, -9, 51, 912341L
      );

      assertEquals(first, second);
      // 32x32 blocks at one anchor per 13 blocks on average.
      assertTrue(first.size() > 50 && first.size() < 110, "unexpected anchor count " + first.size());
      for (TellusVegetationPlanner.Anchor anchor : first) {
         assertTrue(anchor.worldX() >= -40 && anchor.worldX() <= -9);
         assertTrue(anchor.worldZ() >= 20 && anchor.worldZ() <= 51);
         assertEquals(anchor, TellusVegetationPlanner.anchorAt(
            TellusVegetationPlanner.Stratum.SHRUB, anchor.worldX(), anchor.worldZ(), 912341L
         ));
      }
      assertMinimumSpacing(first, TellusVegetationPlanner.Stratum.SHRUB.spacingRadiusSquared());
   }

   @Test
   void anchorsHaveNoLatticePeriod() {
      TellusVegetationPlanner.Stratum stratum = TellusVegetationPlanner.Stratum.SUBCANOPY;
      int cell = stratum.cellSize();
      int cells = 40;
      List<TellusVegetationPlanner.Anchor> anchors = TellusVegetationPlanner.anchorsIn(
         stratum, 0, 0, cells * cell - 1, cells * cell - 1, 4471L
      );
      assertMinimumSpacing(anchors, stratum.spacingRadiusSquared());

      // The legacy lattice put exactly one anchor in every aligned cell; a gridless field does not.
      int[] perCell = new int[cells * cells];
      for (TellusVegetationPlanner.Anchor anchor : anchors) {
         perCell[anchor.worldX() / cell + anchor.worldZ() / cell * cells]++;
      }
      int empty = 0;
      int crowded = 0;
      for (int count : perCell) {
         if (count == 0) {
            empty++;
         } else if (count > 1) {
            crowded++;
         }
      }
      assertTrue(empty > perCell.length / 20, "aligned cells without an anchor: " + empty);
      assertTrue(crowded > perCell.length / 20, "aligned cells with several anchors: " + crowded);
      double perBlock = anchors.size() / (double)(cells * cell * cells * cell);
      assertEquals(1.0 / stratum.neighborhoodSize(), perBlock, 0.08 / stratum.neighborhoodSize());
   }

   @Test
   void anchorDensityIsCalibratedToTheLegacyCellDensity() {
      for (TellusVegetationPlanner.Stratum stratum : TellusVegetationPlanner.Stratum.values()) {
         int area = 384;
         int count = TellusVegetationPlanner.anchorsIn(stratum, -192, -192, 191, 191, 20260831L).size();
         double perBlock = count / (double)(area * area);
         assertEquals(
            1.0 / stratum.neighborhoodSize(), perBlock, 0.1 / stratum.neighborhoodSize(), stratum.name()
         );
         // Expected plants per block at a tuned density d must stay d / cellSize² (the lattice meaning).
         for (double density : new double[]{0.15, 0.4, 0.7}) {
            double plantsPerBlock = stratum.anchorProbability(density) / stratum.neighborhoodSize();
            assertEquals(
               density / (stratum.cellSize() * stratum.cellSize()),
               plantsPerBlock,
               1.0e-12,
               stratum + " at density " + density
            );
         }
      }
   }

   @Test
   void opennessFormsConnectedCorridorsCoveringAModestFraction() {
      long seed = 55511L;
      int size = 384;
      boolean[] corridor = new boolean[size * size];
      int open = 0;
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            double value = TellusVegetationPlanner.openness(x - 100, z + 40, seed);
            assertTrue(value >= 0.0 && value <= 1.0);
            if (value > 0.0) {
               corridor[x + z * size] = true;
               open++;
            }
         }
      }
      double fraction = open / (double)(size * size);
      assertTrue(fraction > 0.07 && fraction < 0.22, "corridor fraction " + fraction);

      // Corridors are bands around level curves: almost every corridor block continues into neighbours.
      int continuing = 0;
      int interior = 0;
      for (int z = 1; z < size - 1; z++) {
         for (int x = 1; x < size - 1; x++) {
            if (!corridor[x + z * size]) {
               continue;
            }
            interior++;
            int neighbours = 0;
            for (int dz = -1; dz <= 1; dz++) {
               for (int dx = -1; dx <= 1; dx++) {
                  if ((dx != 0 || dz != 0) && corridor[x + dx + (z + dz) * size]) {
                     neighbours++;
                  }
               }
            }
            if (neighbours >= 2) {
               continuing++;
            }
         }
      }
      assertTrue(continuing > interior * 0.97, "isolated corridor blocks: " + (interior - continuing));
      assertEquals(
         TellusVegetationPlanner.openness(12, -7, seed), TellusVegetationPlanner.openness(12, -7, seed)
      );
   }

   @Test
   void corridorsThinWoodyStrataButLeaveHerbsMostlyAlone() {
      TellusVegetationPlanner.Environment forest = environment(
         MountainSurfaceRules.ESA_TREE_COVER, VegetationCommunity.TROPICAL_MOIST_FOREST, 1.0
      );
      double[] shrubRates = placementRateInsideAndOutsideCorridors(
         TellusVegetationPlanner.Stratum.SHRUB, forest
      );
      double[] herbRates = placementRateInsideAndOutsideCorridors(
         TellusVegetationPlanner.Stratum.HERB, forest
      );

      assertTrue(shrubRates[1] > 0.0);
      assertTrue(shrubRates[0] < shrubRates[1] * 0.35, "shrubs in corridors " + shrubRates[0] + " vs " + shrubRates[1]);
      assertTrue(herbRates[0] > herbRates[1] * 0.55, "herbs in corridors " + herbRates[0] + " vs " + herbRates[1]);
   }

   /** Placement rate per anchor for anchors whose corridor openness is above 0.5 and for anchors outside corridors. */
   private static double[] placementRateInsideAndOutsideCorridors(
      TellusVegetationPlanner.Stratum stratum, TellusVegetationPlanner.Environment environment
   ) {
      int[] inside = new int[2];
      int[] outside = new int[2];
      for (TellusVegetationPlanner.Anchor anchor : TellusVegetationPlanner.anchorsIn(
         stratum, -256, -256, 255, 255, 7823479L
      )) {
         double openness = TellusVegetationPlanner.openness(
            anchor.worldX(), anchor.worldZ(), environment.spatialSeed()
         );
         int[] bucket = openness > 0.5 ? inside : openness == 0.0 ? outside : null;
         if (bucket == null) {
            continue;
         }
         bucket[1]++;
         if (TellusVegetationPlanner.plan(stratum, anchor, environment) != null) {
            bucket[0]++;
         }
      }
      assertTrue(inside[1] > 100 && outside[1] > 1000, "inside=" + inside[1] + " outside=" + outside[1]);
      return new double[]{inside[0] / (double)inside[1], outside[0] / (double)outside[1]};
   }

   private static void assertMinimumSpacing(List<TellusVegetationPlanner.Anchor> anchors, int radiusSquared) {
      for (int i = 0; i < anchors.size(); i++) {
         for (int j = i + 1; j < anchors.size(); j++) {
            int dx = anchors.get(i).worldX() - anchors.get(j).worldX();
            int dz = anchors.get(i).worldZ() - anchors.get(j).worldZ();
            assertTrue(
               dx * dx + dz * dz > radiusSquared,
               "anchors closer than the spacing radius: " + anchors.get(i) + " and " + anchors.get(j)
            );
         }
      }
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
      TellusVegetationPlanner.Anchor anchor = TellusVegetationPlanner.anchorsIn(
         TellusVegetationPlanner.Stratum.SHRUB, 0, 0, 15, 15, 7L
      ).get(0);
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
      for (TellusVegetationPlanner.Anchor anchor : TellusVegetationPlanner.anchorsIn(
         TellusVegetationPlanner.Stratum.SUBCANOPY, -64, -64, 63, 63, 71L
      )) {
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
      for (TellusVegetationPlanner.Anchor anchor : TellusVegetationPlanner.anchorsIn(
         TellusVegetationPlanner.Stratum.SHRUB, -64, -64, 63, 63, 83L
      )) {
         placement = TellusVegetationPlanner.plan(TellusVegetationPlanner.Stratum.SHRUB, anchor, environment);
         if (placement != null) {
            break;
         }
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
      // 32 x 32 legacy cells' worth of area for the stratum, so counts keep their historical scale.
      int half = 16 * stratum.cellSize();
      int count = 0;
      for (TellusVegetationPlanner.Anchor anchor : TellusVegetationPlanner.anchorsIn(
         stratum, -half, -half, half - 1, half - 1, 7823479L
      )) {
         if (TellusVegetationPlanner.plan(stratum, anchor, environment) != null) {
            count++;
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
