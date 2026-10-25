package com.yucareux.tellus.integration.distant_horizons.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ManagedTerrainAvailabilityTest {
   private static final String KEY = "test";

   @AfterEach
   void clear() {
      ManagedTerrainAvailability.clearAll();
   }

   @Test
   void waitsForTheRestOfADownloadBatchThenAllowsGeneration() {
      assertEquals(ManagedTerrainAvailability.WAIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 64));
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(0, 0), false);
      assertEquals(ManagedTerrainAvailability.WAIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 64));
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(1, 0), false);
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(0, 1), false);
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(1, 1), true);
      assertEquals(ManagedTerrainAvailability.READY, ManagedTerrainAvailability.availability(KEY, 0, 0, 64));
   }

   @Test
   void splitsOnlyRequestsLargerThanTheProgressiveDownloadBatch() {
      assertEquals(256, ManagedTerrainAvailability.DEFAULT_PROGRESSIVE_BATCH_WIDTH_CHUNKS);
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(0, 0), false);
      assertEquals(ManagedTerrainAvailability.WAIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 256));
      assertEquals(ManagedTerrainAvailability.SPLIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 512));
   }

   @Test
   void adaptiveBatchWidthPreventsFineSplitsAtMaximumDistance() {
      ManagedTerrainAvailability.configureProgressiveBatchWidth(KEY, 2048);
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(0, 0), false);
      assertEquals(ManagedTerrainAvailability.WAIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 2048));
      assertEquals(ManagedTerrainAvailability.SPLIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 4096));
   }

   @Test
   void degradedCoverageReleasesGenerationWhileRemainingObservable() {
      ManagedTerrainCell cell = new ManagedTerrainCell(0, 0);

      ManagedTerrainAvailability.markReady(KEY, cell, true);

      assertEquals(ManagedTerrainAvailability.READY, ManagedTerrainAvailability.availability(KEY, 0, 0, 32));
      assertEquals(true, ManagedTerrainAvailability.isDegraded(KEY, cell));
   }

   @Test
   void sparseContinentalRequestsDoNotScanBillionsOfMissingCells() {
      ManagedTerrainAvailability.markReady(KEY, new ManagedTerrainCell(0, 0), false);
      assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
         assertEquals(ManagedTerrainAvailability.SPLIT, ManagedTerrainAvailability.availability(KEY, 0, 0, 1 << 24));
         assertEquals(ManagedTerrainAvailability.WAIT, ManagedTerrainAvailability.availability(KEY, 32, 32, 1 << 24));
      });
   }

   @Test
   void optimizedCoverageMatchesExhaustiveChecksIncludingNegativeCoordinates() {
      Random random = new Random(330);
      Set<ManagedTerrainCell> ready = new HashSet<>();
      for (int i = 0; i < 1500; i++) {
         ManagedTerrainCell cell = new ManagedTerrainCell(random.nextInt(48) - 24, random.nextInt(48) - 24);
         ready.add(cell);
         ManagedTerrainAvailability.markReady(KEY, cell, false);
      }
      for (int i = 0; i < 1000; i++) {
         int x = random.nextInt(1800) - 900;
         int z = random.nextInt(1800) - 900;
         int width = 1 + random.nextInt(800);
         int batchWidth = (i % 3 + 1) * 128;
         ManagedTerrainAvailability.configureProgressiveBatchWidth(KEY, batchWidth);
         ManagedTerrainCell min = ManagedTerrainCell.containingChunk(x, z);
         ManagedTerrainCell max = ManagedTerrainCell.containingChunk(x + width - 1, z + width - 1);
         int count = 0;
         int total = 0;
         for (int cz = min.z(); cz <= max.z(); cz++) {
            for (int cx = min.x(); cx <= max.x(); cx++) {
               total++;
               if (ready.contains(new ManagedTerrainCell(cx, cz))) count++;
            }
         }
         byte expected = count == total ? ManagedTerrainAvailability.READY
            : count > 0 && width > batchWidth ? ManagedTerrainAvailability.SPLIT : ManagedTerrainAvailability.WAIT;
         assertEquals(expected, ManagedTerrainAvailability.availability(KEY, x, z, width));
      }
   }
}
