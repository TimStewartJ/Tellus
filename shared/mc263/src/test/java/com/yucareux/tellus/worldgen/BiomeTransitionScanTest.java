package com.yucareux.tellus.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/biome-scan}): resolves the land cover, biome, top block and
 * tree anchors of every block column of an area the way chunk generation does, from a Tellus cache
 * directory, renders them and measures how artificial the biome and material borders look.
 *
 * <ul>
 *   <li>{@code EDGES}: border length between surface groups, the share in straight axis-aligned
 *       runs of 8+ blocks, and the same after a 7x7 majority filter (macro straightness, which
 *       ignores fine dither).</li>
 *   <li>{@code SPECKS}: connected red-sand/terracotta and badlands-biome patches by size.</li>
 * </ul>
 *
 * <p>{@code TELLUS_BIOME_SCAN="label|gameDir|lat|lon|scale|radiusBlocks"}; images go to
 * {@code TELLUS_BIOME_SCAN_OUT}/label. Missing tiles are downloaded as in game.</p>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_BIOME_SCAN", matches = ".+")
class BiomeTransitionScanTest {
   private static final int MARGIN = 4;
   private static final byte G_WATER = 0, G_VEG = 1, G_STONE = 2, G_RED = 3, G_SAND = 4, G_SNOW = 5, G_SOIL = 6, G_OTHER = 7;
   private static final String[] GROUP_NAMES = {"water", "veg", "stone", "red", "sand", "snow", "soil", "other"};

   @Test
   void scan() throws Exception {
      // label|gameDir|lat|lon|scale|radius; label, lat and lon may be comma-separated lists of areas.
      String[] spec = System.getenv("TELLUS_BIOME_SCAN").split("\\|");
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      String[] labels = spec[0].split(","), lats = spec[2].split(","), lons = spec[3].split(",");
      double scale = Double.parseDouble(spec[4]);
      int radius = Integer.parseInt(spec[5]);
      Path root = Path.of(System.getenv().getOrDefault("TELLUS_BIOME_SCAN_OUT", "build/biome-scan"));

      Thread.currentThread().setContextClassLoader(BiomeTransitionScanTest.class.getClassLoader());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      HolderLookup<Biome> biomes = com.yucareux.tellus.test.MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      bindTags(biomes);
      initColors();
      for (int a = 0; a < labels.length; a++) {
         Path out = root.resolve(labels[a]);
         Files.createDirectories(out);
         scanArea(biomes, labels[a], Double.parseDouble(lats[a]), Double.parseDouble(lons[a]), scale, radius, out);
      }
   }

