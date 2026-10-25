package com.yucareux.tellus.worldgen;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in: renders a recorded hydrology fixture's planned water (level bands, falls in white, banks in tan) for
 * visual review. {@code TELLUS_FIXTURE_RENDER="niagara|minX|minZ|size"} (size blocks, rendered at one pixel each).
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_FIXTURE_RENDER", matches = ".+")
class RecordedFixtureRenderTest {
   @Test
   void render() throws Exception {
      String[] spec = System.getenv("TELLUS_FIXTURE_RENDER").split("\\|");
      var fixture = new RecordedHydrologyFixture(spec[0]);
      var planner = fixture.planner();
      int minX = Integer.parseInt(spec[1]), minZ = Integer.parseInt(spec[2]), size = Integer.parseInt(spec[3]);
      BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
      for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
         int wx = minX + x, wz = minZ + z;
         int raw = (int)Math.ceil(fixture.sample(wx, wz).height());
         var column = planner.sample(wx, wz, raw);
         int rgb;
         if (column.wet() && column.falling()) rgb = 0xFFFFFF;
         else if (column.wet()) rgb = java.awt.Color.HSBtoRGB(Math.floorMod(column.water(), 12) / 12.0F, 0.6F, 0.95F);
         else if (column.bank()) rgb = 0xB0A070;
         else { int shade = Math.max(20, Math.min(235, 60 + (raw - 150) * 2)); rgb = shade << 8 | shade / 2; }
         image.setRGB(x, z, rgb);
      }
      // Mapped waterway lines in black, waterfall lines in red.
      for (HydrologyWaterMap.Shape shape : fixture.shapes) {
         if (!shape.feature.lineGeometry()) continue;
         int color = shape.feature.kind() == com.yucareux.tellus.world.data.osm.OsmWaterKind.WATERFALL ? 0xFF0000 : 0x000000;
         shape.segments(minX, minZ, minX + size - 1, minZ + size - 1, (ax, az, bx, bz) -> {
            int steps = (int)Math.ceil(Math.hypot(bx - ax, bz - az));
            for (int k = 0; k <= steps; k++) {
               int px = (int)Math.floor(ax + (bx - ax) * k / Math.max(1, steps)) - minX, pz = (int)Math.floor(az + (bz - az) * k / Math.max(1, steps)) - minZ;
               if (px >= 0 && pz >= 0 && px < size && pz < size) image.setRGB(px, pz, color);
            }
         });
      }
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_RIVER_LAB_OUT", "build/river-lab"));
      Files.createDirectories(out);
      ImageIO.write(image, "png", out.resolve(spec[0] + "-fixture.png").toFile());
      for (int[] p : new int[][]{{-8802524, -5323992}, {-8802808, -5323732}, {-8802888, -5323636}, {-8802032, -5325124}, {-8802608, -5324032}}) {
         var column = planner.sample(p[0], p[1], (int)Math.ceil(fixture.sample(p[0], p[1]).height()));
         System.out.printf(Locale.ROOT, "FIXTURE %d,%d raw=%.1f %s%n", p[0], p[1], fixture.sample(p[0], p[1]).height(), column);
      }
   }
}
