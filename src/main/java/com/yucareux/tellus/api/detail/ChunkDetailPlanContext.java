package com.yucareux.tellus.api.detail;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Objects;

/**
 * Immutable terrain snapshot supplied to off-thread chunk-detail planning.
 */
public final class ChunkDetailPlanContext {
   public static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   private final int chunkX;
   private final int chunkZ;
   private final int minY;
   private final int maxY;
   private final long worldSeed;
   private final long generationStamp;
   private final WorldProjection projection;
   private final boolean mappedRoadsEnabled;
   private final int[] terrainSurfaces;
   private final int[] waterSurfaces;
   private final boolean[] waterFlags;
   private final int[] landCoverClasses;

   public ChunkDetailPlanContext(
      int chunkX,
      int chunkZ,
      int minY,
      int maxY,
      long worldSeed,
      long generationStamp,
      WorldProjection projection,
      boolean mappedRoadsEnabled,
      int[] terrainSurfaces,
      int[] waterSurfaces,
      boolean[] waterFlags,
      int[] landCoverClasses
   ) {
      if (maxY < minY) {
         throw new IllegalArgumentException("maxY must be at least minY");
      }
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.minY = minY;
      this.maxY = maxY;
      this.worldSeed = worldSeed;
      this.generationStamp = generationStamp;
      this.projection = Objects.requireNonNull(projection, "projection");
      this.mappedRoadsEnabled = mappedRoadsEnabled;
      this.terrainSurfaces = copyColumns(terrainSurfaces, "terrainSurfaces");
      this.waterSurfaces = copyColumns(waterSurfaces, "waterSurfaces");
      this.waterFlags = copyColumns(waterFlags, "waterFlags");
      this.landCoverClasses = copyColumns(landCoverClasses, "landCoverClasses");
   }

   public int chunkX() {
      return this.chunkX;
   }

   public int chunkZ() {
      return this.chunkZ;
   }

   public int minBlockX() {
      return this.chunkX * CHUNK_SIDE;
   }

   public int minBlockZ() {
      return this.chunkZ * CHUNK_SIDE;
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

   public long generationStamp() {
      return this.generationStamp;
   }

   public WorldProjection projection() {
      return this.projection;
   }

   /** Whether Tellus's own mapped road layer is enabled for this world. */
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

   private static int index(int localX, int localZ) {
      if (localX < 0 || localX >= CHUNK_SIDE || localZ < 0 || localZ >= CHUNK_SIDE) {
         throw new IndexOutOfBoundsException("Chunk-local column outside 0..15: " + localX + "," + localZ);
      }
      return localZ * CHUNK_SIDE + localX;
   }

   private static int[] copyColumns(int[] values, String name) {
      Objects.requireNonNull(values, name);
      if (values.length != CHUNK_AREA) {
         throw new IllegalArgumentException(name + " must contain exactly " + CHUNK_AREA + " values");
      }
      return values.clone();
   }

   private static boolean[] copyColumns(boolean[] values, String name) {
      Objects.requireNonNull(values, name);
      if (values.length != CHUNK_AREA) {
         throw new IllegalArgumentException(name + " must contain exactly " + CHUNK_AREA + " values");
      }
      return values.clone();
   }
}
