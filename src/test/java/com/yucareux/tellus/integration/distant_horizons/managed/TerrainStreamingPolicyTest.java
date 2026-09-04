package com.yucareux.tellus.integration.distant_horizons.managed;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainStreamingPolicyTest {
   @AfterEach
   void resetCompatibility() {
      ManagedTerrainCompatibility.setDistantHorizonsCompatibility(false, false);
      ManagedTerrainAvailability.clearAll();
   }

   @Test
   void automaticCoarseLodsAreReadyBeforeManagedCoverage() {
      ManagedTerrainCompatibility.setDistantHorizonsCompatibility(true, true);

      byte availability = TerrainStreamingPolicy.fastLodAvailability(
         EarthGeneratorSettings.DEFAULT, "test", 0, 0, 256, (byte)0
      );

      assertEquals(ManagedTerrainAvailability.PRIORITY, availability);
      assertTrue(TerrainStreamingPolicy.usesCacheOnlyFastLod(EarthGeneratorSettings.DEFAULT, (byte)6));
   }

   @Test
   void automaticFineLodsWaitForManagedCoverage() {
      ManagedTerrainCompatibility.setDistantHorizonsCompatibility(true, true);

      byte waiting = TerrainStreamingPolicy.fastLodAvailability(
         EarthGeneratorSettings.DEFAULT, "test", 0, 0, 4, (byte)5
      );
      ManagedTerrainAvailability.markReady("test", new ManagedTerrainCell(0, 0), false);
      byte ready = TerrainStreamingPolicy.fastLodAvailability(
         EarthGeneratorSettings.DEFAULT, "test", 0, 0, 4, (byte)5
      );

      assertEquals(ManagedTerrainAvailability.WAIT, waiting);
      assertEquals(ManagedTerrainAvailability.READY, ready);
   }

   @Test
   void automaticCoarseLodsStayCacheOnlyWithoutGenerationGate() {
      ManagedTerrainCompatibility.setDistantHorizonsCompatibility(true, false);

      assertFalse(TerrainStreamingPolicy.managedDownloadsActive(EarthGeneratorSettings.DEFAULT));
      assertTrue(TerrainStreamingPolicy.startsBackgroundPrefetch(EarthGeneratorSettings.DEFAULT, (byte)6));
      assertTrue(TerrainStreamingPolicy.usesCacheOnlyFastLod(EarthGeneratorSettings.DEFAULT, (byte)6));
      assertFalse(TerrainStreamingPolicy.usesCacheOnlyFastLod(EarthGeneratorSettings.DEFAULT, (byte)5));
   }

   @Test
   void derivesGeneratedDetailFromSectionWidthAndRequestedDataDetail() {
      assertEquals(0, TerrainStreamingPolicy.generatedDetailForAvailability(4, (byte)0));
      assertEquals(5, TerrainStreamingPolicy.generatedDetailForAvailability(128, (byte)0));
      assertEquals(6, TerrainStreamingPolicy.generatedDetailForAvailability(256, (byte)0));
      assertEquals(8, TerrainStreamingPolicy.generatedDetailForAvailability(64, (byte)4));
   }

   @Test
   void managedFallbackResolutionCoversTheGlobalMapterhornLevel() {
      assertEquals(
         16.0,
         TerrainStreamingPolicy.MANAGED_LOD_FALLBACK_RESOLUTION_METERS
      );
   }
}
