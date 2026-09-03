package com.yucareux.tellus.api.detail;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Objects;

/**
 * Immutable sampled terrain grid for companion-neutral coarse-detail exclusion planning.
 */
public final class ChunkDetailLodPlanContext {
   private static final int MAX_COLUMNS_PER_SIDE = 128;
   private final int minBlockX;
   private final int minBlockZ;
   private final int columnsPerSide;
   private final int cellSize;
   private final int minY;
   private final int maxY;
   private final long worldSeed;
   private final WorldProjection projection;
   private final boolean mappedRoadsEnabled;
   private final int[] terrainSurfaces;
   private final int[] waterSurfaces;
   private final boolean[] waterFlags;
   private final int[] landCoverClasses;

   public ChunkDetailLodPlanContext(
      int minBlockX,
      int minBlockZ,
      int columnsPerSide,
      int cellSize,
      int minY,
      int maxY,
      long worldSeed,
      WorldProjection projection,
      boolean mappedRoadsEnabled,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] landCoverClasses
   ) {
      if (columnsPerSide <= 0 || columnsPerSide > MAX_COLUMNS_PER_SIDE) {
         throw new IllegalArgumentException(
            "columnsPerSide must be within 1.." + MAX_COLUMNS_PER_SIDE
         );
      }
      if (cellSize <= 0) {
         throw new IllegalArgumentException("cellSize must be positive");
      }
      if (maxY < minY) {
         throw new IllegalArgumentException("maxY must be at least minY");
      }
      long span = (long)columnsPerSide * cellSize;
      if ((long)minBlockX + span - 1L > Integer.MAX_VALUE
         || (long)minBlockX + span - 1L < Integer.MIN_VALUE
         || (long)minBlockZ + span - 1L > Integer.MAX_VALUE
         || (long)minBlockZ + span - 1L < Integer.MIN_VALUE) {
         throw new IllegalArgumentException("LOD plan bounds exceed block-coordinate limits");
      }

      this.minBlockX = minBlockX;
      this.minBlockZ = minBlockZ;
      this.columnsPerSide = columnsPerSide;
      this.cellSize = cellSize;
      this.minY = minY;
      this.maxY = maxY;
      this.worldSeed = worldSeed;
      this.projection = Objects.requireNonNull(projection, "projection");
      this.mappedRoadsEnabled = mappedRoadsEnabled;
      int area = Math.multiplyExact(columnsPerSide, columnsPerSide);
      this.terrainSurfaces = copyColumns(terrainSurfaces, area, "terrainSurfaces");
      this.waterSurfaces = copyColumns(waterSurfaces, area, "waterSurfaces");
      this.waterFlags = copyColumns(waterFlags, area, "waterFlags");
      this.landCoverClasses = copyColumns(landCoverClasses, area, "landCoverClasses");
   }

   public int minBlockX() {
      return this.minBlockX;
   }

   public int minBlockZ() {
      return this.minBlockZ;
   }

   public int maxBlockX() {
      return (int)(
         (long)this.minBlockX + (long)this.columnsPerSide * this.cellSize - 1L
      );
   }

   public int maxBlockZ() {
      return (int)(
         (long)this.minBlockZ + (long)this.columnsPerSide * this.cellSize - 1L
      );
   }

   public int columnsPerSide() {
      return this.columnsPerSide;
   }

   public int cellSize() {
      return this.cellSize;
   }

   public int sampleWorldX(int localX) {
      checkLocal(localX);
      return (int)(
         (long)this.minBlockX
            + (long)localX * this.cellSize
            + (this.cellSize >> 1)
      );
   }

   public int sampleWorldZ(int localZ) {
      checkLocal(localZ);
      return (int)(
         (long)this.minBlockZ
            + (long)localZ * this.cellSize
            + (this.cellSize >> 1)
      );
   }

   public int minY() {
      return this.minY;
   }

   public int maxY() {
      return this.maxY;
   }

   public long worldSeed() {
      return this.worldSeed;
   }

   public WorldProjection projection() {
      return this.projection;
   }

   public boolean mappedRoadsEnabled() {
      return this.mappedRoadsEnabled;
   }

   public int terrainSurface(int localX, int localZ) {
      return this.terrainSurfaces[index(localX, localZ)];
   }

   public int waterSurface(int localX, int localZ) {
      return this.waterSurfaces[index(localX, localZ)];
   }

   public boolean hasWater(int localX, int localZ) {
      return this.waterFlags[index(localX, localZ)];
   }

   public int landCoverClass(int localX, int localZ) {
      return this.landCoverClasses[index(localX, localZ)];
   }

   private int index(int localX, int localZ) {
      checkLocal(localX);
      checkLocal(localZ);
      return localZ * this.columnsPerSide + localX;
   }

   private void checkLocal(int value) {
      if (value < 0 || value >= this.columnsPerSide) {
         throw new IndexOutOfBoundsException(
            "LOD-local coordinate outside 0.." + (this.columnsPerSide - 1) + ": " + value
         );
      }
   }

   private static int[] copyColumns(int[] values, int area, String name) {
      Objects.requireNonNull(values, name);
      if (values.length != area) {
         throw new IllegalArgumentException(name + " must contain exactly " + area + " values");
      }
      return values.clone();
   }

   private static boolean[] copyColumns(boolean[] values, int area, String name) {
      Objects.requireNonNull(values, name);
      if (values.length != area) {
         throw new IllegalArgumentException(name + " must contain exactly " + area + " values");
      }
      return values.clone();
   }
}
