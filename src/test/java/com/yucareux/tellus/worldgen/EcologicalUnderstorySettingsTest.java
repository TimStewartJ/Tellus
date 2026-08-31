package com.yucareux.tellus.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcologicalUnderstorySettingsTest {
   @Test
   void understoryIsOnForNewWorldsAndWorldsSavedBeforeTheSettingExisted() {
      EarthGeneratorSettings decoded = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}"))
      );

      assertTrue(EarthGeneratorSettings.DEFAULT.ecologicalUnderstory());
      assertTrue(decoded.ecologicalUnderstory());
      assertTrue(decoded.customTrees(), "the understory toggle must not affect canopy trees");
   }

   @Test
   void understoryRoundTripsIndependentlyOfCustomTrees() {
      EarthGeneratorSettings decoded = requireSuccess(
         EarthGeneratorSettings.CODEC.parse(
            JsonOps.INSTANCE,
            JsonParser.parseString("{\"custom_trees\":true,\"ecological_understory\":false}")
         )
      );
      JsonElement encoded = requireSuccess(
         EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, decoded)
      );
      JsonObject encodedObject = encoded.getAsJsonObject();

      assertFalse(decoded.ecologicalUnderstory());
      assertTrue(decoded.customTrees());
      assertFalse(encodedObject.get("ecological_understory").getAsBoolean());
      assertTrue(decoded.withEcologicalUnderstory(true).ecologicalUnderstory());
      assertFalse(decoded.withEcologicalUnderstory(true).withCustomTrees(false).customTrees());
      assertTrue(decoded.withEcologicalUnderstory(true).withCustomTrees(false).ecologicalUnderstory());
   }

   private static <T> T requireSuccess(DataResult<T> result) {
      Optional<T> value = result.resultOrPartial(message -> {
         throw new AssertionError(message);
      });
      assertTrue(value.isPresent());
      return value.get();
   }
}
