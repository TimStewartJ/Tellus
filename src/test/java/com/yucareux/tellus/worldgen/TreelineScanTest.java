package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/lod-scan/run-treeline.sh}): the latitude curve and the
 * local treeline from the Köppen tundra border at mountain ranges with well-known treelines.
 *
 * <p>{@code TELLUS_TREELINE_SCAN="gameDir"}. Missing elevation tiles are downloaded as in game.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_TREELINE_SCAN", matches = ".+")
class TreelineScanTest {
   // Name, latitude, longitude and an approximate observed treeline (m) from the literature.
   private static final Object[][] PLACES = {
      {"Colorado Front Range", 40.255, -105.615, 3500},
      {"Sierra Nevada, Whitney", 36.578, -118.292, 3400},
      {"Grand Teton", 43.741, -110.802, 3000},
      {"Banff", 51.2, -115.6, 2200},
      {"Mt Washington", 44.271, -71.303, 1400},
      {"Denali", 63.07, -151.0, 800},
      {"Mauna Kea", 19.82, -155.468, 2900},
      {"Cordillera Blanca", -9.12, -77.6, 3900},
      {"Japanese N. Alps", 36.342, 137.648, 2500},
      {"Everest, Khumbu", 27.988, 86.925, 4000},
      {"Gongga Shan", 29.596, 101.879, 4300},
      {"Kilimanjaro", -3.067, 37.355, 3000},
      {"Valais, Zermatt", 46.02, 7.75, 2350},
      {"Northern Limestone Alps", 47.555, 12.92, 1750},
      {"Pyrenees", 42.63, 0.65, 2300},
      {"Tatras", 49.18, 20.08, 1500},
      {"Caucasus, Elbrus", 43.35, 42.44, 2500},
      {"Jotunheimen", 61.636, 8.312, 1100},
      {"Geiranger", 62.101, 7.206, 800},
      {"Norway outer coast", 62.2, 5.6, 400},
      {"Abisko", 68.35, 18.8, 650},
      {"Cairngorms", 57.08, -3.67, 650},
      {"Iceland, Vatnajokull", 64.4, -16.8, 500},
      {"Southern Alps, Aoraki", -43.595, 170.142, 1250},
      {"Tasmania, Cradle Mt", -41.684, 145.951, 1150},
   };

   @Test
   void scan() {
      String gameDir = System.getenv("TELLUS_TREELINE_SCAN");
      System.setProperty("tellus.gameDir", Path.of(gameDir).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(gameDir).resolve("config").toAbsolutePath().toString());
      JsonObject json = new JsonObject();
      json.addProperty("world_scale", 30.0);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      LocalTreeline treeline = LocalTreeline.forSettings(settings);
      double curveError = 0.0, localError = 0.0;
      for (Object[] place : PLACES) {
         double latitude = (double)place[1], longitude = (double)place[2];
         int observed = (int)place[3];
         long started = System.nanoTime();
         double local = treeline.treelineMeters(latitude, longitude);
         double seconds = (System.nanoTime() - started) / 1e9;
         double curve = MountainElevationZones.treelineMeters(latitude);
         curveError += Math.abs(curve - observed);
         localError += Math.abs(local - observed);
         System.out.printf(Locale.ROOT, "TREELINE %-24s observed %5d  curve %5.0f (%+5.0f)  local %5.0f (%+5.0f)  %.2fs%n",
            place[0], observed, curve, curve - observed, local, local - observed, seconds);
      }
      System.out.printf(Locale.ROOT, "TREELINE mean absolute error: curve %.0f m, local %.0f m%n", curveError / PLACES.length, localError / PLACES.length);
      timeSweep(settings);
   }

   /** Cost of building every tile over Scandinavia, with elevation calls counted and timed apart. */
   private static void timeSweep(EarthGeneratorSettings settings) {
      TellusKoppenSource koppen = TellusWorldgenSources.koppen();
      TellusElevationSource source = TellusWorldgenSources.elevation();
      double worldScale = settings.worldScale();
      AtomicLong calls = new AtomicLong(), nanos = new AtomicLong();
      LocalTreeline treeline = new LocalTreeline(
         new LocalTreeline.ClimateRaster() {
            @Override
            public TellusKoppenSource.PixelGrid grid() {
               return koppen.pixelGrid();
            }

            @Override
            public int classIndex(int pixelX, int pixelY) {
               return koppen.classIndexAtPixel(pixelX, pixelY);
            }

            @Override
            public void readWindow(int pixelX, int pixelY, int width, int height, byte[] out) {
               koppen.readClassWindow(pixelX, pixelY, width, height, out);
            }
         },
         (latitude, longitude) -> {
            long started = System.nanoTime();
            double meters = source.samplePreviewElevationMeters(
               WorldProjection.global(worldScale).lonToBlockX(longitude), WorldProjection.global(worldScale).latToBlockZ(latitude),
               WorldProjection.global(worldScale), false, settings.demSelection(), LocalTreeline.ELEVATION_RESOLUTION_METERS
            );
            nanos.addAndGet(System.nanoTime() - started);
            calls.incrementAndGet();
            return meters;
         }
      );
      for (int round = 0; round < 2; round++) {
         calls.set(0);
         nanos.set(0);
         long started = System.nanoTime();
         int local = 0, tiles = 0;
         for (int latitude = 58; latitude < 71; latitude++) {
            for (int longitude = 4; longitude < 32; longitude++) {
               tiles++;
               if (treeline.treelineMeters(latitude + 0.5, longitude + 0.5) != MountainElevationZones.treelineMeters(latitude + 0.5)) {
                  local++;
               }
            }
         }
         double seconds = (System.nanoTime() - started) / 1e9;
         System.out.printf(Locale.ROOT, "TREELINE_SWEEP round=%d tiles=%d local=%d seconds=%.2f elevationCalls=%d elevationSeconds=%.2f%n",
            round, tiles, local, seconds, calls.get(), nanos.get() / 1e9);
      }
   }
}
