package com.yucareux.tellus.world.data.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yucareux.tellus.world.data.resolve.ResolveBiome;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.ResolveRealm;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResolveMountainSnowTest {
   @BeforeAll
   static void bootstrapMinecraft() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @ParameterizedTest
   @ValueSource(strings = {"Dfc", "Dfd", "Dwc", "Dwd", "Dsc", "Dsd"})
   void temperateMountainForestsDoNotBecomePermanentlySnowy(String climate) {
      assertEquals(
         Biomes.TAIGA,
         ResolveBiomeClassification.findBiomeKey(10, climate, region(ResolveBiome.TEMPERATE_CONIFER_FORESTS))
      );
   }

   @ParameterizedTest
   @ValueSource(strings = {
      "Af", "Am", "Aw", "BWh", "BWk", "BSh", "BSk",
      "Csa", "Csb", "Csc", "Cwa", "Cwb", "Cwc", "Cfa", "Cfb", "Cfc",
      "Dsa", "Dsb", "Dsc", "Dsd", "Dwa", "Dwb", "Dwc", "Dwd", "Dfa", "Dfb", "Dfc", "Dfd"
   })
   void nonPolarMontaneTreeCoverDoesNotImplySnow(String climate) {
      assertEquals(
         Biomes.WINDSWEPT_FOREST,
         ResolveBiomeClassification.findBiomeKey(10, climate, region(ResolveBiome.MONTANE_GRASSLANDS_SHRUBLANDS))
      );
   }

   @Test
   void missingMontaneClimateDoesNotInventSnow() {
      for (String climate : new String[]{null, "", " "}) {
         assertEquals(
            Biomes.WINDSWEPT_FOREST,
            ResolveBiomeClassification.findBiomeKey(10, climate, region(ResolveBiome.MONTANE_GRASSLANDS_SHRUBLANDS))
         );
      }
   }

   @ParameterizedTest
   @ValueSource(strings = {"ET", "EF"})
   void polarMontaneForestsStillUseSnowyGroves(String climate) {
      assertEquals(
         Biomes.GROVE,
         ResolveBiomeClassification.findBiomeKey(10, climate, region(ResolveBiome.MONTANE_GRASSLANDS_SHRUBLANDS))
      );
   }

   @ParameterizedTest
   @ValueSource(strings = {"Dfc", "Dfd", "Dwc", "Dwd", "Dsc", "Dsd"})
   void coldBorealForestsKeepTheirSnowyTaiga(String climate) {
      assertEquals(
         Biomes.SNOWY_TAIGA,
         ResolveBiomeClassification.findBiomeKey(10, climate, region(ResolveBiome.BOREAL_FORESTS_TAIGA))
      );
   }

   @Test
   void mappedSnowAndIceStillUseFrozenPeaksThroughTheLegacyFallback() {
      for (ResolveBiome biome : new ResolveBiome[]{
         ResolveBiome.TEMPERATE_CONIFER_FORESTS, ResolveBiome.MONTANE_GRASSLANDS_SHRUBLANDS
      }) {
         assertNull(ResolveBiomeClassification.findBiomeKey(70, "Cfb", region(biome)));
         assertEquals(Biomes.FROZEN_PEAKS, BiomeClassification.findBiomeKey(70, "Cfb"));
      }
   }

   private static ResolveEcoregion region(ResolveBiome biome) {
      return new ResolveEcoregion(
         1, "Mountain snow regression", biome, biome.displayName(), ResolveRealm.PALEARCTIC, "Palearctic", "CC-BY 4.0"
      );
   }
}