   private static void scanArea(HolderLookup<Biome> biomes, String label, double lat, double lon, double scale, int radius, Path out)
      throws IOException {
      JsonObject json = new JsonObject();
      json.addProperty("spawn_latitude", lat);
      json.addProperty("spawn_longitude", lon);
      json.addProperty("world_scale", scale);
      // Plain JUnit has no height mixins, so the generator only accepts the vanilla height range.
      EarthGeneratorSettings settings = EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
      EarthBiomeSource biomeSource = new EarthBiomeSource(biomes, settings);
      biomeSource.setFastSpawnMode(false);
      EarthChunkGenerator generator = new EarthChunkGenerator(biomeSource, settings);
      generator.setLodShorelineOverrideSuppressed(true);
      WaterSurfaceResolver water = TellusWorldgenSources.waterResolver(settings);
      TellusKoppenSource koppen = TellusWorldgenSources.koppen();

      int centerX = (int)Math.floor(WorldProjection.global(scale).lonToBlockX(lon));
      int centerZ = (int)Math.floor(WorldProjection.global(scale).latToBlockZ(lat));
      int size = 2 * radius;
      int minX = centerX - radius, minZ = centerZ - radius;
      System.out.printf(Locale.ROOT, "BIOMESCAN_START label=%s lat=%.4f lon=%.4f scale=%.1f size=%d min=%d,%d%n",
         label, lat, lon, scale, size, minX, minZ);

      long started = System.nanoTime();
      Area area = new Area(size);
      int padded = size + 2 * MARGIN;
      int[] heights = new int[padded * padded];
      IntStream.range(0, padded).parallel().forEach(row -> {
         int z = minZ - MARGIN + row;
         for (int col = 0; col < padded; col++) {
            int x = minX - MARGIN + col;
            int raw = generator.sampleCoverClass(x, z);
            heights[row * padded + col] = water.resolveFastColumnData(x, z, raw).terrainSurface();
         }
      });
      Map<String, Integer> badlandsSources = new java.util.concurrent.ConcurrentHashMap<>();
      BadlandsRegionField region = new BadlandsRegionField(scale);
      IntStream.range(0, size).parallel().forEach(row -> {
         int z = minZ + row;
         for (int col = 0; col < size; col++) {
            int x = minX + col;
            int i = row * size + col;
            int raw = generator.sampleCoverClass(x, z);
            WaterSurfaceResolver.WaterColumnData column = water.resolveFastColumnData(x, z, raw);
            boolean wet = column.hasWater();
            int cover = generator.resolveDryOsmTerrainCoverClass(x, z, raw, wet);
            int visual = settings.enableWater() && raw == MountainSurfaceRules.ESA_WATER && !wet
               ? cover : generator.sampleVisualCoverClass(x, z, cover);
            int cellX = Math.floorDiv(x, 4) * 4 + 2, cellZ = Math.floorDiv(z, 4) * 4 + 2;
            String climate = koppen.sampleDitheredCode(cellX, cellZ, WorldProjection.global(scale));
            if (climate == null) climate = koppen.findNearestCode(cellX, cellZ, WorldProjection.global(scale));
            Holder<Biome> biome = biomeSource.getBiomeAtBlock(x, z, cover, visual, column, climate);
            if (biome.is(BiomeTags.IS_BADLANDS)) {
               String name = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
               badlandsSources.merge(name + "/cover" + visual + "/" + climate, 1, Integer::sum);
            }
            int surface = column.terrainSurface();
            int hp = (row + MARGIN) * padded + col + MARGIN;
            int east = heights[hp + 4], west = heights[hp - 4], north = heights[hp - 4 * padded], south = heights[hp + 4 * padded];
            int slope = Math.max(Math.max(Math.abs(east - surface), Math.abs(west - surface)), Math.max(Math.abs(north - surface), Math.abs(south - surface)));
            int convexity = (east + west + north + south) / 4 - surface;
            boolean underwater = wet && column.waterSurface() > surface;
            BlockState top = generator.resolveUltraFastLodSurface(
               biome, x, z, surface, underwater, cover, visual, slope, convexity, false, OsmQueryMode.NON_BLOCKING, false
            ).top();
            area.raw[i] = cover;
            area.visual[i] = visual;
            area.biome[i] = biome;
            area.top[i] = wet ? null : top;
            area.group[i] = wet ? G_WATER : group(top);
            area.climate[i] = climate;
            area.coherent[i] = koppen.sampleCoherentCode(x, z, WorldProjection.global(scale));
            area.relief[i] = (float)region.reliefMeters(x, z, Double.NaN, 1.0, (rx, rz, res) -> TellusWorldgenSources.elevation().sampleElevationMeters(rx, rz, WorldProjection.global(scale), false, settings.demSelection()));
         }
      });
      // Trees: one anchor per placement cell, kept where the tree rule accepts the cover there.
      int cell = TellusProceduralTreeGenerator.PLACEMENT_CELL_SIZE;
      for (int cz = Math.floorDiv(minZ, cell); cz <= Math.floorDiv(minZ + size - 1, cell); cz++) {
         for (int cx = Math.floorDiv(minX, cell); cx <= Math.floorDiv(minX + size - 1, cell); cx++) {
            TellusProceduralTreeGenerator.TreeAnchor anchor = TellusProceduralTreeGenerator.anchorForCell(cx, cz, 0L);
            int lx = anchor.worldX() - minX, lz = anchor.worldZ() - minZ;
            if (lx < 0 || lz < 0 || lx >= size || lz >= size) continue;
            int i = lz * size + lx;
            if (area.group[i] != G_WATER && treeCover(area, i)) area.trees.add(new int[]{lx, lz});
         }
      }
      double seconds = (System.nanoTime() - started) / 1e9;
      System.out.printf(Locale.ROOT, "BIOMESCAN_RESULT label=%s seconds=%.1f trees=%d%n", label, seconds, area.trees.size());

      reportShares(label, area);
      reportEdges(label, "surface", area.group, size);
      byte[] canopy = canopyMask(area);
      reportEdges(label, "canopy", canopy, size);
      reportSpecks(label, "redSurface", area, i -> area.group[i] == G_RED);
      reportSpecks(label, "badlandsBiome", area, i -> area.biome[i] != null && area.biome[i].is(BiomeTags.IS_BADLANDS));
      StringBuilder sources = new StringBuilder();
      badlandsSources.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(10)
         .forEach(e -> sources.append(" ").append(e.getKey()).append("=").append(e.getValue()));
      System.out.printf(Locale.ROOT, "BIOMESCAN_BADLANDS_SOURCES label=%s%s%n", label, sources);

      ImageIO.write(renderSurface(area, canopy, 0, 0, size, 1), "png", out.resolve("surface.png").toFile());
      ImageIO.write(renderBiomes(area, 0, 0, size, 1), "png", out.resolve("biomes.png").toFile());
      ImageIO.write(renderCover(area, 0, 0, size, 1), "png", out.resolve("cover.png").toFile());
      ImageIO.write(renderClimate(area.climate, size), "png", out.resolve("climate.png").toFile());
      ImageIO.write(renderClimate(area.coherent, size), "png", out.resolve("climate-coherent.png").toFile());
      ImageIO.write(renderRelief(area.relief, size), "png", out.resolve("relief.png").toFile());
      int crop = Math.min(size, 320);
      int c0 = (size - crop) / 2;
      ImageIO.write(renderSurface(area, canopy, c0, c0, crop, 3), "png", out.resolve("surface-center.png").toFile());
      ImageIO.write(renderCover(area, c0, c0, crop, 3), "png", out.resolve("cover-center.png").toFile());
   }

