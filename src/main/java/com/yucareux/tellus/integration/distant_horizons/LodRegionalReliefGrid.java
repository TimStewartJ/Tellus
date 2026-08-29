package com.yucareux.tellus.integration.distant_horizons;

import com.yucareux.tellus.worldgen.BadlandsTerrainPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class LodRegionalReliefGrid {
   private LodRegionalReliefGrid() {
   }

   public static double[] compute(
      int[] worldXs,
      int[] worldZs,
      int[] terrainHeights,
      int[] visualCoverClasses,
      double worldScale,
      HeightToElevation heightToElevation,
      ElevationFallback fallback
   ) {
      int width = worldXs.length;
      if (width == 0
         || worldZs.length != width
         || terrainHeights.length != width * width
         || visualCoverClasses.length != terrainHeights.length) {
         throw new IllegalArgumentException("Invalid LOD relief grid dimensions");
      }
      double[] reliefByColumn = new double[terrainHeights.length];
      Arrays.fill(reliefByColumn, Double.NaN);
      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         return reliefByColumn;
      }

      int regionalCellBlocks = BadlandsTerrainPolicy.regionalSampleCellBlocks(worldScale);
      int sampleOffsetBlocks = Math.max(
         4,
         (int)Math.min(Integer.MAX_VALUE, Math.ceil(BadlandsTerrainPolicy.CANYON_RELIEF_SAMPLE_METERS / worldScale))
      );
      Map<Long, Double> reliefByRegion = new HashMap<>();
      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            int index = z * width + x;
            if (!BadlandsTerrainPolicy.isDryCanyonCover(visualCoverClasses[index])) {
               continue;
            }
            int regionX = Math.floorDiv(worldXs[x], regionalCellBlocks);
            int regionZ = Math.floorDiv(worldZs[z], regionalCellBlocks);
            long key = (long)regionX << 32 | regionZ & 0xffffffffL;
            reliefByColumn[index] = reliefByRegion.computeIfAbsent(
               key,
               ignored -> sampleRegion(
                  regionX,
                  regionZ,
                  regionalCellBlocks,
                  sampleOffsetBlocks,
                  worldXs,
                  worldZs,
                  terrainHeights,
                  heightToElevation,
                  fallback
               )
            );
         }
      }
      return reliefByColumn;
   }

   private static double sampleRegion(
      int regionX,
      int regionZ,
      int regionalCellBlocks,
      int sampleOffsetBlocks,
      int[] worldXs,
      int[] worldZs,
      int[] terrainHeights,
      HeightToElevation heightToElevation,
      ElevationFallback fallback
   ) {
      int centerX = cellCenter(regionX, regionalCellBlocks);
      int centerZ = cellCenter(regionZ, regionalCellBlocks);
      int eastX = offsetCoordinate(centerX, sampleOffsetBlocks);
      int westX = offsetCoordinate(centerX, -sampleOffsetBlocks);
      int southZ = offsetCoordinate(centerZ, sampleOffsetBlocks);
      int northZ = offsetCoordinate(centerZ, -sampleOffsetBlocks);
      double[] elevations = new double[]{
         sampleElevation(centerX, centerZ, worldXs, worldZs, terrainHeights, heightToElevation, fallback),
         sampleElevation(eastX, centerZ, worldXs, worldZs, terrainHeights, heightToElevation, fallback),
         sampleElevation(westX, centerZ, worldXs, worldZs, terrainHeights, heightToElevation, fallback),
         sampleElevation(centerX, southZ, worldXs, worldZs, terrainHeights, heightToElevation, fallback),
         sampleElevation(centerX, northZ, worldXs, worldZs, terrainHeights, heightToElevation, fallback)
      };
      double min = Double.POSITIVE_INFINITY;
      double max = Double.NEGATIVE_INFINITY;
      int samples = 0;
      for (double elevation : elevations) {
         if (Double.isFinite(elevation)) {
            min = Math.min(min, elevation);
            max = Math.max(max, elevation);
            samples++;
         }
      }
      return samples >= 3 ? max - min : Double.NaN;
   }

   private static double sampleElevation(
      int worldX,
      int worldZ,
      int[] worldXs,
      int[] worldZs,
      int[] terrainHeights,
      HeightToElevation heightToElevation,
      ElevationFallback fallback
   ) {
      int x = nearestGridIndex(worldXs, worldX);
      int z = nearestGridIndex(worldZs, worldZ);
      if (x >= 0 && z >= 0) {
         return heightToElevation.convert(terrainHeights[z * worldXs.length + x], worldZs[z]);
      }
      return fallback.sample(worldX, worldZ);
   }

   private static int nearestGridIndex(int[] coordinates, int coordinate) {
      if (coordinates.length == 1) {
         return coordinates[0] == coordinate ? 0 : -1;
      }
      long step = (long)coordinates[1] - coordinates[0];
      if (step <= 0L) {
         throw new IllegalArgumentException("LOD relief coordinates must be strictly increasing");
      }
      long halfStep = step / 2L;
      if ((long)coordinate < (long)coordinates[0] - halfStep
         || (long)coordinate > (long)coordinates[coordinates.length - 1] + halfStep) {
         return -1;
      }
      long index = Math.round(((double)coordinate - coordinates[0]) / step);
      return index >= 0L && index < coordinates.length ? (int)index : -1;
   }

   private static int cellCenter(int cell, int cellSize) {
      long center = (long)cell * cellSize + cellSize / 2L;
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, center));
   }

   private static int offsetCoordinate(int coordinate, int offset) {
      return (int)Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long)coordinate + offset));
   }

   @FunctionalInterface
   public interface HeightToElevation {
      double convert(int terrainHeight, int blockZ);
   }

   @FunctionalInterface
   public interface ElevationFallback {
      double sample(int blockX, int blockZ);
   }
}
