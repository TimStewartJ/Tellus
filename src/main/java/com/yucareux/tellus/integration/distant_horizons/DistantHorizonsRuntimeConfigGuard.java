package com.yucareux.tellus.integration.distant_horizons;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Holds Distant Horizons runtime-only config overrides while Tellus direct LOD
 * generators are registered. The reflection boundary keeps Tellus compatible
 * with Distant Horizons releases that do not expose the experimental entry.
 */
final class DistantHorizonsRuntimeConfigGuard {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ConfigKey N_SIZED_GENERATION = new ConfigKey(
      "com.seibel.distanthorizons.core.config.Config$Server$Experimental",
      "enableNSizedGeneration",
      true,
      "Enabled Distant Horizons N-sized generation for Tellus far LODs"
   );
   /**
    * With N-sized generation, far LODs exist only at coarse detail. When the player approaches, DH
    * creates finer child sections that have no data yet; an empty section still counts as
    * renderable, so the coarse parent is switched off and the terrain vanishes until the fine tiles
    * are generated (which DH also pauses while moving faster than ~20 blocks/s). This DH option
    * pre-fills the children from the coarse parent so the area keeps rendering; DH's own config
    * comment recommends it whenever N-sized generation is active.
    */
   private static final ConfigKey UPSAMPLE_TO_FILL_HOLES = new ConfigKey(
      "com.seibel.distanthorizons.core.config.Config$Common$LodBuilding$Experimental",
      "upsampleLowerDetailLodsToFillHoles",
      true,
      "Enabled Distant Horizons lower-detail LOD upsampling so coarse Tellus LODs stay visible while finer tiles generate"
   );
   private static final boolean FORCE_UPSAMPLE_TO_FILL_HOLES = Boolean.parseBoolean(
      System.getProperty("tellus.dhForceUpsampleToFillHoles", "true")
   );
   private final Object lock = new Object();
   private final boolean forceNSizedGeneration;
   private final ConfigEntryResolver configEntryResolver;
   private final Set<String> activeDimensions = new HashSet<>();
   private RuntimeBooleanOverride nSizedGenerationOverride;
   private RuntimeBooleanOverride upsampleOverride;

   static DistantHorizonsRuntimeConfigGuard reflective(boolean forceNSizedGeneration) {
      return new DistantHorizonsRuntimeConfigGuard(forceNSizedGeneration, ReflectiveConfigEntryResolver.INSTANCE);
   }

   DistantHorizonsRuntimeConfigGuard(boolean forceNSizedGeneration, ConfigEntryResolver configEntryResolver) {
      this.forceNSizedGeneration = forceNSizedGeneration;
      this.configEntryResolver = Objects.requireNonNull(configEntryResolver, "configEntryResolver");
   }

   /**
    * Acquires the overrides for a dimension.
    *
    * @return true only when this call added a new dimension lease
    */
   boolean acquire(String dimensionName) {
      Objects.requireNonNull(dimensionName, "dimensionName");
      synchronized (this.lock) {
         if (!this.activeDimensions.add(dimensionName)) {
            return false;
         }

         if (this.activeDimensions.size() == 1) {
            if (this.forceNSizedGeneration) {
               this.nSizedGenerationOverride = this.tryApply(N_SIZED_GENERATION);
               if (FORCE_UPSAMPLE_TO_FILL_HOLES) {
                  this.upsampleOverride = this.tryApply(UPSAMPLE_TO_FILL_HOLES);
               }
            }
         }
         return true;
      }
   }

   /** Restores the captured values after the last Tellus dimension releases. */
   void release(String dimensionName) {
      if (dimensionName == null) {
         return;
      }

      synchronized (this.lock) {
         if (!this.activeDimensions.remove(dimensionName) || !this.activeDimensions.isEmpty()) {
            return;
         }

         this.restore(this.upsampleOverride);
         this.upsampleOverride = null;
         this.restore(this.nSizedGenerationOverride);
         this.nSizedGenerationOverride = null;
      }
   }

   int activeDimensionCount() {
      synchronized (this.lock) {
         return this.activeDimensions.size();
      }
   }

   private RuntimeBooleanOverride tryApply(ConfigKey configKey) {
      try {
         BooleanConfigEntry configEntry = this.configEntryResolver.resolve(configKey.ownerClassName(), configKey.fieldName());
         boolean previousValue = configEntry.get();
         if (previousValue == configKey.overrideValue()) {
            return null;
         }

         configEntry.setWithoutSaving(configKey.overrideValue());
         LOGGER.info("{} (runtime only; config unchanged)", configKey.appliedLogMessage());
         return new RuntimeBooleanOverride(configKey, configEntry, previousValue);
      } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException error) {
         // Experimental config fields have moved between DH versions. Their
         // absence must never prevent the Tellus generator from registering.
         LOGGER.debug(
            "Distant Horizons config entry {}.{} is unavailable; skipping this Tellus runtime override",
            configKey.ownerClassName(),
            configKey.fieldName()
         );
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         LOGGER.warn(
            "Could not apply Tellus runtime override for Distant Horizons config entry {}.{}",
            configKey.ownerClassName(),
            configKey.fieldName(),
            error
         );
      }
      return null;
   }

   private void restore(RuntimeBooleanOverride runtimeOverride) {
      if (runtimeOverride == null) {
         return;
      }

      try {
         runtimeOverride.configEntry().setWithoutSaving(runtimeOverride.previousValue());
         if (runtimeOverride.previousValue() != runtimeOverride.configKey().overrideValue()) {
            LOGGER.info(
               "Restored Distant Horizons {} after unloading the last Tellus direct LOD generator",
               runtimeOverride.configKey().fieldName()
            );
         }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         LOGGER.warn(
            "Failed to restore Distant Horizons config entry {}.{}",
            runtimeOverride.configKey().ownerClassName(),
            runtimeOverride.configKey().fieldName(),
            error
         );
      }
   }

   interface ConfigEntryResolver {
      BooleanConfigEntry resolve(String ownerClassName, String fieldName) throws ReflectiveOperationException;
   }

   interface BooleanConfigEntry {
      boolean get() throws ReflectiveOperationException;

      void setWithoutSaving(boolean value) throws ReflectiveOperationException;
   }

   private enum ReflectiveConfigEntryResolver implements ConfigEntryResolver {
      INSTANCE;

      @Override
      public BooleanConfigEntry resolve(String ownerClassName, String fieldName) throws ReflectiveOperationException {
         Class<?> configOwner = Class.forName(ownerClassName);
         Object configEntry = configOwner.getField(fieldName).get(null);
         Method getter = configEntry.getClass().getMethod("get");
         Method setter = configEntry.getClass().getMethod("setWithoutSaving", Object.class);
         return new ReflectiveBooleanConfigEntry(configEntry, getter, setter);
      }
   }

   private record ReflectiveBooleanConfigEntry(Object configEntry, Method getter, Method setter) implements BooleanConfigEntry {
      @Override
      public boolean get() throws ReflectiveOperationException {
         Object value = this.getter.invoke(this.configEntry);
         if (value instanceof Boolean booleanValue) {
            return booleanValue;
         }
         throw new IllegalStateException("Distant Horizons config entry is not boolean");
      }

      @Override
      public void setWithoutSaving(boolean value) throws ReflectiveOperationException {
         this.setter.invoke(this.configEntry, value);
      }
   }

   private record ConfigKey(String ownerClassName, String fieldName, boolean overrideValue, String appliedLogMessage) {
   }

   private record RuntimeBooleanOverride(ConfigKey configKey, BooleanConfigEntry configEntry, boolean previousValue) {
   }
}
