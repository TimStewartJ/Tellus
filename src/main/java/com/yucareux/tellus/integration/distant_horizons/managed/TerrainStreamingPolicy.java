package com.yucareux.tellus.integration.distant_horizons.managed;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;

public final class TerrainStreamingPolicy {
   public static final int COARSE_FIRST_MIN_DETAIL = intProperty("tellus.streaming.coarseFirstMinDetail", 6, 0, 24);

   private TerrainStreamingPolicy() {
   }

   public static boolean isAutomatic(EarthGeneratorSettings settings) {
      return settings.terrainStreamingStrategy().isAutomatic();
   }

   public static boolean isCoarseDetail(byte detailLevel) {
      return Byte.toUnsignedInt(detailLevel) >= COARSE_FIRST_MIN_DETAIL;
   }

   public static boolean managedDownloadsActive(EarthGeneratorSettings settings) {
      return isAutomatic(settings) && ManagedTerrainCompatibility.isGenerationGateAvailable();
   }

   public static boolean usesCacheOnlyFastLod(EarthGeneratorSettings settings, byte detailLevel) {
      return managedDownloadsActive(settings) || isAutomatic(settings) && isCoarseDetail(detailLevel);
   }

   public static boolean startsBackgroundPrefetch(EarthGeneratorSettings settings, byte detailLevel) {
      return isAutomatic(settings) && isCoarseDetail(detailLevel) && !managedDownloadsActive(settings);
   }

   public static byte fastLodAvailability(
      EarthGeneratorSettings settings,
      String managedTerrainKey,
      int chunkPosMinX,
      int chunkPosMinZ,
      int widthChunks,
      byte targetDataDetail
   ) {
      if (isAutomatic(settings) && generatedDetailForAvailability(widthChunks, targetDataDetail) >= COARSE_FIRST_MIN_DETAIL) {
         return ManagedTerrainAvailability.PRIORITY;
      }
      return managedDownloadsActive(settings)
         ? ManagedTerrainAvailability.availability(managedTerrainKey, chunkPosMinX, chunkPosMinZ, widthChunks)
         : ManagedTerrainAvailability.READY;
   }

   static int generatedDetailForAvailability(int widthChunks, byte targetDataDetail) {
      int safeWidth = Math.max(1, widthChunks);
      int chunkWidthDetail = 31 - Integer.numberOfLeadingZeros(safeWidth);
      return Math.max(0, Byte.toUnsignedInt(targetDataDetail) + chunkWidthDetail - 2);
   }

   private static int intProperty(String key, int defaultValue, int min, int max) {
      int value = Integer.getInteger(key, defaultValue);
      return Math.max(min, Math.min(max, value));
   }
}
