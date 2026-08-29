package com.yucareux.tellus.world.data.elevation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Measures the cost of decoding cached Mapterhorn WebP tiles into rasters. */
public final class TileDecodeBenchmark {
   private TileDecodeBenchmark() {
   }

   public static void main(String[] args) throws IOException {
      Path root = Path.of(args.length > 0 ? args[0] : "build/chunkgen-benchmark-game/tellus/cache/elevation-mapterhorn");
      int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
      List<Path> tiles = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(root)) {
         walk.filter(path -> path.toString().endsWith(".webp")).forEach(tiles::add);
      }
      System.out.printf(Locale.ROOT, "TILE_DECODE tiles=%d root=%s%n", tiles.size(), root);
      long totalBytes = 0L;
      for (Path tile : tiles) {
         totalBytes += Files.size(tile);
      }
      for (int round = 1; round <= rounds; round++) {
         long readNs = 0L;
         long decodeNs = 0L;
         long checksum = 0L;
         for (Path tile : tiles) {
            long start = System.nanoTime();
            byte[] bytes = Files.readAllBytes(tile);
            long afterRead = System.nanoTime();
            ShortRaster raster = TellusElevationSource.readTerrainRaster(new ByteArrayInputStream(bytes));
            long end = System.nanoTime();
            readNs += afterRead - start;
            decodeNs += end - afterRead;
            checksum = checksum * 31L + raster.get(0, 0) + raster.get(255, 255) + raster.get(511, 511);
         }
         System.out.printf(
            Locale.ROOT,
            "TILE_DECODE_ROUND round=%d tiles=%d avg_bytes=%d read_ms_per_tile=%.2f decode_ms_per_tile=%.2f checksum=%d%n",
            round,
            tiles.size(),
            totalBytes / Math.max(1, tiles.size()),
            readNs / 1.0e6 / tiles.size(),
            decodeNs / 1.0e6 / tiles.size(),
            checksum
         );
      }
   }
}
