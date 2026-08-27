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
      Boolean.TRUE,
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
      Boolean.TRUE,
      "Enabled Distant Horizons lower-detail LOD upsampling so coarse Tellus LODs stay visible while finer tiles generate"
   );
   private static final boolean FORCE_UPSAMPLE_TO_FILL_HOLES = Boolean.parseBoolean(
      System.getProperty("tellus.dhForceUpsampleToFillHoles", "true")
   );
   /**
    * The Tellus DH fork keeps a coarse LOD rendering until its children hold real data, which
    * removes the hole without the upsample cascade (that cascade writes every descendant section
    * down to block detail into the database). When that entry exists and is on, upsampling is
    * left at the user's setting.
    */
   private static final ConfigKey FORK_KEEP_LOWER_DETAIL = new ConfigKey(
      "com.seibel.distanthorizons.core.config.Config$Common$LodBuilding$Experimental",
      "keepLowerDetailLodsUntilChildrenHaveData",
      Boolean.TRUE,
      "Distant Horizons fork keeps coarse LODs until children have data"
   );
   /**
    * Upstream DH pauses its world-gen and update-propagator threads while the camera averages more
    * than 20 blocks/s. Tellus LOD tiles now build in milliseconds, so the Tellus DH fork exposes the
    * threshold and Tellus disables the pause here (0); stock DH lacks the entry and is left alone.
    * Negative property values disable the override, positive values pause above that speed.
    */
   private static final double WORLD_GEN_PAUSE_SPEED = doubleProperty("tellus.dhWorldGenPauseSpeed", 0.0);
   private static final ConfigKey WORLD_GEN_PAUSE_SPEED_KEY = new ConfigKey(
      "com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator",
      "pauseGenerationAboveCameraSpeed",
      WORLD_GEN_PAUSE_SPEED,
      WORLD_GEN_PAUSE_SPEED > 0.0
         ? "Raised the Distant Horizons world-gen pause speed to " + WORLD_GEN_PAUSE_SPEED + " blocks/s so Tellus LODs keep generating while flying"
         : "Disabled the Distant Horizons world-gen camera-speed pause so Tellus LODs keep generating while flying"
   );
   private final Object lock = new Object();
   private final boolean forceNSizedGeneration;
   private final ConfigEntryResolver configEntryResolver;
   private final Set<String> activeDimensions = new HashSet<>();
   private RuntimeOverride nSizedGenerationOverride;
   private RuntimeOverride upsampleOverride;
   private RuntimeOverride worldGenPauseSpeedOverride;

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
               if (FORCE_UPSAMPLE_TO_FILL_HOLES && !this.forkKeepsLowerDetailLods()) {
                  this.upsampleOverride = this.tryApply(UPSAMPLE_TO_FILL_HOLES);
               }
            }
            if (WORLD_GEN_PAUSE_SPEED >= 0.0) {
               this.worldGenPauseSpeedOverride = this.tryApply(WORLD_GEN_PAUSE_SPEED_KEY);
            }
         }
         return true;
      }
   }

   /** True when the Tellus DH fork's keep-parent rule is present and enabled. */
   private boolean forkKeepsLowerDetailLods() {
      try {
         ConfigEntryHandle entry = this.configEntryResolver.resolve(
            FORK_KEEP_LOWER_DETAIL.ownerClassName(), FORK_KEEP_LOWER_DETAIL.fieldName()
         );
         boolean enabled = Boolean.TRUE.equals(entry.get());
         if (enabled) {
            LOGGER.info("{}; leaving upsampleLowerDetailLodsToFillHoles at the user's setting", FORK_KEEP_LOWER_DETAIL.appliedLogMessage());
         }
         return enabled;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
         return false;
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

         this.restore(this.worldGenPauseSpeedOverride);
         this.worldGenPauseSpeedOverride = null;
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

   private RuntimeOverride tryApply(ConfigKey configKey) {
      try {
         ConfigEntryHandle configEntry = this.configEntryResolver.resolve(configKey.ownerClassName(), configKey.fieldName());
         Object previousValue = configEntry.get();
         if (Objects.equals(previousValue, configKey.overrideValue())) {
            return null;
         }
         if (previousValue != null && previousValue.getClass() != configKey.overrideValue().getClass()) {
            throw new IllegalStateException(
               "Distant Horizons config entry holds " + previousValue.getClass().getSimpleName()
                  + ", expected " + configKey.overrideValue().getClass().getSimpleName()
            );
         }

         configEntry.setWithoutSaving(configKey.overrideValue());
         LOGGER.info("{} (runtime only; config unchanged)", configKey.appliedLogMessage());
         return new RuntimeOverride(configKey, configEntry, previousValue);
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

   private void restore(RuntimeOverride runtimeOverride) {
      if (runtimeOverride == null) {
         return;
      }

      try {
         runtimeOverride.configEntry().setWithoutSaving(runtimeOverride.previousValue());
         if (!Objects.equals(runtimeOverride.previousValue(), runtimeOverride.configKey().overrideValue())) {
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

   private static double doubleProperty(String key, double defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException error) {
         LOGGER.warn("Invalid value '{}' for {}, using {}", value, key, defaultValue);
         return defaultValue;
      }
   }

   interface ConfigEntryResolver {
      ConfigEntryHandle resolve(String ownerClassName, String fieldName) throws ReflectiveOperationException;
   }

   /** A DH {@code ConfigEntry<T>} seen through its {@code get()} / {@code setWithoutSaving(T)} methods. */
   interface ConfigEntryHandle {
      Object get() throws ReflectiveOperationException;

      void setWithoutSaving(Object value) throws ReflectiveOperationException;
   }

   private enum ReflectiveConfigEntryResolver implements ConfigEntryResolver {
      INSTANCE;

      @Override
      public ConfigEntryHandle resolve(String ownerClassName, String fieldName) throws ReflectiveOperationException {
         Class<?> configOwner = Class.forName(ownerClassName);
         Object configEntry = configOwner.getField(fieldName).get(null);
         Method getter = configEntry.getClass().getMethod("get");
         Method setter = configEntry.getClass().getMethod("setWithoutSaving", Object.class);
         return new ReflectiveConfigEntryHandle(configEntry, getter, setter);
      }
   }

   private record ReflectiveConfigEntryHandle(Object configEntry, Method getter, Method setter) implements ConfigEntryHandle {
      @Override
      public Object get() throws ReflectiveOperationException {
         return this.getter.invoke(this.configEntry);
      }

      @Override
      public void setWithoutSaving(Object value) throws ReflectiveOperationException {
         this.setter.invoke(this.configEntry, value);
      }
   }

   private record ConfigKey(String ownerClassName, String fieldName, Object overrideValue, String appliedLogMessage) {
   }

   private record RuntimeOverride(ConfigKey configKey, ConfigEntryHandle configEntry, Object previousValue) {
   }
}
