package com.yucareux.tellus.integration.distant_horizons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DistantHorizonsRuntimeConfigGuardTest {
   private static final String N_SIZED_OWNER = "com.seibel.distanthorizons.core.config.Config$Server$Experimental";
   private static final String N_SIZED_FIELD = "enableNSizedGeneration";
   private static final String UPSAMPLING_OWNER = "com.seibel.distanthorizons.core.config.Config$Common$LodBuilding$Experimental";
   private static final String UPSAMPLING_FIELD = "upsampleLowerDetailLodsToFillHoles";
   private static final String WORLD_GEN_OWNER = "com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator";
   private static final String PAUSE_SPEED_FIELD = "pauseGenerationAboveCameraSpeed";

   @Test
   void raisesForkPauseSpeedAndRestoresIt() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      FakeConfigEntry pauseSpeed = resolver.add(WORLD_GEN_OWNER, PAUSE_SPEED_FIELD, 20.0);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(60.0, pauseSpeed.value);
      assertEquals(1, pauseSpeed.writeCount);
      assertEquals(Boolean.TRUE, nSized.value);

      guard.release("minecraft:overworld");
      assertEquals(20.0, pauseSpeed.value);
      assertEquals(2, pauseSpeed.writeCount);
   }

   @Test
   void pauseSpeedIsRaisedEvenWithoutNSizedForcing() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry pauseSpeed = resolver.add(WORLD_GEN_OWNER, PAUSE_SPEED_FIELD, 20.0);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(false, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(60.0, pauseSpeed.value);
      guard.release("minecraft:overworld");
      assertEquals(20.0, pauseSpeed.value);
   }

   @Test
   void wrongTypedEntryIsLeftUntouched() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry pauseSpeed = resolver.add(WORLD_GEN_OWNER, PAUSE_SPEED_FIELD, 20);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(false, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(20, pauseSpeed.value);
      assertEquals(0, pauseSpeed.writeCount);
      guard.release("minecraft:overworld");
   }

   @Test
   void appliesOnceAndRestoresOnlyAfterLastDimensionReleases() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      FakeConfigEntry upsampling = resolver.add(UPSAMPLING_OWNER, UPSAMPLING_FIELD, true);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(Boolean.TRUE, nSized.value);
      assertEquals(1, nSized.writeCount);
      assertEquals(Boolean.TRUE, upsampling.value);
      assertEquals(0, upsampling.writeCount);

      assertTrue(guard.acquire("minecraft:the_nether"));
      assertFalse(guard.acquire("minecraft:overworld"));
      assertEquals(2, guard.activeDimensionCount());
      assertEquals(1, nSized.writeCount);
      assertEquals(0, upsampling.writeCount);

      guard.release("minecraft:overworld");
      assertEquals(Boolean.TRUE, nSized.value);
      assertEquals(Boolean.TRUE, upsampling.value);

      guard.release("minecraft:the_nether");
      assertEquals(Boolean.FALSE, nSized.value);
      assertEquals(Boolean.TRUE, upsampling.value);
      assertEquals(0, guard.activeDimensionCount());
      assertEquals(2, nSized.writeCount);
      assertEquals(0, upsampling.writeCount);
   }

   @Test
   void immediateReleaseRestoresValuesAfterRegistrationFailure() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      FakeConfigEntry upsampling = resolver.add(UPSAMPLING_OWNER, UPSAMPLING_FIELD, true);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      boolean acquired = guard.acquire("minecraft:overworld");
      if (acquired) {
         guard.release("minecraft:overworld");
      }

      assertEquals(Boolean.FALSE, nSized.value);
      assertEquals(Boolean.TRUE, upsampling.value);
      assertEquals(0, guard.activeDimensionCount());
   }

   @Test
   void missingExperimentalFieldDoesNotBlockOtherOverride() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(Boolean.TRUE, nSized.value);
      guard.release("minecraft:overworld");
      assertEquals(Boolean.FALSE, nSized.value);
   }

   @Test
   void alreadyEnabledValueIsLeftUntouched() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, true);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(Boolean.TRUE, nSized.value);
      assertEquals(0, nSized.writeCount);

      guard.release("minecraft:overworld");
      assertEquals(Boolean.TRUE, nSized.value);
      assertEquals(0, nSized.writeCount);
      assertEquals(0, guard.activeDimensionCount());
   }

   @Test
   void disabledUpsamplingIsForcedOnWithNSizedGenerationAndRestored() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      FakeConfigEntry upsampling = resolver.add(UPSAMPLING_OWNER, UPSAMPLING_FIELD, false);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(true, resolver);

      assertTrue(guard.acquire("minecraft:overworld"));
      assertEquals(Boolean.TRUE, nSized.value);
      assertEquals(Boolean.TRUE, upsampling.value);
      assertEquals(1, upsampling.writeCount);

      assertTrue(guard.acquire("minecraft:the_nether"));
      assertEquals(1, upsampling.writeCount);

      guard.release("minecraft:overworld");
      assertEquals(Boolean.TRUE, upsampling.value);

      guard.release("minecraft:the_nether");
      assertEquals(Boolean.FALSE, upsampling.value);
      assertEquals(Boolean.FALSE, nSized.value);
      assertEquals(2, upsampling.writeCount);
   }

   @Test
   void upsamplingRemainsUserControlledWhenNSizedForcingIsDisabled() {
      FakeResolver resolver = new FakeResolver();
      FakeConfigEntry nSized = resolver.add(N_SIZED_OWNER, N_SIZED_FIELD, false);
      FakeConfigEntry upsampling = resolver.add(UPSAMPLING_OWNER, UPSAMPLING_FIELD, true);
      DistantHorizonsRuntimeConfigGuard guard = new DistantHorizonsRuntimeConfigGuard(false, resolver);

      guard.acquire("minecraft:overworld");
      assertEquals(Boolean.FALSE, nSized.value);
      assertEquals(Boolean.TRUE, upsampling.value);
      assertEquals(0, nSized.writeCount);
      assertEquals(0, upsampling.writeCount);
      guard.release("minecraft:overworld");
      assertEquals(Boolean.TRUE, upsampling.value);
   }

   private static final class FakeResolver implements DistantHorizonsRuntimeConfigGuard.ConfigEntryResolver {
      private final Map<String, FakeConfigEntry> entries = new HashMap<>();

      FakeConfigEntry add(String ownerClassName, String fieldName, Object value) {
         FakeConfigEntry entry = new FakeConfigEntry(value);
         this.entries.put(key(ownerClassName, fieldName), entry);
         return entry;
      }

      @Override
      public DistantHorizonsRuntimeConfigGuard.ConfigEntryHandle resolve(String ownerClassName, String fieldName)
         throws NoSuchFieldException {
         FakeConfigEntry entry = this.entries.get(key(ownerClassName, fieldName));
         if (entry == null) {
            throw new NoSuchFieldException(fieldName);
         }
         return entry;
      }

      private static String key(String ownerClassName, String fieldName) {
         return ownerClassName + '#' + fieldName;
      }
   }

   private static final class FakeConfigEntry implements DistantHorizonsRuntimeConfigGuard.ConfigEntryHandle {
      private Object value;
      private int writeCount;

      private FakeConfigEntry(Object value) {
         this.value = value;
      }

      @Override
      public Object get() {
         return this.value;
      }

      @Override
      public void setWithoutSaving(Object value) {
         this.value = value;
         this.writeCount++;
      }
   }
}
