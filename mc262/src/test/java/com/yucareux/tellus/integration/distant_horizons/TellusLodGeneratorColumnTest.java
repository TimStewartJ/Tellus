package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.yucareux.tellus.api.detail.ChunkDetailDomain;
import com.yucareux.tellus.api.detail.ChunkDetailLodPlan;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import com.yucareux.tellus.worldgen.vegetation.TellusVegetationPlanner;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TellusLodGeneratorColumnTest {
   private static final IDhApiBlockStateWrapper DEEPSLATE = new TestBlockStateWrapper("minecraft:deepslate");
   private static final IDhApiBiomeWrapper MOUNTAIN_BIOME = new TestBiomeWrapper("minecraft:jagged_peaks");
   private static final TellusProceduralTreeGenerator.TreePlan TALL_TREE = new TellusProceduralTreeGenerator.TreePlan(
      TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF, 30, 2, 8, 14, 17, 0, 0, false, true
   );

   @Test
   void sealedBaseStartsAtColumnMinimumAndRetainsResolvedDeepslate() {
      List<DhApiTerrainDataPoint> dataPoints = new ArrayList<>();

      int layerTop = TellusLodGenerator.appendSealedLodBaseColumn(
         dataPoints, 0, 3200, DEEPSLATE, MOUNTAIN_BIOME
      );

      assertEquals(3200, layerTop);
      assertEquals(1, dataPoints.size());
      DhApiTerrainDataPoint base = dataPoints.getFirst();
      assertEquals(0, base.bottomYBlockPos);
      assertEquals(3200, base.topYBlockPos);
      assertSame(DEEPSLATE, base.blockStateWrapper);
      assertSame(MOUNTAIN_BIOME, base.biomeWrapper);
   }

   @Test
   void sealedBaseContinuesFromThePreviousLayerWithoutAGap() {
      List<DhApiTerrainDataPoint> dataPoints = new ArrayList<>();

      int layerTop = TellusLodGenerator.appendSealedLodBaseColumn(
         dataPoints, 64, 220, DEEPSLATE, MOUNTAIN_BIOME
      );

      assertEquals(220, layerTop);
      assertEquals(1, dataPoints.size());
      DhApiTerrainDataPoint base = dataPoints.getFirst();
      assertEquals(64, base.bottomYBlockPos);
      assertEquals(220, base.topYBlockPos);
      assertSame(DEEPSLATE, base.blockStateWrapper);
   }

   @Test
   void adjacentColumnsRemainClosedWhenReliefExceedsTheOldShellDepth() {
      List<DhApiTerrainDataPoint> lowerColumn = new ArrayList<>();
      List<DhApiTerrainDataPoint> higherColumn = new ArrayList<>();

      TellusLodGenerator.appendSealedLodBaseColumn(lowerColumn, 0, 100, DEEPSLATE, MOUNTAIN_BIOME);
      TellusLodGenerator.appendSealedLodBaseColumn(higherColumn, 0, 220, DEEPSLATE, MOUNTAIN_BIOME);

      DhApiTerrainDataPoint lowerBase = lowerColumn.getFirst();
      DhApiTerrainDataPoint higherBase = higherColumn.getFirst();
      assertEquals(0, lowerBase.bottomYBlockPos);
      assertEquals(0, higherBase.bottomYBlockPos);
      assertEquals(220, higherBase.topYBlockPos);
      assertSame(DEEPSLATE, higherBase.blockStateWrapper);
   }

   @Test
   void sealedBaseDoesNotAppendAnEmptyRange() {
      List<DhApiTerrainDataPoint> dataPoints = new ArrayList<>();

      int layerTop = TellusLodGenerator.appendSealedLodBaseColumn(
         dataPoints, 220, 220, DEEPSLATE, MOUNTAIN_BIOME
      );

      assertEquals(220, layerTop);
      assertEquals(0, dataPoints.size());
   }

   @Test
   void canopyExclusionsUseTheSameNativeTreeAndUnderstoryRadii() {
      List<Query> queries = new ArrayList<>();
      ChunkDetailLodPlan exclusions = (domain, x, z, radius) -> {
         queries.add(new Query(domain, x, z, radius));
         return domain == ChunkDetailDomain.MATURE_TREE_EXCLUSION;
      };

      TellusLodGenerator.suppressesLodMatureTree(exclusions, 12, 18);
      assertEquals(
         List.of(
            new Query(
               ChunkDetailDomain.TREE_ANCHOR_EXCLUSION, 12, 18, 0
            ),
            new Query(
            ChunkDetailDomain.MATURE_TREE_EXCLUSION,
            12,
            18,
            TellusProceduralTreeGenerator.ROOT_EXCLUSION_RADIUS
            )
         ),
         queries
      );

      queries.clear();
      TellusLodGenerator.suppressesLodUnderstory(
         exclusions, TellusVegetationPlanner.Stratum.SHRUB, 20, 24
      );
      assertEquals(
         List.of(
            new Query(
               ChunkDetailDomain.UNDERSTORY_EXCLUSION, 20, 24, 3
            )
         ),
         queries
      );
   }

   @Test
   void pendingLodInputsBecomeSilentRetryableRejections() {
      var pending = new com.yucareux.tellus.api.detail.ChunkDetailLodPendingException(
         20, "terrain elevation unavailable"
      );

      RejectedExecutionException retry =
         TellusLodGenerator.retryablePendingRejection(pending);

      assertSame(pending, retry.getCause());
   }

   @Test
   void companionRetryHintsCannotOvershootTheFailOpenDeadline() {
      var pending = new com.yucareux.tellus.api.detail.ChunkDetailLodPendingException(
         1_200, "route data unavailable"
      );

      var clamped = TellusLodGenerator.clampPendingRetryToRemaining(
         pending, TimeUnit.SECONDS.toNanos(30)
      );

      assertEquals(600, clamped.retryAfterTicks());
      assertSame(
         pending,
         TellusLodGenerator.clampPendingRetryToRemaining(
            pending, TimeUnit.SECONDS.toNanos(90)
         )
      );
   }

   @Test
   void lodTreesUseTheSharedConnectedBasalFootprintDecision() {
      TellusProceduralTreeGenerator.TreePlan tree = treePlan();

      assertTrue(
         TellusLodGenerator.acceptsLodTreeFootprint(
            0,
            100,
            0,
            tree,
            (x, z) -> x == 1 ? 101 : 100
         )
      );
      assertFalse(
         TellusLodGenerator.acceptsLodTreeFootprint(
            0,
            100,
            0,
            tree,
            (x, z) -> x < 0 || x == 0 && (z == 0 || z == 1)
               ? 100
               : Integer.MIN_VALUE
         )
      );
   }

   @Test
   void tallCrownCoversItsWholeRadiusInsteadOfStoppingAtNeighbouringAnchors() {
      long worldSeed = 42L;
      TellusProceduralTreeGenerator.TreeAnchor tallAnchor = TellusProceduralTreeGenerator.anchorForCell(0, 0, worldSeed);
      TellusProceduralTreeGenerator.TreePlan bush = new TellusProceduralTreeGenerator.TreePlan(
         TellusProceduralTreeGenerator.Profile.TEMPERATE_BROADLEAF, 3, 1, 2, 1, 3, 0, 0, true, true
      );
      Function<TellusProceduralTreeGenerator.TreeAnchor, TellusProceduralTreeGenerator.TreePlan> plans =
         anchor -> anchor.equals(tallAnchor) ? TALL_TREE : bush;

      int columnsNearerAnotherAnchor = 0;
      for (int dz = -8; dz <= 8; dz++) {
         for (int dx = -8; dx <= 8; dx++) {
            if (dx * dx + dz * dz > 64) {
               continue;
            }
            int worldX = tallAnchor.worldX() + dx;
            int worldZ = tallAnchor.worldZ() + dz;
            assertEquals(tallAnchor, TellusLodGenerator.anchoredCrown(worldX, worldZ, 1, worldSeed, plans).anchor());
            // Columns as wide as a placement cell keep their nearest anchor, which shows where the old rule cut.
            if (!tallAnchor.equals(TellusLodGenerator.anchoredCrown(worldX, worldZ, 16, worldSeed, plans).anchor())) {
               columnsNearerAnotherAnchor++;
            }
         }
      }
      assertTrue(columnsNearerAnotherAnchor > 0);
   }

   @Test
   void anchorsThatGrowNoTreeLendNoCrown() {
      long worldSeed = 42L;
      TellusProceduralTreeGenerator.TreeAnchor treeAnchor = TellusProceduralTreeGenerator.anchorForCell(0, 0, worldSeed);
      // A forest edge: one anchor stands on tree cover, its neighbours on open ground.
      Function<TellusProceduralTreeGenerator.TreeAnchor, TellusProceduralTreeGenerator.TreePlan> plans =
         anchor -> anchor.equals(treeAnchor) ? TALL_TREE : null;

      assertEquals(
         treeAnchor, TellusLodGenerator.anchoredCrown(treeAnchor.worldX() + 8, treeAnchor.worldZ(), 1, worldSeed, plans).anchor()
      );
      assertNull(TellusLodGenerator.anchoredCrown(treeAnchor.worldX() + 9, treeAnchor.worldZ(), 1, worldSeed, plans));
   }

   private record TestBlockStateWrapper(String serialString) implements IDhApiBlockStateWrapper {
      @Override
      public Object getWrappedMcObject() {
         return this.serialString;
      }

      @Override
      public boolean isAir() {
         return false;
      }

      @Override
      public boolean isSolid() {
         return true;
      }

      @Override
      public boolean isLiquid() {
         return false;
      }

      @Override
      public int getOpacity() {
         return 15;
      }

      @Override
      public String getSerialString() {
         return this.serialString;
      }

      @Override
      public byte getMaterialId() {
         return 0;
      }
   }

   private record TestBiomeWrapper(String name) implements IDhApiBiomeWrapper {
      @Override
      public String getSerialString() {
         return this.name;
      }

      @Override
      public Object getWrappedMcObject() {
         return this.name;
      }

      @Override
      public String getName() {
         return this.name;
      }
   }

   private record Query(
      ChunkDetailDomain domain, int worldX, int worldZ, int radius
   ) {
   }

   private static TellusProceduralTreeGenerator.TreePlan treePlan() {
      return new TellusProceduralTreeGenerator.TreePlan(
         TellusProceduralTreeGenerator.Profile.TALL_CONIFER,
         52,
         4,
         8,
         26,
         27,
         0,
         0,
         false,
         true
      );
   }

}
