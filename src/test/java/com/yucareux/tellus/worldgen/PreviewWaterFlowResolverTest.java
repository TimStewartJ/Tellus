package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PreviewWaterFlowResolverTest {
   private static final InlandWaterFlowAnalyzer.Parameters FLOW_PARAMETERS = new InlandWaterFlowAnalyzer.Parameters(
      4,
      12,
      24,
      10,
      12,
      6,
      48,
      4
   );

   @Test
   void flowingPreviewWaterHasARealBedAndNeverRisesDownstream() {
      int size = 9;
      PreviewGrid grid = flowingRow(size, new int[]{18, 18, 17, 16, 14, 12, 10});

      PreviewWaterFlowResolver.Result result = resolve(grid, FLOW_PARAMETERS);

      for (int x = 1; x <= 7; x++) {
         int cell = index(size, x, 4);
         assertTrue(result.inlandWater()[cell]);
         assertTrue(result.terrainSurface()[cell] < result.waterSurface()[cell]);
         if (x < 7) {
            assertTrue(result.waterSurface()[cell] >= result.waterSurface()[index(size, x + 1, 4)]);
         }
      }
   }

   @Test
   void majorDemDropStaysWetInsteadOfLeavingAFluidDrop() {
      int size = 9;
      PreviewGrid grid = flowingRow(size, new int[]{30, 30, 30, 10, 10, 10, 10});

      PreviewWaterFlowResolver.Result result = resolve(grid, FLOW_PARAMETERS);

      for (int x = 1; x <= 7; x++) {
         int cell = index(size, x, 4);
         assertTrue(result.inlandWater()[cell]);
         assertFalse(result.waterfallDrop()[cell]);
      }
      int top = index(size, 3, 4);
      int bottom = index(size, 4, 4);
      assertTrue(result.waterSurface()[top] > result.waterSurface()[bottom]);
      // The upper bed reaches below the lower pool, so the step is a column of water.
      assertTrue(result.terrainSurface()[top] < result.waterSurface()[bottom]);
   }

   @Test
   void previewKeepsUpperPlateauWhenOnlyItTouchesGridBoundary() {
      int size = 15;
      int area = size * size;
      int[] terrain = new int[area];
      Arrays.fill(terrain, 140);
      boolean[] inland = new boolean[area];
      boolean[] line = new boolean[area];
      boolean[] polygon = new boolean[area];
      boolean[] flowing = new boolean[area];
      for (int z = 0; z <= 12; z++) {
         for (int x = 5; x <= 9; x++) {
            int cell = index(size, x, z);
            terrain[cell] = z <= 5 ? 128 : 100;
            inland[cell] = true;
            line[cell] = true;
            polygon[cell] = true;
            flowing[cell] = true;
         }
      }
      PreviewGrid grid = new PreviewGrid(
         size, terrain, inland, new boolean[area], line, polygon, flowing, new int[area]
      );

      PreviewWaterFlowResolver.Result result = resolve(grid, FLOW_PARAMETERS);

      int upstreamSurface = result.waterSurface()[index(size, 7, 3)];
      int downstreamSurface = result.waterSurface()[index(size, 7, 9)];
      assertTrue(upstreamSurface > downstreamSurface);
      for (int x = 5; x <= 9; x++) {
         int edge = index(size, x, 5);
         int below = index(size, x, 6);
         assertFalse(result.waterfallDrop()[edge]);
         assertFalse(result.waterfallDrop()[below]);
         assertTrue(result.terrainSurface()[edge] < result.waterSurface()[below]);
      }
   }

   @Test
   void previewCarvesOverBudgetRidgeOnlyWithinTheCutBudget() {
      int size = 9;
      PreviewGrid grid = flowingRow(size, new int[]{16, 15, 30, 14, 13, 12, 10});
      InlandWaterFlowAnalyzer.Parameters parameters = new InlandWaterFlowAnalyzer.Parameters(4, 12, 24, 30, 12, 6, 48, 4);

      PreviewWaterFlowResolver.Result result = resolve(grid, parameters);

      int ridge = index(size, 3, 4);
      assertTrue(result.inlandWater()[ridge]);
      assertFalse(result.waterfallDrop()[ridge]);
      assertTrue(30 - result.waterSurface()[ridge] <= parameters.maxHydroFlattenCut());
      assertTrue(result.terrainSurface()[ridge] < result.waterSurface()[ridge]);
   }

   @Test
   void previewNeverUsesOrdinaryReachCellsAsFluidDrops() {
      int size = 13;
      PreviewGrid grid = flowingRow(size, new int[]{30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20});
      InlandWaterFlowAnalyzer.Parameters parameters = new InlandWaterFlowAnalyzer.Parameters(
         4, 12, 24, 30, 12, 6, 48, 4
      );

      PreviewWaterFlowResolver.Result result = resolve(grid, parameters);

      for (int x = 1; x < 11; x++) {
         int cell = index(size, x, 4);
         int next = index(size, x + 1, 4);
         assertTrue(result.inlandWater()[cell]);
         assertFalse(result.waterfallDrop()[cell]);
         int step = result.waterSurface()[cell] - result.waterSurface()[next];
         assertTrue(step >= 0 && step <= 1, "Expected a small downhill step at x=" + x + ", got " + step);
      }
   }

   @Test
   void correctedLakeIsFlatAndNaturalizesNearbyBank() {
      int size = 9;
      int area = size * size;
      int[] terrain = new int[area];
      Arrays.fill(terrain, 22);
      boolean[] inland = new boolean[area];
      boolean[] empty = new boolean[area];
      int height = 19;
      for (int z = 3; z <= 5; z++) {
         for (int x = 3; x <= 5; x++) {
            int cell = index(size, x, z);
            inland[cell] = true;
            terrain[cell] = height++ % 3 + 19;
         }
      }
      PreviewGrid corrected = new PreviewGrid(size, terrain, inland, empty, empty, inland, empty, new int[area]);

      PreviewWaterFlowResolver.Result correctedResult = resolve(corrected, FLOW_PARAMETERS);
      int lakeSurface = correctedResult.waterSurface()[index(size, 3, 3)];
      for (int z = 3; z <= 5; z++) {
         for (int x = 3; x <= 5; x++) {
            assertEquals(lakeSurface, correctedResult.waterSurface()[index(size, x, z)]);
         }
      }
      assertTrue(correctedResult.terrainSurface()[index(size, 2, 4)] < 22);
   }

   @Test
   void lineOnlyRiverIsCarvedLikeAMappedRiver() {
      int size = 9;
      int[] heights = {28, 24, 20, 16, 12, 8, 4};

      PreviewWaterFlowResolver.Result lineOnly = resolve(flowingLineOnlyRow(size, heights), FLOW_PARAMETERS);
      PreviewWaterFlowResolver.Result mapped = resolve(flowingRow(size, heights), FLOW_PARAMETERS);

      assertArrayEquals(mapped.waterSurface(), lineOnly.waterSurface());
      assertArrayEquals(mapped.terrainSurface(), lineOnly.terrainSurface());
      for (int x = 1; x <= 7; x++) {
         int cell = index(size, x, 4);
         assertTrue(lineOnly.inlandWater()[cell]);
         assertFalse(lineOnly.waterfallDrop()[cell]);
         assertTrue(lineOnly.terrainSurface()[cell] < lineOnly.waterSurface()[cell]);
      }
   }

   @Test
   void waterfallMarkerZoneFollowsRawDemAndCarvingResumesOutsideIt() {
      int size = 13;
      int area = size * size;
      int[] terrain = new int[area];
      Arrays.fill(terrain, 40);
      boolean[] inland = new boolean[area];
      boolean[] line = new boolean[area];
      boolean[] polygon = new boolean[area];
      boolean[] flowing = new boolean[area];
      boolean[] noCarve = new boolean[area];
      int[] profile = new int[]{30, 38, 30, 30, 28, 16, 10, 10, 18, 10, 10};
      for (int offset = 0; offset < profile.length; offset++) {
         int x = offset + 1;
         int cell = index(size, x, 6);
         terrain[cell] = profile[offset];
         inland[cell] = true;
         line[cell] = true;
         polygon[cell] = true;
         flowing[cell] = true;
      }
      for (int z = 4; z <= 8; z++) {
         for (int x = 5; x <= 7; x++) {
            noCarve[index(size, x, z)] = true;
         }
      }
      PreviewGrid grid = new PreviewGrid(
         size, terrain, inland, new boolean[area], line, polygon, flowing, new int[area]
      );
      InlandWaterFlowAnalyzer.Parameters markerDriven = new InlandWaterFlowAnalyzer.Parameters(
         4, 12, 24, 10, 12, 6, 48, 4, false
      );

      PreviewWaterFlowResolver.Result result = resolve(grid, markerDriven, 1, noCarve);

      for (int x = 5; x <= 7; x++) {
         int cell = index(size, x, 6);
         assertEquals(terrain[cell], result.waterSurface()[cell]);
         // Beds under the falls connect down to the lower water instead of leaving dry steps.
         assertTrue(result.terrainSurface()[cell] < result.waterSurface()[cell]);
         assertFalse(result.waterfallDrop()[cell]);
         assertTrue(result.waterfallProtection()[cell]);
      }
      int protectedLand = index(size, 6, 5);
      assertEquals(terrain[protectedLand], result.terrainSurface()[protectedLand]);
      assertTrue(result.waterfallProtection()[protectedLand]);

      int upstreamHump = index(size, 2, 6);
      assertTrue(result.terrainSurface()[upstreamHump] < terrain[upstreamHump]);
      assertFalse(result.waterfallProtection()[upstreamHump]);

      int downstreamHump = index(size, 9, 6);
      assertTrue(result.terrainSurface()[downstreamHump] < terrain[downstreamHump]);
      assertFalse(result.waterfallProtection()[downstreamHump]);
   }

   private static PreviewWaterFlowResolver.Result resolve(
      PreviewGrid grid,
      InlandWaterFlowAnalyzer.Parameters parameters
   ) {
      return resolve(grid, parameters, 1);
   }

   private static PreviewWaterFlowResolver.Result resolve(
      PreviewGrid grid,
      InlandWaterFlowAnalyzer.Parameters parameters,
      int cellSizeBlocks
   ) {
      return resolve(grid, parameters, cellSizeBlocks, new boolean[grid.size() * grid.size()]);
   }

   private static PreviewWaterFlowResolver.Result resolve(
      PreviewGrid grid,
      InlandWaterFlowAnalyzer.Parameters parameters,
      int cellSizeBlocks,
      boolean[] waterfallNoCarve
   ) {
      return PreviewWaterFlowResolver.resolve(
         grid.size(),
         cellSizeBlocks,
         0,
         0,
         grid.terrain(),
         grid.inland(),
         grid.ocean(),
         grid.line(),
         grid.area(),
         grid.flowing(),
         waterfallNoCarve,
         grid.oceanSurface(),
         2,
         20,
         12,
         4,
         5,
         true,
         parameters
      );
   }

   private static PreviewGrid flowingRow(int size, int[] heights) {
      int area = size * size;
      int[] terrain = new int[area];
      Arrays.fill(terrain, 40);
      boolean[] inland = new boolean[area];
      boolean[] line = new boolean[area];
      boolean[] polygon = new boolean[area];
      boolean[] flowing = new boolean[area];
      for (int offset = 0; offset < heights.length; offset++) {
         int cell = index(size, offset + 1, 4);
         terrain[cell] = heights[offset];
         inland[cell] = true;
         line[cell] = true;
         polygon[cell] = true;
         flowing[cell] = true;
      }
      return new PreviewGrid(size, terrain, inland, new boolean[area], line, polygon, flowing, new int[area]);
   }

   private static PreviewGrid flowingLineOnlyRow(int size, int[] heights) {
      PreviewGrid polygonGrid = flowingRow(size, heights);
      return new PreviewGrid(
         size,
         polygonGrid.terrain(),
         polygonGrid.inland(),
         polygonGrid.ocean(),
         polygonGrid.line(),
         new boolean[size * size],
         polygonGrid.flowing(),
         polygonGrid.oceanSurface()
      );
   }

   private static int index(int size, int x, int z) {
      return x + z * size;
   }

   private record PreviewGrid(
      int size,
      int[] terrain,
      boolean[] inland,
      boolean[] ocean,
      boolean[] line,
      boolean[] area,
      boolean[] flowing,
      int[] oceanSurface
   ) {
   }
}
