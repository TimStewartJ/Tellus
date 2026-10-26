package com.yucareux.tellus.worldgen;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlandWaterFlowAnalyzerTest {
   private static final InlandWaterFlowAnalyzer.Parameters PARAMETERS = new InlandWaterFlowAnalyzer.Parameters(
      3,
      8,
      8,
      6,
      8,
      4,
      32,
      2
   );

   @Test
   void embedsFlatWaterOneBlockIntoTheTerrain() {
      Analysis analysis = analyzeLine(90, 90, 90, 90, 90, 90);

      for (int i = 0; i < 6; i++) {
         assertTrue(analysis.activeAt(i));
         assertFalse(analysis.waterfallAt(i));
         assertEquals(89, analysis.surfaceAt(i));
         assertTrue(analysis.correctionAt(i));
         assertTrue(analysis.blendRadiusAt(i) > PARAMETERS.baseBlendBlocks());
      }
   }

   @Test
   void carvesSmallUphillObstructionWithinBudget() {
      Analysis analysis = analyzeLine(110, 109, 111, 108, 107, 106);

      assertTrue(analysis.activeAt(2));
      assertTrue(analysis.correctionAt(2));
      assertTrue(111 - analysis.surfaceAt(2) <= PARAMETERS.maxHydroFlattenCut());
      for (int i = 0; i < 5; i++) {
         assertTrue(analysis.surfaceAt(i) >= analysis.surfaceAt(i + 1));
      }
      assertTrue(analysis.blendRadiusAt(2) > PARAMETERS.baseBlendBlocks());
   }

   @Test
   void followsLongSlopesInSmallConnectedStepsInsteadOfOneFlatSurface() {
      Analysis analysis = analyzeLine(
         130, 129, 128, 127, 126, 125, 124, 123, 122, 121,
         120, 119, 118, 117, 116, 115, 114, 113, 112, 111,
         110, 109, 108, 107, 106, 105, 104, 103, 102, 101, 100
      );

      for (int i = 0; i < 31; i++) {
         assertTrue(analysis.activeAt(i));
         assertFalse(analysis.waterfallAt(i));
         assertTrue(130 - i - analysis.surfaceAt(i) <= PARAMETERS.maxHydroFlattenCut());
      }
      for (int i = 0; i < 30; i++) {
         int step = analysis.surfaceAt(i) - analysis.surfaceAt(i + 1);
         assertTrue(step >= 0 && step <= 1, "Expected a small downhill step at " + i + ", got " + step);
      }
      assertTrue(analysis.surfaceAt(0) > analysis.surfaceAt(30), "A long reach must not collapse to its outlet level");
   }

   @Test
   void distantOutletOnlyLowersWaterWithinTheLookAhead() {
      InlandWaterFlowAnalyzer.Parameters deepCuts = new InlandWaterFlowAnalyzer.Parameters(3, 48, 8, 6, 8, 4, 32, 2);
      int[] profile = new int[30];
      Arrays.fill(profile, 100);
      profile[29] = 60;

      Analysis analysis = analyzeLine(deepCuts, profile);

      // One-block cells and an 8-block look-ahead: only the 8 cells next to the outlet can feel it.
      for (int i = 0; i <= 20; i++) {
         assertEquals(99, analysis.surfaceAt(i));
      }
      assertTrue(analysis.surfaceAt(21) < 99);
   }

   @Test
   void resultDoesNotDependOnCellOrder() {
      int[] profile = {110, 109, 111, 108, 107, 106, 100, 99, 105, 98};

      Analysis forward = analyzeLine(PARAMETERS, false, profile);
      Analysis reversed = analyzeLine(PARAMETERS, true, profile);

      for (int i = 0; i < profile.length; i++) {
         assertEquals(forward.surfaceAt(i), reversed.surfaceAt(i));
         assertEquals(forward.blendRadiusAt(i), reversed.blendRadiusAt(i));
      }
   }

   private static Analysis analyzeLine(int... terrainProfile) {
      return analyzeLine(PARAMETERS, terrainProfile);
   }

   private static Analysis analyzeLine(InlandWaterFlowAnalyzer.Parameters parameters, int... terrainProfile) {
      return analyzeLine(parameters, false, terrainProfile);
   }

   private static Analysis analyzeLine(InlandWaterFlowAnalyzer.Parameters parameters, boolean reverseOrder, int... terrainProfile) {
      int gridSize = terrainProfile.length + 2;
      int area = gridSize * gridSize;
      int z = gridSize / 2;
      int[] cells = new int[terrainProfile.length];
      int[] rawTerrain = new int[area];
      int[] ceiling = new int[area];
      boolean[] ocean = new boolean[area];
      int[] planned = new int[area];
      boolean[] active = new boolean[area];
      boolean[] waterfall = new boolean[area];
      int[] waterfallTop = new int[area];
      boolean[] correction = new boolean[area];
      int[] blendRadius = new int[area];
      boolean[] protection = new boolean[area];

      for (int i = 0; i < terrainProfile.length; i++) {
         int cell = z * gridSize + i + 1;
         cells[i] = cell;
         rawTerrain[cell] = terrainProfile[i];
         ceiling[cell] = terrainProfile[i];
      }
      int[] order = cells.clone();
      if (reverseOrder) {
         for (int i = 0; i < order.length / 2; i++) {
            int swap = order[i];
            order[i] = order[order.length - 1 - i];
            order[order.length - 1 - i] = swap;
         }
      }

      InlandWaterFlowAnalyzer.analyze(
         gridSize,
         order,
         order.length,
         rawTerrain,
         ceiling,
         ocean,
         1,
         3,
         parameters,
         planned,
         active,
         waterfall,
         waterfallTop,
         correction,
         blendRadius,
         protection,
         new InlandWaterFlowAnalyzer.Workspace()
      );
      return new Analysis(cells, planned, active, waterfall, correction, blendRadius);
   }

   private record Analysis(
      int[] cells,
      int[] surfaces,
      boolean[] active,
      boolean[] waterfall,
      boolean[] correction,
      int[] blendRadius
   ) {
      private int surfaceAt(int index) {
         return this.surfaces[this.cells[index]];
      }

      private boolean activeAt(int index) {
         return this.active[this.cells[index]];
      }

      private boolean waterfallAt(int index) {
         return this.waterfall[this.cells[index]];
      }

      private boolean correctionAt(int index) {
         return this.correction[this.cells[index]];
      }

      private int blendRadiusAt(int index) {
         return this.blendRadius[this.cells[index]];
      }
   }
}
