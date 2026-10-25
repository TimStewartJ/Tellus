package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/river-scan/run-lod-water.sh}): the water Distant Horizons LODs draw over an
 * area, at several LOD cell sizes, through the same resolver and terrain sampling the LOD generator uses. Each size
 * gets an oblique view (heights scaled by the cell size, so slopes keep their proportions) and a top-down view.
 *
 * <p>{@code TELLUS_LOD_WATER_SCAN="label|gameDir|lat|lon|scale|radiusBlocks|cellSizes"} (cell sizes comma
 * separated); images go to {@code TELLUS_RIVER_SCAN_OUT}/label.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_LOD_WATER_SCAN", matches = ".+")
class LodWaterScanTest {
   private static final int SECTION = 64;

   @Test
   void scan() throws Exception {
      String[] spec = System.getenv("TELLUS_LOD_WATER_SCAN").split("\\|");
      String label = spec[0];
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      double lat = Double.parseDouble(spec[2]), lon = Double.parseDouble(spec[3]), scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]);
      String[] sizes = (spec.length > 6 ? spec[6] : "2,4,8,16").split(",");
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_RIVER_SCAN_OUT", "build/river-scan")).resolve(label);
      Files.createDirectories(out);

      Thread.currentThread().setContextClassLoader(LodWaterScanTest.class.getClassLoader());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      var biomes = com.yucareux.tellus.test.MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      JsonObject json = new JsonObject();
      json.addProperty("spawn_latitude", lat);
      json.addProperty("spawn_longitude", lon);
      json.addProperty("world_scale", scale);
      // Increase Height needs the game's packed-coordinate patches, which plain JUnit lacks.
      json.addProperty("experimental_increase_height", false);
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      EarthChunkGenerator generator = new EarthChunkGenerator(new FixedBiomeSource(biomes.getOrThrow(Biomes.PLAINS)), settings);
      DhLodWaterResolver water = new DhLodWaterResolver(generator);
      int centerX = (int)Math.floor(WorldProjection.global(scale).lonToBlockX(lon));
      int centerZ = (int)Math.floor(WorldProjection.global(scale).latToBlockZ(lat));
      for (String text : sizes) {
         int cell = Integer.parseInt(text.trim());
         int span = SECTION * cell;
         int sections = Math.max(1, (2 * radius + span - 1) / span);
         int minX = Math.floorDiv(centerX - radius, span) * span, minZ = Math.floorDiv(centerZ - radius, span) * span;
         int size = sections * SECTION;
         int[] terrain = new int[size * size], surface = new int[size * size];
         byte[] kind = new byte[size * size];
         double resolution = Math.max(scale, scale * cell);
         long started = System.nanoTime();
         IntStream.range(0, sections * sections).parallel().forEach(s -> {
            int baseX = minX + (s % sections) * span, baseZ = minZ + (s / sections) * span;
            int[] xs = new int[SECTION], zs = new int[SECTION];
            for (int i = 0; i < SECTION; i++) { xs[i] = baseX + i * cell + (cell >> 1); zs[i] = baseZ + i * cell + (cell >> 1); }
            int[] base = new int[SECTION * SECTION], cover = new int[SECTION * SECTION];
            for (int z = 0; z < SECTION; z++) for (int x = 0; x < SECTION; x++) {
               int index = z * SECTION + x;
               cover[index] = generator.sampleCoverClass(xs[x], zs[z], resolution);
               base[index] = generator.resolveLodTerrainSurface(xs[x], zs[z], cover[index], resolution);
            }
            generator.repairLodTerrainSurfaceGrid(base, cover, SECTION);
            DhLodWaterResolver.AreaResult result = water.resolveArea(baseX, baseZ, SECTION, cell, xs, zs, base, cover, cell <= 32);
            for (int z = 0; z < SECTION; z++) for (int x = 0; x < SECTION; x++) {
               int index = z * SECTION + x;
               int target = ((s / sections) * SECTION + z) * size + (s % sections) * SECTION + x;
               terrain[target] = result.terrainSurface()[index];
               surface[target] = result.waterSurface()[index];
               kind[target] = !result.hasWater()[index] ? WaterRender3d.DRY : result.ocean()[index] ? WaterRender3d.OCEAN
                  : result.falling()[index] ? WaterRender3d.FALLING : WaterRender3d.WET;
            }
         });
         double seconds = (System.nanoTime() - started) / 1e9;
         int wet = 0;
         for (byte k : kind) if (k != WaterRender3d.DRY) wet++;
         System.out.printf(Locale.ROOT, "LODWATER cell=%d sections=%d wet=%d seconds=%.1f%n", cell, sections * sections, wet, seconds);
         // Heights in cells, so the view keeps the terrain's proportions.
         int[] t = new int[size * size], w = new int[size * size];
         for (int i = 0; i < size * size; i++) { t[i] = Math.floorDiv(terrain[i], cell); w[i] = Math.floorDiv(surface[i], cell); }
         ImageIO.write(WaterRender3d.render(size, t, w, kind, 0, 0, size, 0.0, 45.0, 1400.0 / size / Math.sqrt(2.0)), "png",
            out.resolve("lod-" + cell + ".png").toFile());
         BufferedImage top = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
         int[] bands = {0x1F4FD0, 0x2A8C9E, 0x3A6FE0, 0x1E9E6A, 0x5A50C8, 0x2E7FB8};
         for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int i = z * size + x;
            int shade = Math.max(40, Math.min(220, 120 + (terrain[Math.max(0, x - 1) + z * size] - terrain[i]) * 6));
            top.setRGB(x, z, kind[i] == WaterRender3d.DRY ? shade << 8 | shade / 3 << 16 : kind[i] == WaterRender3d.FALLING ? 0xFFFFFF
               : kind[i] == WaterRender3d.OCEAN ? 0x1A3A8A : bands[Math.floorMod(surface[i], bands.length)]);
         }
         ImageIO.write(top, "png", out.resolve("lod-top-" + cell + ".png").toFile());
      }
   }
}
