package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.TerrainSlopePolicy;

public final class LodSurfaceSlopeGrid {
   private LodSurfaceSlopeGrid() {
   }

   public static double[] compute(
      int[] worldXs,
      int[] worldZs,
      int[] terrainHeights,
      double worldScale,
      HeightToElevation heightToElevation,
      ElevationFallback elevationFallback
   ) {
      int width = worldXs.length;
      if (width == 0 || worldZs.length != width || terrainHeights.length != width * width) {
         throw new IllegalArgumentException("Invalid LOD slope grid dimensions");
      }

      double[] slopes = new double[terrainHeights.length];
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         java.util.Arrays.fill(slopes, Double.NaN);
         return slopes;
      }
      if (width == 1) {
         slopes[0] = Double.NaN;
         return slopes;
      }

      int stepX = uniformStep(worldXs, "X");
      int stepZ = uniformStep(worldZs, "Z");
      int paddedWidth = width + 2;
      double[] elevations = new double[paddedWidth * paddedWidth];
      java.util.Arrays.fill(elevations, Double.NaN);
      for (int z = 0; z < width; z++) {
         int terrainRow = z * width;
         int elevationRow = (z + 1) * paddedWidth + 1;
         for (int x = 0; x < width; x++) {
            elevations[elevationRow + x] = heightToElevation.convert(terrainHeights[terrainRow + x], worldZs[z]);
         }
      }
      int westX = saturatedOffset(worldXs[0], -stepX);
      int eastX = saturatedOffset(worldXs[width - 1], stepX);
      for (int z = 0; z < width; z++) {
         int row = (z + 1) * paddedWidth;
         elevations[row] = elevationFallback.sample(westX, worldZs[z]);
         elevations[row + width + 1] = elevationFallback.sample(eastX, worldZs[z]);
      }
      int northZ = saturatedOffset(worldZs[0], -stepZ);
      int southZ = saturatedOffset(worldZs[width - 1], stepZ);
      for (int x = 0; x < width; x++) {
         elevations[x + 1] = elevationFallback.sample(worldXs[x], northZ);
         elevations[(width + 1) * paddedWidth + x + 1] = elevationFallback.sample(worldXs[x], southZ);
      }

      for (int z = 0; z < width; z++) {
         int outputRow = z * width;
         int elevationRow = (z + 1) * paddedWidth + 1;
         double eastWestDistance = stepX * EarthProjection.groundMetersPerBlockX(worldZs[z], worldScale);
         double northSouthDistance = stepZ * EarthProjection.groundMetersPerBlockZ(worldZs[z], worldScale);
         for (int x = 0; x < width; x++) {
            int outputIndex = outputRow + x;
            int elevationIndex = elevationRow + x;
            slopes[outputIndex] = TerrainSlopePolicy.localSlopeDegrees(
               elevations[elevationIndex],
               elevations[elevationIndex + 1],
               elevations[elevationIndex - 1],
               elevations[elevationIndex - paddedWidth],
               elevations[elevationIndex + paddedWidth],
               eastWestDistance,
               northSouthDistance
            );
         }
      }
      return slopes;
   }

   private static int uniformStep(int[] coordinates, String axis) {
      long step = (long)coordinates[1] - coordinates[0];
      if (step <= 0L || step > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("LOD slope " + axis + " coordinates must be strictly increasing");
      }
      for (int i = 2; i < coordinates.length; i++) {
         if ((long)coordinates[i] - coordinates[i - 1] != step) {
            throw new IllegalArgumentException("LOD slope " + axis + " coordinates must use a uniform step");
         }
      }
      return (int)step;
   }

   private static int saturatedOffset(int coordinate, int offset) {
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long)coordinate + offset));
   }

   @FunctionalInterface
   public interface HeightToElevation {
      double convert(int terrainHeight, int blockZ);
   }

   @FunctionalInterface
   public interface ElevationFallback {
      double sample(int worldX, int worldZ);
   }
}