   /** Whether the tree rule plants at this column: the land cover the generator hands it. */
   static boolean treeCover(Area area, int i) {
      return MountainSurfaceRules.resolveSurfaceCoverClass(area.raw[i], area.visual[i]) == MountainSurfaceRules.ESA_TREE_COVER;
   }

   private static final class Area {
      final int size;
      final int[] raw, visual;
      final byte[] group;
      final String[] climate, coherent;
      final float[] relief;
      final BlockState[] top;
      final Holder<Biome>[] biome;
      final List<int[]> trees = new java.util.ArrayList<>();

      @SuppressWarnings("unchecked")
      Area(int size) {
         this.size = size;
         int cells = size * size;
         raw = new int[cells]; visual = new int[cells]; group = new byte[cells];
         climate = new String[cells]; coherent = new String[cells]; relief = new float[cells];
         top = new BlockState[cells]; biome = new Holder[cells];
      }
   }

   private static byte group(BlockState top) {
      Block block = top.getBlock();
      if (block == Blocks.GRASS_BLOCK || block == Blocks.PODZOL || block == Blocks.MOSS_BLOCK || block == Blocks.MYCELIUM
         || block == Blocks.ROOTED_DIRT) return G_VEG;
      if (block == Blocks.COARSE_DIRT || block == Blocks.DIRT || block == Blocks.MUD || block == Blocks.PACKED_MUD) return G_SOIL;
      if (block == Blocks.RED_SAND || block == Blocks.RED_SANDSTONE || BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("terracotta")) return G_RED;
      if (block == Blocks.SAND || block == Blocks.SANDSTONE) return G_SAND;
      if (block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW || block == Blocks.PACKED_ICE || block == Blocks.ICE || block == Blocks.BLUE_ICE) return G_SNOW;
      if (block == Blocks.STONE || block == Blocks.GRAVEL || block == Blocks.ANDESITE || block == Blocks.DIORITE || block == Blocks.GRANITE
         || block == Blocks.COBBLESTONE || block == Blocks.DEEPSLATE || block == Blocks.TUFF || block == Blocks.CALCITE) return G_STONE;
      return G_OTHER;
   }

