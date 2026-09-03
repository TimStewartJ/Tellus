package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.yucareux.tellus.api.detail.ChunkDetailDomain;
import com.yucareux.tellus.api.detail.ChunkDetailLodPlan;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import com.yucareux.tellus.worldgen.vegetation.TellusVegetationPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TellusLodGeneratorColumnTest {
   private static final IDhApiBlockStateWrapper DEEPSLATE = new TestBlockStateWrapper("minecraft:deepslate");
   private static final IDhApiBiomeWrapper MOUNTAIN_BIOME = new TestBiomeWrapper("minecraft:jagged_peaks");

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
      AtomicReference<Query> query = new AtomicReference<>();
      ChunkDetailLodPlan exclusions = (domain, x, z, radius) -> {
         query.set(new Query(domain, x, z, radius));
         return true;
      };

      TellusLodGenerator.suppressesLodMatureTree(exclusions, 12, 18);
      assertEquals(
         new Query(
            ChunkDetailDomain.MATURE_TREE_EXCLUSION,
            12,
            18,
            TellusProceduralTreeGenerator.ROOT_EXCLUSION_RADIUS
         ),
         query.get()
      );

      TellusLodGenerator.suppressesLodUnderstory(
         exclusions, TellusVegetationPlanner.Stratum.SHRUB, 20, 24
      );
      assertEquals(
         new Query(ChunkDetailDomain.UNDERSTORY_EXCLUSION, 20, 24, 3),
         query.get()
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
