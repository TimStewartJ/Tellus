package com.yucareux.tellus.worldgen;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Gives a brand-new Tellus world its generator's height limits before its first server starts. The
 * tellus_dynamic_dimension pack only applies from the next load, so the first session would otherwise run on the
 * bundled tellus:earth heights and save chunks and Distant Horizons LODs against the wrong range.
 */
public final class NewWorldHeightAlignment {
   private NewWorldHeightAlignment() {
   }

   public static WorldStem align(WorldStem stem) {
      if (MinecraftVersionCompat.worldData(stem).overworldData().isInitialized()) {
         return stem;
      }

      LevelStem overworld = stem.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD).map(Holder::value).orElse(null);
      if (overworld == null || !(overworld.generator() instanceof EarthChunkGenerator generator)) {
         return stem;
      }

      ResourceKey<DimensionType> typeKey = overworld.type().unwrapKey().orElse(null);
      DimensionType type = overworld.type().value();
      EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(generator.settings());
      if (typeKey == null || type.minY() == limits.minY() && type.height() == limits.height() && type.logicalHeight() == limits.logicalHeight()) {
         return stem;
      }

      try {
         DimensionType updated = EarthGeneratorSettings.applyHeightLimits(type, limits);
         LayeredRegistryAccess<RegistryLayer> registries = copyRegistry(stem.registries(), Registries.DIMENSION_TYPE, (key, value) -> key.equals(typeKey) ? updated : value);
         HolderGetter<DimensionType> dimensionTypes = registries.compositeAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
         // Level stems hold dimension type holders, and the old ones are not valid in the new registries when saved.
         UnaryOperator<LevelStem> rebind = levelStem -> new LevelStem(
            levelStem.type().unwrapKey().<Holder<DimensionType>>map(dimensionTypes::getOrThrow).orElse(levelStem.type()), levelStem.generator()
         );
         registries = copyRegistry(registries, Registries.LEVEL_STEM, (key, value) -> rebind.apply(value));
         Tellus.LOGGER.info("Applied Tellus height limits to the new world before its first start: minY={}, height={}", limits.minY(), limits.height());
         return MinecraftVersionCompat.withRegistries(stem, registries, rebind);
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Failed to apply Tellus height limits to the new world; they apply after a restart", error);
         return stem;
      }
   }

   private static <T> LayeredRegistryAccess<RegistryLayer> copyRegistry(
      LayeredRegistryAccess<RegistryLayer> registries, ResourceKey<? extends Registry<T>> registryKey, BiFunction<ResourceKey<T>, T, T> values
   ) {
      LayeredRegistryAccess<RegistryLayer> updated = registries;
      for (RegistryLayer layer : RegistryLayer.values()) {
         RegistryAccess.Frozen layerAccess = updated.getLayer(layer);
         if (layerAccess.lookup(registryKey).isEmpty()) {
            continue;
         }

         Registry<T> source = MinecraftVersionCompat.registry(layerAccess, registryKey);
         MappedRegistry<T> copy = new MappedRegistry<>(registryKey, source instanceof MappedRegistry<T> mapped ? mapped.registryLifecycle() : Lifecycle.stable());
         List<Entry<ResourceKey<T>, T>> entries = new ArrayList<>(source.entrySet());
         entries.sort(Comparator.comparingInt(entry -> source.getId(entry.getValue())));
         for (Entry<ResourceKey<T>, T> entry : entries) {
            // Built-in registration info also drops the known pack, so clients receive the changed entry instead of their bundled copy.
            Registry.register(copy, entry.getKey(), values.apply(entry.getKey(), entry.getValue()));
         }

         Map<ResourceKey<? extends Registry<?>>, Registry<?>> layerRegistries = new LinkedHashMap<>();
         layerAccess.registries().forEach(registry -> layerRegistries.put(registry.key(), registry.value()));
         layerRegistries.put(registryKey, copy.freeze());
         List<RegistryAccess.Frozen> replacement = new ArrayList<>();
         replacement.add(new RegistryAccess.ImmutableRegistryAccess(layerRegistries).freeze());
         for (RegistryLayer later : RegistryLayer.values()) {
            if (later.ordinal() > layer.ordinal()) {
               replacement.add(updated.getLayer(later));
            }
         }
         updated = updated.replaceFrom(layer, replacement);
      }

      return updated;
   }
}
