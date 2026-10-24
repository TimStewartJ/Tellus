package com.yucareux.tellus.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Adapts vanilla generation entry points without duplicating Tellus terrain logic. */
public abstract class TellusChunkGeneratorBridge extends ChunkGenerator {
   public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
            EarthGeneratorSettings.CODEC.fieldOf("settings").forGetter(EarthChunkGenerator::settings)
         )
         .apply(instance, EarthChunkGenerator::new)
   );

   protected TellusChunkGeneratorBridge(BiomeSource biomes, Function<Holder<Biome>, BiomeGenerationSettings> settings) {
      super(biomes, settings);
   }

   @Override
   protected MapCodec<? extends ChunkGenerator> codec() {
      return Objects.requireNonNull(CODEC, "CODEC");
   }

   protected static Structure trialChambers(Registry<Structure> registry) {
      return registry.getValueOrThrow(BuiltinStructures.TRIAL_CHAMBERS);
   }

   protected ChunkGeneratorStructureState createStructureState(RandomState random, long seed, HolderLookup<StructureSet> structures) {
      return ChunkGeneratorStructureState.createForNormal(random, seed, this.biomeSource, structures);
   }

   protected abstract void addTellusDebugInfo(List<String> info, RandomState random, BlockPos pos);

   @Override
   public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
      this.addTellusDebugInfo(info, random, pos);
   }
}
