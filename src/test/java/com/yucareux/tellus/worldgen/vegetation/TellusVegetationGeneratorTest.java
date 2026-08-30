package com.yucareux.tellus.worldgen.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TellusVegetationGeneratorTest {
   @Test
   void chunkPlanningIsDeterministicBoundedAndOrdered() {
      TellusVegetationGenerator.EnvironmentSampler sampler = (stratum, worldX, worldZ, seed) ->
         new TellusVegetationPlanner.Environment(
            MountainSurfaceRules.ESA_SHRUBLAND,
            VegetationCommunity.MEDITERRANEAN_SCRUB,
            TellusProceduralTreeGenerator.Profile.MEDITERRANEAN,
            781239L,
            1.0,
            0.0,
            0.12,
            0.45,
            6,
            1,
            false,
            true
         );

      List<TellusVegetationPlanner.Placement> first = TellusVegetationGenerator.planChunk(
         -16, 32, 781239L, sampler
      );
      List<TellusVegetationPlanner.Placement> second = TellusVegetationGenerator.planChunk(
         -16, 32, 781239L, sampler
      );

      assertEquals(first, second);
      assertFalse(first.isEmpty());
      assertTrue(first.size() <= 106);
      int previousOrder = -1;
      for (TellusVegetationPlanner.Placement placement : first) {
         assertTrue(placement.worldX() >= -16 && placement.worldX() <= -1);
         assertTrue(placement.worldZ() >= 32 && placement.worldZ() <= 47);
         int order = placementOrder(placement.stratum());
         assertTrue(order >= previousOrder);
         previousOrder = order;
      }
   }

   @Test
   void blockedEnvironmentProducesAnEmptyChunkPlan() {
      List<TellusVegetationPlanner.Placement> placements = TellusVegetationGenerator.planChunk(
         0,
         0,
         17L,
         (stratum, worldX, worldZ, seed) -> new TellusVegetationPlanner.Environment(
            MountainSurfaceRules.ESA_BUILT,
            VegetationCommunity.NONE,
            TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF,
            17L,
            1.0,
            0.0,
            0.0,
            0.0,
            9,
            0,
            false,
            false
         )
      );

      assertTrue(placements.isEmpty());
   }

   @Test
   void explicitSoilPredicateCoversModernForestSurfaces() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      assertTrue(
         TellusVegetationGenerator.supportsVegetation(Blocks.GRASS_BLOCK.defaultBlockState())
      );
      assertTrue(
         TellusVegetationGenerator.supportsVegetation(Blocks.PODZOL.defaultBlockState())
      );
      assertTrue(
         TellusVegetationGenerator.supportsVegetation(Blocks.ROOTED_DIRT.defaultBlockState())
      );
      assertFalse(
         TellusVegetationGenerator.supportsVegetation(Blocks.STONE.defaultBlockState())
      );
   }

   @Test
   void adjacentChunksOwnDisjointCandidateAnchors() {
      TellusVegetationGenerator.EnvironmentSampler sampler = (stratum, worldX, worldZ, seed) ->
         new TellusVegetationPlanner.Environment(
            MountainSurfaceRules.ESA_SHRUBLAND,
            VegetationCommunity.TEMPERATE_SCRUB,
            TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF,
            45123L,
            1.0,
            0.0,
            0.1,
            0.3,
            9,
            0,
            false,
            true
         );
      List<TellusVegetationPlanner.Placement> west = TellusVegetationGenerator.planChunk(
         0, 0, 45123L, sampler
      );
      List<TellusVegetationPlanner.Placement> east = TellusVegetationGenerator.planChunk(
         16, 0, 45123L, sampler
      );
      Set<String> anchors = new HashSet<>();
      for (TellusVegetationPlanner.Placement placement : west) {
         assertTrue(anchors.add(anchorKey(placement)));
      }
      for (TellusVegetationPlanner.Placement placement : east) {
         assertTrue(anchors.add(anchorKey(placement)), "duplicate anchor " + placement);
      }
   }

   private static String anchorKey(TellusVegetationPlanner.Placement placement) {
      return placement.stratum() + ":" + placement.worldX() + ":" + placement.worldZ();
   }

   private static int placementOrder(TellusVegetationPlanner.Stratum stratum) {
      return switch (stratum) {
         case DEADWOOD -> 0;
         case SUBCANOPY -> 1;
         case SHRUB -> 2;
         case GROUND -> 3;
         case HERB -> 4;
      };
   }
}
