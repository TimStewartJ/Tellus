package com.yucareux.tellus.worldgen;

/**
 * Bounded, terrain-following inland-water reaches. The lowering envelope has a
 * finite support in world blocks, so a distant outlet or a different region
 * boundary cannot flatten an entire river. Fractional grades are rounded only
 * after relaxation, giving level pools separated by small, connected steps.
 */
final class InlandWaterFlowAnalyzer {
   private static final int[] NEIGHBOR_OFFSETS = {1, 0, -1, 0, 0, 1, 0, -1};
   private static final int GRADE_UNITS_PER_BLOCK = 4;

   private InlandWaterFlowAnalyzer() {
   }

   static void analyze(
      int gridSize,
      int[] componentCells,
      int componentCount,
      int[] rawTerrain,
      int[] surfaceCeiling,
      boolean[] oceanMask,
      int cellSizeBlocks,
      int channelWidthBlocks,
      Parameters parameters,
      int[] plannedWaterSurface,
      boolean[] activeWater,
      boolean[] waterfallDrop,
      int[] waterfallTop,
      boolean[] correctionRequired,
      int[] adaptiveBlendRadius,
      boolean[] waterfallProtection,
      Workspace workspace
   ) {
      if (componentCount <= 0) {
         return;
      }
      int area = gridSize * gridSize;
      if (gridSize <= 0 || componentCount > componentCells.length
         || rawTerrain.length < area || surfaceCeiling.length < area || oceanMask.length < area
         || plannedWaterSurface.length < area || activeWater.length < area || waterfallDrop.length < area
         || waterfallTop.length < area || correctionRequired.length < area
         || adaptiveBlendRadius.length < area || waterfallProtection.length < area) {
         throw new IllegalArgumentException("Invalid inland-water flow analysis dimensions");
      }
      workspace.ensureCapacity(area);
      int spacing = Math.max(1, cellSizeBlocks);
      int passes = Math.max(1, (parameters.hydroFlattenLookAheadBlocks() + spacing - 1) / spacing);
      int maxCut = Math.max(1, parameters.maxHydroFlattenCut());
      for (int i = 0; i < componentCount; i++) {
         int cell = componentCells[i];
         workspace.member[cell] = true;
         // Embed the surface in the DEM instead of placing a sheet above it.
         int ceiling = Math.min(rawTerrain[cell] - 1, surfaceCeiling[cell]);
         workspace.current[cell] = ceiling * GRADE_UNITS_PER_BLOCK;
         workspace.floor[cell] = Math.min(ceiling, rawTerrain[cell] - maxCut) * GRADE_UNITS_PER_BLOCK;
      }

      // Jacobi updates are independent of traversal order. Never read a value
      // written in this pass: that would let an outlet influence an unbounded
      // distance and reintroduce seams between independently generated regions.
      for (int pass = 0; pass < passes; pass++) {
         boolean changed = false;
         for (int i = 0; i < componentCount; i++) {
            int cell = componentCells[i];
            int level = workspace.current[cell];
            int x = cell % gridSize;
            int z = cell / gridSize;
            for (int n = 0; n < NEIGHBOR_OFFSETS.length; n += 2) {
               int nx = x + NEIGHBOR_OFFSETS[n];
               int nz = z + NEIGHBOR_OFFSETS[n + 1];
               if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) {
                  continue;
               }
               int neighbor = nz * gridSize + nx;
               if (workspace.member[neighbor]) {
                  level = Math.min(level, workspace.current[neighbor] + spacing);
               }
            }
            level = Math.max(level, workspace.floor[cell]);
            workspace.next[cell] = level;
            changed |= level != workspace.current[cell];
         }
         int[] swap = workspace.current;
         workspace.current = workspace.next;
         workspace.next = swap;
         if (!changed) {
            break;
         }
      }

      for (int i = 0; i < componentCount; i++) {
         int cell = componentCells[i];
         int surface = Math.floorDiv(workspace.current[cell], GRADE_UNITS_PER_BLOCK);
         plannedWaterSurface[cell] = surface;
         activeWater[cell] = true;
         // Drops are wet geometry from generation onward. No dry separator is
         // left for fluid simulation or a particular renderer to reconstruct.
         waterfallDrop[cell] = false;
         waterfallTop[cell] = Integer.MIN_VALUE;
         correctionRequired[cell] = rawTerrain[cell] != surface;
         int cut = Math.max(0, rawTerrain[cell] - surface);
         adaptiveBlendRadius[cell] = Math.min(parameters.maxAdaptiveBlendBlocks(),
            parameters.baseBlendBlocks() + Math.min(cut, maxCut) * 2);
         workspace.member[cell] = false;
      }
   }

   record Parameters(
      int maxProfileCut,
      int maxHydroFlattenCut,
      int hydroFlattenLookAheadBlocks,
      int waterfallMinDrop,
      int waterfallLookAheadBlocks,
      int baseBlendBlocks,
      int maxAdaptiveBlendBlocks,
      int waterfallProtectionBlocks,
      boolean inferWaterfalls
   ) {
      Parameters(
         int maxProfileCut,
         int maxHydroFlattenCut,
         int hydroFlattenLookAheadBlocks,
         int waterfallMinDrop,
         int waterfallLookAheadBlocks,
         int baseBlendBlocks,
         int maxAdaptiveBlendBlocks,
         int waterfallProtectionBlocks
      ) {
         this(
            maxProfileCut,
            maxHydroFlattenCut,
            hydroFlattenLookAheadBlocks,
            waterfallMinDrop,
            waterfallLookAheadBlocks,
            baseBlendBlocks,
            maxAdaptiveBlendBlocks,
            waterfallProtectionBlocks,
            true
         );
      }

      Parameters {
         if (maxProfileCut < 0
            || maxHydroFlattenCut < maxProfileCut
            || hydroFlattenLookAheadBlocks < 1
            || waterfallMinDrop < 1
            || waterfallLookAheadBlocks < 1
            || baseBlendBlocks < 0
            || maxAdaptiveBlendBlocks < baseBlendBlocks
            || waterfallProtectionBlocks < 0) {
            throw new IllegalArgumentException("Invalid inland-water flow parameters");
         }
      }
   }

   static final class Workspace {
      private boolean[] member = new boolean[0];
      private int[] current;
      private int[] next;
      private int[] floor;

      private void ensureCapacity(int area) {
         if (member.length < area) {
            member = new boolean[area];
            current = new int[area];
            next = new int[area];
            floor = new int[area];
         }
      }
   }
}