   private static void reportShares(String label, Area area) {
      int[] counts = new int[GROUP_NAMES.length];
      for (byte g : area.group) counts[g]++;
      StringBuilder groups = new StringBuilder();
      for (int g = 0; g < counts.length; g++) {
         if (counts[g] > 0) groups.append(String.format(Locale.ROOT, " %s=%.1f%%", GROUP_NAMES[g], 100.0 * counts[g] / area.group.length));
      }
      System.out.printf(Locale.ROOT, "BIOMESCAN_SHARES label=%s%s%n", label, groups);
      Map<String, Integer> byBiome = new HashMap<>();
      for (Holder<Biome> b : area.biome) {
         String key = b == null ? "?" : b.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
         byBiome.merge(key, 1, Integer::sum);
      }
      StringBuilder line = new StringBuilder();
      byBiome.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(14).forEach(e ->
         line.append(String.format(Locale.ROOT, " %s=%.1f%%", e.getKey(), 100.0 * e.getValue() / area.biome.length)));
      System.out.printf(Locale.ROOT, "BIOMESCAN_BIOMES label=%s%s%n", label, line);
      int[] coverCounts = new int[256];
      for (int c : area.visual) coverCounts[c & 255]++;
      StringBuilder covers = new StringBuilder();
      for (int c = 0; c < 256; c++) {
         if (coverCounts[c] > 0) covers.append(String.format(Locale.ROOT, " %d=%.1f%%", c, 100.0 * coverCounts[c] / area.visual.length));
      }
      System.out.printf(Locale.ROOT, "BIOMESCAN_COVER label=%s%s%n", label, covers);
   }

   /** Border length, and the share of it in straight axis-aligned runs, raw and after a majority filter. */
   private static void reportEdges(String label, String name, byte[] classes, int size) {
      EdgeStats raw = edges(classes, size);
      EdgeStats smooth = edges(majority(classes, size, 3), size);
      System.out.printf(Locale.ROOT,
         "BIOMESCAN_EDGES label=%s map=%s borderPer1k=%.1f straight8=%.1f%% straight16=%.1f%% | majority7: borderPer1k=%.1f straight8=%.1f%% straight16=%.1f%%%n",
         label, name, raw.perThousand(size), raw.share8(), raw.share16(), smooth.perThousand(size), smooth.share8(), smooth.share16());
   }

   private record EdgeStats(long edges, long run8, long run16) {
      double perThousand(int size) { return 1000.0 * edges / ((double)size * size); }
      double share8() { return edges == 0 ? 0 : 100.0 * run8 / edges; }
      double share16() { return edges == 0 ? 0 : 100.0 * run16 / edges; }
   }

   private static EdgeStats edges(byte[] classes, int size) {
      long edges = 0, run8 = 0, run16 = 0;
      // Vertical borders between x and x+1, runs along z; horizontal ones between z and z+1, runs along x.
      for (int pass = 0; pass < 2; pass++) {
         for (int a = 0; a < size - 1; a++) {
            int run = 0;
            for (int b = 0; b <= size; b++) {
               boolean border = false;
               if (b < size) {
                  int i = pass == 0 ? b * size + a : a * size + b;
                  int j = pass == 0 ? i + 1 : i + size;
                  border = classes[i] != classes[j];
               }
               if (border) {
                  run++;
                  edges++;
               } else if (run > 0) {
                  if (run >= 8) run8 += run;
                  if (run >= 16) run16 += run;
                  run = 0;
               }
            }
         }
      }
      return new EdgeStats(edges, run8, run16);
   }

   private static byte[] majority(byte[] classes, int size, int r) {
      byte[] result = new byte[classes.length];
      IntStream.range(0, size).parallel().forEach(z -> {
         int[] counts = new int[256];
         for (int x = 0; x < size; x++) {
            java.util.Arrays.fill(counts, 0);
            int best = classes[z * size + x] & 255, bestCount = 0;
            for (int dz = -r; dz <= r; dz++) {
               int zz = Math.clamp(z + dz, 0, size - 1);
               for (int dx = -r; dx <= r; dx++) {
                  int xx = Math.clamp(x + dx, 0, size - 1);
                  int c = classes[zz * size + xx] & 255;
                  if (++counts[c] > bestCount) {
                     bestCount = counts[c];
                     best = c;
                  }
               }
            }
            result[z * size + x] = (byte)best;
         }
      });
      return result;
   }

