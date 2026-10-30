package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DappledForestTest {
   @TempDir
   static Path gameDirectory;

   private static HolderLookup<Biome> biomes;
   private static String previousGameDir;
   private static String previousConfigDir;
   private static ClassLoader previousContextClassLoader;

   @BeforeAll
   static void bootstrapMinecraft() {
      previousGameDir = System.getProperty("tellus.gameDir");
      previousConfigDir = System.getProperty("tellus.configDir");
      previousContextClassLoader = Thread.currentThread().getContextClassLoader();
      // NeoForge's JUnit runner has a separate loader; find biome providers through the fixture's.
      Thread.currentThread().setContextClassLoader(DappledForestTest.class.getClassLoader());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      biomes = com.yucareux.tellus.test.MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      System.setProperty("tellus.gameDir", gameDirectory.toString());
      System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
   }

   @AfterAll
   static void restoreTestEnvironment() {
      Thread.currentThread().setContextClassLoader(previousContextClassLoader);
      restoreProperty("tellus.gameDir", previousGameDir);
      restoreProperty("tellus.configDir", previousConfigDir);
   }

   @Test
   void mixesThroughCoolContinentalForestOnly() {
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
         EarthBiomeSource source = new EarthBiomeSource(biomes, settings);
         WaterSurfaceResolver.WaterColumnData dryLand = new WaterSurfaceResolver.WaterColumnData(false, false, 320, 320);
         // Vermont's mixed forest, where RESOLVE classifies tree cover as forest.
         int originX = (int)Math.round(-72.7 * EarthProjection.blocksPerDegree(settings.worldScale()));
         int originZ = (int)Math.round(EarthProjection.latToBlockZ(44.0, settings.worldScale()));
         int tree = MountainSurfaceRules.ESA_TREE_COVER;
         int dappled = 0;
         int samples = 0;
         for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
               int x = originX + i * 128;
               int z = originZ + j * 128;
               samples++;
               if (source.getBiomeAtBlock(x, z, tree, tree, dryLand, "Dfb").is(Biomes.DAPPLED_FOREST)) {
                  dappled++;
               }
               assertFalse(source.getBiomeAtBlock(x, z, tree, tree, dryLand, "Cfb").is(Biomes.DAPPLED_FOREST));
               assertFalse(source.getBiomeAtBlock(x, z, MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_GRASSLAND, dryLand, "Dfb").is(Biomes.DAPPLED_FOREST));
            }
         }

         double share = dappled / (double)samples;
         assertTrue(share > 0.45 && share < 0.75, "Dappled Forest share " + share);

         // Clumps of a few trees: every stretch of this forest mixes green and autumn crowns.
         for (int window = 0; window < 8; window++) {
            boolean autumn = false;
            boolean green = false;
            for (int dx = 0; dx < 96; dx += 8) {
               for (int dz = 0; dz < 96; dz += 8) {
                  int x = originX + window * 320 + dx;
                  int z = originZ + window * 160 + dz;
                  boolean dappledHere = source.getBiomeAtBlock(x, z, tree, tree, dryLand, "Dfb").is(Biomes.DAPPLED_FOREST);
                  autumn |= dappledHere;
                  green |= !dappledHere;
               }
            }
            assertTrue(autumn && green, "96-block window " + window + " is not mixed");
         }
         // Abandoned Camps only consider biomes the source declares.
         assertTrue(source.possibleBiomes().stream().anyMatch(biome -> biome.is(Biomes.DAPPLED_FOREST)));
      }
   }

   @Test
   void customTreesGrowRedOrangeAndYellowPoplars() {
      TellusCanopyHeightSource.CanopySample canopy = new TellusCanopyHeightSource.CanopySample(
         true, 18.0, 18.0, 18.0, 18.0, 18.0, 18.0, 13, 9
      );
      Set<Block> leaves = new HashSet<>();
      for (long seed = 0; seed < 200; seed++) {
         TellusProceduralTreeGenerator.TreePlan plan = TellusProceduralTreeGenerator.plan(
            Biomes.DAPPLED_FOREST, ResolveEcoregion.UNKNOWN, canopy, seed
         );
         assertEquals(TellusProceduralTreeGenerator.Profile.POPLAR, plan.profile());
         assertEquals(Blocks.POPLAR_LOG, TellusProceduralTreeGenerator.logState(plan.profile(), seed).getBlock());
         leaves.add(TellusProceduralTreeGenerator.leavesState(plan.profile(), seed).getBlock());
      }
      assertEquals(Set.of(Blocks.RED_POPLAR_LEAVES, Blocks.ORANGE_POPLAR_LEAVES, Blocks.YELLOW_POPLAR_LEAVES), leaves);
   }

   @Test
   void randomBiomesOfferTheDappledForest() {
      assertTrue(EarthGeneratorSettings.DEFAULT.randomBiomeIds().contains("dappled_forest"));
      assertTrue(
         new RandomBiomeMixer(biomes, EarthGeneratorSettings.DEFAULT).possibleBiomes().stream()
            .anyMatch(biome -> biome.is(Biomes.DAPPLED_FOREST))
      );
   }

   private static void restoreProperty(String key, String value) {
      if (value == null) {
         System.clearProperty(key);
      } else {
         System.setProperty(key, value);
      }
   }
}