   private static byte[] canopyMask(Area area) {
      int size = area.size;
      byte[] mask = new byte[size * size];
      for (int[] tree : area.trees) {
         for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
               if (dx * dx + dz * dz > 10) continue;
               int x = tree[0] + dx, z = tree[1] + dz;
               if (x >= 0 && z >= 0 && x < size && z < size) mask[z * size + x] = 1;
            }
         }
      }
      return mask;
   }

   private static void reportSpecks(String label, String name, Area area, java.util.function.IntPredicate member) {
      int size = area.size;
      boolean[] seen = new boolean[size * size];
      long[] bucketArea = new long[6];
      int[] bucketCount = new int[6];
      int[] limits = {64, 256, 1024, 4096, 16384, Integer.MAX_VALUE};
      long total = 0;
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      for (int start = 0; start < seen.length; start++) {
         if (seen[start] || !member.test(start)) continue;
         int count = 0;
         seen[start] = true;
         queue.add(start);
         while (!queue.isEmpty()) {
            int i = queue.poll();
            count++;
            int x = i % size, z = i / size;
            int[] next = {x > 0 ? i - 1 : -1, x < size - 1 ? i + 1 : -1, z > 0 ? i - size : -1, z < size - 1 ? i + size : -1};
            for (int n : next) {
               if (n >= 0 && !seen[n] && member.test(n)) {
                  seen[n] = true;
                  queue.add(n);
               }
            }
         }
         total += count;
         for (int b = 0; b < limits.length; b++) {
            if (count < limits[b]) {
               bucketCount[b]++;
               bucketArea[b] += count;
               break;
            }
         }
      }
      StringBuilder line = new StringBuilder();
      String[] names = {"<64", "<256", "<1k", "<4k", "<16k", ">=16k"};
      for (int b = 0; b < names.length; b++) {
         line.append(String.format(Locale.ROOT, " %s:%d/%.1f%%", names[b], bucketCount[b], total == 0 ? 0 : 100.0 * bucketArea[b] / total));
      }
      System.out.printf(Locale.ROOT, "BIOMESCAN_SPECKS label=%s map=%s area=%.2f%% patches(count/areaShare):%s%n",
         label, name, 100.0 * total / ((double)size * size), line);
   }

   private static BufferedImage renderSurface(Area area, byte[] canopy, int x0, int z0, int span, int zoom) {
      BufferedImage image = new BufferedImage(span * zoom, span * zoom, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < span; z++) {
         for (int x = 0; x < span; x++) {
            int i = (z0 + z) * area.size + x0 + x;
            int rgb = area.group[i] == G_WATER ? 0x3F6FD8 : blockColor(area.top[i]);
            if (canopy[i] != 0) rgb = blend(rgb, foliageColor(area.biome[i]), 0.85);
            fill(image, x, z, zoom, rgb);
         }
      }
      return image;
   }

   private static BufferedImage renderBiomes(Area area, int x0, int z0, int span, int zoom) {
      BufferedImage image = new BufferedImage(span * zoom, span * zoom, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < span; z++) {
         for (int x = 0; x < span; x++) {
            fill(image, x, z, zoom, biomeColor(area.biome[(z0 + z) * area.size + x0 + x]));
         }
      }
      return image;
   }

   private static BufferedImage renderCover(Area area, int x0, int z0, int span, int zoom) {
      BufferedImage image = new BufferedImage(span * zoom, span * zoom, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < span; z++) {
         for (int x = 0; x < span; x++) {
            fill(image, x, z, zoom, coverColor(area.visual[(z0 + z) * area.size + x0 + x]));
         }
      }
      return image;
   }

   /** Relief in grey (black 0 m, white 600 m+), canyon-threshold side tinted red. */
   private static BufferedImage renderRelief(float[] relief, int size) {
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
      for (int i = 0; i < relief.length; i++) {
         float value = relief[i];
         int grey = Float.isFinite(value) ? Math.clamp((int)(value / 600.0F * 255.0F), 0, 255) : 0;
         int rgb = grey << 16 | grey << 8 | grey;
         if (Float.isFinite(value) && value >= BadlandsTerrainPolicy.MIN_CANYON_RELIEF_METERS) {
            rgb = blend(rgb, 0xC03020, 0.35);
         }
         image.setRGB(i % size, i / size, rgb);
      }
      return image;
   }

   private static BufferedImage renderClimate(String[] codes, int size) {
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
      for (int i = 0; i < codes.length; i++) {
         String code = codes[i] == null ? "" : codes[i];
         int rgb = switch (code) {
            case "Af" -> 0x0000FF;
            case "Am" -> 0x0078FF;
            case "Aw" -> 0x46AAFA;
            case "BWh" -> 0xFF0000;
            case "BWk" -> 0xFF9696;
            case "BSh" -> 0xF5A500;
            case "BSk" -> 0xFFDC64;
            case "Csa" -> 0xFFFF00;
            case "Csb" -> 0xC8C800;
            case "Cwa" -> 0x96FF96;
            case "Cwb" -> 0x64C864;
            case "Cwc" -> 0x329632;
            case "Cfa" -> 0xC8FF50;
            case "Cfb" -> 0x64FF50;
            case "" -> 0x000000;
            default -> (code.hashCode() & 0xFFFFFF) | 0x202020;
         };
         image.setRGB(i % size, i / size, rgb);
      }
      return image;
   }

   private static void fill(BufferedImage image, int x, int z, int zoom, int rgb) {
      for (int dz = 0; dz < zoom; dz++) {
         for (int dx = 0; dx < zoom; dx++) {
            image.setRGB(x * zoom + dx, z * zoom + dz, rgb);
         }
      }
   }

   private static int blend(int a, int b, double t) {
      int r = (int)(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
      int g = (int)(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
      int bl = (int)((a & 255) * (1 - t) + (b & 255) * t);
      return r << 16 | g << 8 | bl;
   }

   private static final Map<Block, Integer> BLOCK_COLORS = new IdentityHashMap<>();

   /** Called after bootstrap: block classes cannot initialise before it. */
   private static void initColors() {
      BLOCK_COLORS.put(Blocks.GRASS_BLOCK, 0x6E9E3A);
      BLOCK_COLORS.put(Blocks.PODZOL, 0x6B4A22);
      BLOCK_COLORS.put(Blocks.ROOTED_DIRT, 0x8E6A4C);
      BLOCK_COLORS.put(Blocks.MOSS_BLOCK, 0x5A7A2E);
      BLOCK_COLORS.put(Blocks.COARSE_DIRT, 0x7A5638);
      BLOCK_COLORS.put(Blocks.DIRT, 0x86603F);
      BLOCK_COLORS.put(Blocks.MUD, 0x3C3837);
      BLOCK_COLORS.put(Blocks.RED_SAND, 0xD06A22);
      BLOCK_COLORS.put(Blocks.TERRACOTTA, 0x985E43);
      BLOCK_COLORS.put(Blocks.SAND, 0xDBCF9E);
      BLOCK_COLORS.put(Blocks.SANDSTONE, 0xD8CB94);
      BLOCK_COLORS.put(Blocks.STONE, 0x7F7F7F);
      BLOCK_COLORS.put(Blocks.GRAVEL, 0x9C928F);
      BLOCK_COLORS.put(Blocks.ANDESITE, 0x888888);
      BLOCK_COLORS.put(Blocks.COBBLESTONE, 0x6E6E6E);
      BLOCK_COLORS.put(Blocks.DEEPSLATE, 0x505052);
      BLOCK_COLORS.put(Blocks.SNOW_BLOCK, 0xF4F8F8);
      BLOCK_COLORS.put(Blocks.CALCITE, 0xDDDEDA);
      BLOCK_COLORS.put(Blocks.TUFF, 0x6C6D66);
   }

   private static int blockColor(BlockState state) {
      if (state == null) return 0x3F6FD8;
      Integer color = BLOCK_COLORS.get(state.getBlock());
      if (color != null) return color;
      return state.getBlock().defaultMapColor().col;
   }

   private static int foliageColor(Holder<Biome> biome) {
      if (biome == null) return 0x2F6A1F;
      String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
      if (path.contains("taiga") || path.contains("windswept_forest") || path.contains("grove")) return 0x24442A;
      if (path.contains("dark_forest")) return 0x1F4A16;
      if (path.contains("birch")) return 0x6C9A4A;
      if (path.contains("savanna") || path.contains("badlands")) return 0x7E8F3A;
      if (path.contains("jungle")) return 0x2E8A1A;
      return 0x3D7A26;
   }

   private static int biomeColor(Holder<Biome> biome) {
      if (biome == null) return 0;
      String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
      return switch (path) {
         case "badlands" -> 0xD9571F;
         case "wooded_badlands" -> 0xB0702F;
         case "eroded_badlands" -> 0xFF6D2E;
         case "desert" -> 0xF2DD8C;
         case "savanna" -> 0xBDB25F;
         case "savanna_plateau" -> 0xA79D56;
         case "windswept_savanna" -> 0xE5DA87;
         case "stony_peaks" -> 0x8C8C8C;
         case "windswept_gravelly_hills" -> 0xB4ACA8;
         case "windswept_hills" -> 0x8AA07A;
         case "windswept_forest" -> 0x3E6B48;
         case "plains" -> 0x8DB360;
         case "sunflower_plains" -> 0xB5DB88;
         case "meadow" -> 0x83BB6D;
         case "forest" -> 0x056621;
         case "flower_forest" -> 0x2D8E49;
         case "birch_forest" -> 0x307444;
         case "old_growth_birch_forest" -> 0x589C6C;
         case "dark_forest" -> 0x40511A;
         case "taiga" -> 0x0B6659;
         case "old_growth_pine_taiga" -> 0x596651;
         case "old_growth_spruce_taiga" -> 0x818E79;
         case "sparse_jungle" -> 0x628B17;
         case "jungle" -> 0x537B09;
         case "bamboo_jungle" -> 0x768E14;
         case "swamp" -> 0x07F9B2;
         case "mangrove_swamp" -> 0x67C99D;
         case "river" -> 0x0000FF;
         case "frozen_peaks", "snowy_slopes", "jagged_peaks" -> 0xE0E8F0;
         default -> path.contains("ocean") ? 0x000070 : (path.hashCode() & 0xFFFFFF) | 0x404040;
      };
   }

   private static int coverColor(int cover) {
      return switch (cover) {
         case 10 -> 0x006400;
         case 20 -> 0xFFBB22;
         case 30 -> 0xFFFF4C;
         case 40 -> 0xF096FF;
         case 50 -> 0xFA0000;
         case 60 -> 0xB4B4B4;
         case 70 -> 0xF0F0F0;
         case 80 -> 0x0064C8;
         case 90 -> 0x0096A0;
         case 95 -> 0x00CF75;
         case 100 -> 0xFAE6A0;
         default -> 0x000000;
      };
   }

   /** Plain JUnit has no data-pack tags: bind the vanilla membership of the tags surface rules read. */
   private static void bindTags(HolderLookup<Biome> biomes) throws ReflectiveOperationException {
      Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
      bindTags.setAccessible(true);
      Map<TagKey<Biome>, Set<ResourceKey<Biome>>> members = Map.of(
         BiomeTags.IS_BADLANDS, Set.of(Biomes.BADLANDS, Biomes.WOODED_BADLANDS, Biomes.ERODED_BADLANDS),
         BiomeTags.IS_TAIGA, Set.of(Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA),
         BiomeTags.IS_SAVANNA, Set.of(Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA),
         BiomeTags.IS_JUNGLE, Set.of(Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE),
         BiomeTags.IS_RIVER, Set.of(Biomes.RIVER, Biomes.FROZEN_RIVER),
         BiomeTags.IS_OCEAN, Set.of(Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN,
            Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN)
      );
      for (Holder.Reference<Biome> biome : biomes.listElements().toList()) {
         List<TagKey<Biome>> tags = members.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(biome::is)).map(Map.Entry::getKey).toList();
         bindTags.invoke(biome, tags);
      }
      Set<Block> dirt = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.MUD, Blocks.MOSS_BLOCK);
      for (Holder.Reference<Block> block : BuiltInRegistries.BLOCK.listElements().toList()) {
         bindTags.invoke(block, dirt.contains(block.value()) ? List.of(BlockTags.DIRT) : List.of());
      }
   }
}
