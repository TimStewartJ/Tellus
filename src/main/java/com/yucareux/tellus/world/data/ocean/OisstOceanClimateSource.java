package com.yucareux.tellus.world.data.ocean;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import net.minecraft.util.Mth;

/**
 * Annual ocean climate (mean sea-surface temperature and peak sea-ice fraction) on the 0.25 degree
 * NOAA OISST v2 grid, from the 1991-2020 monthly long-term means published by NOAA PSL over OPeNDAP.
 *
 * <p>Cells are fetched and cached in 60x60 blocks (15 x 15 degrees). A PSL request takes a few seconds
 * whatever its size, so one request per large block, instead of one per cell or small block, is what
 * keeps Distant Horizons from waiting on thousands of kilometres of ocean.</p>
 */
public final class OisstOceanClimateSource implements TellusCacheHandle {
   private static final double GRID_DEGREES = 0.25;
   private static final double MIN_GRID_LAT = -89.875;
   private static final int LAT_CELL_COUNT = 720;
   private static final int LON_CELL_COUNT = 1440;
   // Divides both grid dimensions (720 x 1440), so every block is complete.
   private static final int BLOCK_CELLS = 60;
   private static final int BLOCK_AREA = BLOCK_CELLS * BLOCK_CELLS;
   private static final int MONTHS = 12;
   private static final String DEFAULT_BASE_URL = "https://psl.noaa.gov/thredds/dodsC/Datasets/noaa.oisst.v2.highres";
   private static final String BASE_URL = System.getProperty("tellus.oisst.baseUrl", DEFAULT_BASE_URL);
   private static final String SST_FILE = "sst.mon.ltm.1991-2020.nc";
   private static final String ICE_FILE = "icec.mon.ltm.1991-2020.nc";
   private static final String SOURCE_CACHE_ID = Integer.toUnsignedString((BASE_URL + '|' + SST_FILE + '|' + ICE_FILE).hashCode(), 16);
   private static final double MIN_VALID_SST_C = -3.0;
   private static final double MAX_VALID_SST_C = 45.0;
   private static final int HTTP_CONNECT_TIMEOUT = intProperty("tellus.oisst.connectTimeoutMs", 8000);
   private static final int HTTP_READ_TIMEOUT = intProperty("tellus.oisst.readTimeoutMs", 15000);
   private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
   // No sea ice occurs in the 1991-2020 climatology this close to the equator.
   private static final double ICE_FREE_MAX_ABS_LATITUDE = 30.0;
   private static final int MAX_CACHE_CELLS = intProperty("tellus.oisst.cacheCells", 4096);
   private static final int MAX_CACHE_BLOCKS = intProperty("tellus.oisst.cacheBlocks", 64);
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   // A retired dataset answers HTTP 404. Stop asking once that is known this session.
   private static volatile boolean endpointMissing;
   private final Path cacheRoot;
   private final LoadingCache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> cache;
   private final LoadingCache<OisstOceanClimateSource.BlockKey, OisstOceanClimateSource.Sample[]> blocks;

   public OisstOceanClimateSource() {
      this.cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/ocean-oisst-v21");
      this.cache = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_CELLS)
         .build(new CacheLoader<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample>() {
            public OisstOceanClimateSource.Sample load(OisstOceanClimateSource.CellKey key) {
               return OisstOceanClimateSource.this.loadSample(key);
            }
         });
      this.blocks = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_BLOCKS)
         .build(new CacheLoader<OisstOceanClimateSource.BlockKey, OisstOceanClimateSource.Sample[]>() {
            public OisstOceanClimateSource.Sample[] load(OisstOceanClimateSource.BlockKey key) throws IOException {
               return OisstOceanClimateSource.this.loadBlock(key);
            }
         });
      TellusCacheRegistry.register(this);
   }

   public OisstOceanClimateSource.Sample sample(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return fallbackForLatitude(0.0);
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (blocksPerDegree <= 0.0) {
         return fallbackForLatitude(0.0);
      }

      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      OisstOceanClimateSource.CellKey key = cellKeyForLatLon(lat, blockX / blocksPerDegree);
      if (key == null) {
         return fallbackForLatitude(lat);
      }

      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         OisstOceanClimateSource.Sample cached = this.cache.getIfPresent(key);
         if (cached == null) {
            OisstOceanClimateSource.Sample[] block = this.blocks.getIfPresent(key.block());
            if (block == null) {
               block = this.readCachedBlock(key.block());
            }
            if (block != null) {
               cached = block[key.blockOffset()];
               this.cache.put(key, cached);
            }
         }
         return cached == null ? fallbackForLatitude(lat) : cached;
      }

      try {
         return this.cache.get(key);
      } catch (ExecutionException | UncheckedExecutionException error) {
         Tellus.LOGGER.debug("Failed to sample NOAA OISST v2 cell {}", key, error);
         return fallbackForLatitude(lat);
      }
   }

   public boolean preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      int completedUnits,
      BiConsumer<Integer, String> progress
   ) {
      if (worldScale <= 0.0) {
         return true;
      }
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int step = Math.max(16, (int)Math.floor(blocksPerDegree * GRID_DEGREES * 0.8));
      Set<OisstOceanClimateSource.CellKey> cells = new LinkedHashSet<>();
      for (long z = minBlockZ; z <= (long)maxBlockZ + step; z += step) {
         double sampleZ = Math.min(maxBlockZ, z);
         double latitude = EarthProjection.blockZToLat(sampleZ, worldScale);
         for (long x = minBlockX; x <= (long)maxBlockX + step; x += step) {
            double sampleX = Math.min(maxBlockX, x);
            OisstOceanClimateSource.CellKey key = cellKeyForLatLon(latitude, sampleX / blocksPerDegree);
            if (key != null) {
               cells.add(key);
            }
         }
      }

      boolean available = true;
      int finished = 0;
      for (OisstOceanClimateSource.CellKey key : cells) {
         try {
            OisstOceanClimateSource.Sample sample = this.cache.get(key);
            available &= sample.available();
         } catch (ExecutionException | RuntimeException error) {
            available = false;
         }
         finished++;
         if (progress != null) {
            progress.accept(completedUnits + finished, "Ocean climate " + finished + "/" + cells.size());
         }
      }
      return available;
   }

   private OisstOceanClimateSource.Sample loadSample(OisstOceanClimateSource.CellKey key) {
      if (endpointMissing) {
         return fallbackForLatitude(key.latCenter());
      }

      try {
         return this.blocks.get(key.block())[key.blockOffset()];
      } catch (ExecutionException | UncheckedExecutionException error) {
         Tellus.LOGGER.debug("Falling back from NOAA OISST v2 cell {}", key, error.getCause());
         return fallbackForLatitude(key.latCenter());
      }
   }

   private OisstOceanClimateSource.Sample[] loadBlock(OisstOceanClimateSource.BlockKey key) throws IOException {
      OisstOceanClimateSource.Sample[] cached = this.readCachedBlock(key);
      if (cached != null) {
         return cached;
      }
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         throw new IOException("Network access is disabled during managed Distant Horizons generation");
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OISST);
      double[] sst = this.fetchMonthlyGrid(SST_FILE, "sst", key);
      double[] ice;
      try {
         ice = iceFree(key) ? null : this.fetchMonthlyGrid(ICE_FILE, "icec", key);
      } catch (IOException error) {
         // Ice only refines polar biomes; keep the temperatures when it is unavailable.
         Tellus.LOGGER.debug("NOAA OISST ice block {} unavailable", key, error);
         ice = null;
      }

      OisstOceanClimateSource.Sample[] samples = new OisstOceanClimateSource.Sample[BLOCK_AREA];
      for (int offset = 0; offset < BLOCK_AREA; offset++) {
         double sum = 0.0;
         int count = 0;
         double maxIce = 0.0;
         for (int month = 0; month < MONTHS; month++) {
            double value = sst[month * BLOCK_AREA + offset];
            if (Double.isFinite(value)) {
               sum += value;
               count++;
            }
            if (ice != null) {
               double iceValue = ice[month * BLOCK_AREA + offset];
               if (Double.isFinite(iceValue)) {
                  maxIce = Math.max(maxIce, iceValue);
               }
            }
         }
         double latCenter = MIN_GRID_LAT + (key.latBlock() * BLOCK_CELLS + offset / BLOCK_CELLS) * GRID_DEGREES;
         samples[offset] = count == 0
            ? fallbackForLatitude(latCenter)
            : new OisstOceanClimateSource.Sample(sum / count, Mth.clamp(maxIce, 0.0, 1.0), true);
      }
      this.writeCachedBlock(key, samples, generation);
      return samples;
   }

   /** Returns the block's 12 monthly values, month-major then row-major; missing values are NaN. */
   private double[] fetchMonthlyGrid(String file, String variable, OisstOceanClimateSource.BlockKey key) throws IOException {
      int latStart = key.latBlock() * BLOCK_CELLS;
      int lonStart = key.lonBlock() * BLOCK_CELLS;
      String url = BASE_URL + "/" + file + ".ascii?" + variable
         + "%5B0:1:" + (MONTHS - 1) + "%5D"
         + "%5B" + latStart + ":1:" + (latStart + BLOCK_CELLS - 1) + "%5D"
         + "%5B" + lonStart + ":1:" + (lonStart + BLOCK_CELLS - 1) + "%5D";
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      boolean reusable = false;
      try {
         int status = connection.getResponseCode();
         if (status == 404 && !endpointMissing) {
            endpointMissing = true;
            Tellus.LOGGER.warn("NOAA OISST climatology unavailable (HTTP 404); using latitude ocean climate defaults this session");
         }
         if (status < 200 || status >= 300) {
            throw new IOException("NOAA OISST climatology returned HTTP " + status);
         }
         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_RESPONSE_BYTES) {
            throw new IOException("NOAA OISST climatology response exceeds the safety limit");
         }

         byte[] response;
         try (InputStream input = connection.getInputStream()) {
            response = InputStreamSafety.readAllBytes(input, MAX_RESPONSE_BYTES, "NOAA OISST response");
         }
         reusable = true;
         return parseAsciiGrid(response, variable, BLOCK_CELLS);
      } finally {
         if (!reusable) {
            connection.disconnect();
         }
      }
   }

   private static boolean iceFree(OisstOceanClimateSource.BlockKey key) {
      double southEdge = MIN_GRID_LAT - GRID_DEGREES * 0.5 + key.latBlock() * BLOCK_CELLS * GRID_DEGREES;
      double northEdge = southEdge + BLOCK_CELLS * GRID_DEGREES;
      return southEdge >= -ICE_FREE_MAX_ABS_LATITUDE && northEdge <= ICE_FREE_MAX_ABS_LATITUDE;
   }

   /**
    * Parses an OPeNDAP ASCII grid response of shape [12][cells][cells]: after the {@code var.var[..]}
    * header, each line is {@code [month][row], v0, ..., v(cells-1)}.
    */
   static double[] parseAsciiGrid(byte[] response, String variable, int cells) throws IOException {
      int area = cells * cells;
      double[] values = new double[MONTHS * area];
      Arrays.fill(values, Double.NaN);
      String header = variable + "." + variable + "[";
      int rows = 0;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(response), StandardCharsets.UTF_8))) {
         String line;
         boolean inData = false;
         while ((line = reader.readLine()) != null) {
            if (!inData) {
               inData = line.startsWith(header);
               continue;
            }
            if (line.isBlank()) {
               break;
            }
            int close = line.indexOf(']');
            int secondClose = close < 0 ? -1 : line.indexOf(']', close + 1);
            if (!line.startsWith("[") || secondClose < 0) {
               throw new IOException("Unexpected NOAA OISST grid row");
            }
            int month = Integer.parseInt(line.substring(1, close).trim());
            int row = Integer.parseInt(line.substring(close + 2, secondClose).trim());
            String[] parts = line.substring(secondClose + 1).split(",");
            if (month < 0 || month >= MONTHS || row < 0 || row >= cells || parts.length != cells + 1) {
               throw new IOException("Unexpected NOAA OISST grid shape");
            }
            for (int column = 0; column < cells; column++) {
               double value = parseDouble(parts[column + 1]);
               values[month * area + row * cells + column] = validValue(variable, value);
            }
            rows++;
         }
      } catch (NumberFormatException error) {
         throw new IOException("Malformed NOAA OISST grid", error);
      }
      if (rows != MONTHS * cells) {
         throw new IOException("Incomplete NOAA OISST grid (" + rows + " rows)");
      }
      return values;
   }

   private static double validValue(String variable, double value) {
      if (!Double.isFinite(value)) {
         return Double.NaN;
      }
      if ("sst".equals(variable)) {
         return value >= MIN_VALID_SST_C && value <= MAX_VALID_SST_C ? value : Double.NaN;
      }
      double fraction = value > 1.5 ? value / 100.0 : value;
      return fraction >= 0.0 && fraction <= 1.0 ? fraction : Double.NaN;
   }

   private OisstOceanClimateSource.Sample[] readCachedBlock(OisstOceanClimateSource.BlockKey key) {
      Path path = this.cachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }

      try {
         List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
         if (lines.size() < BLOCK_AREA) {
            return null;
         }
         OisstOceanClimateSource.Sample[] samples = new OisstOceanClimateSource.Sample[BLOCK_AREA];
         for (int offset = 0; offset < BLOCK_AREA; offset++) {
            String[] parts = lines.get(offset).trim().split(",", -1);
            if (parts.length < 2) {
               return null;
            }
            double meanSstC = Double.parseDouble(parts[0]);
            double maxIceFraction = Double.parseDouble(parts[1]);
            double latCenter = MIN_GRID_LAT + (key.latBlock() * BLOCK_CELLS + offset / BLOCK_CELLS) * GRID_DEGREES;
            // Cells without ocean data (land) are stored as NaN and resolve to the latitude default.
            samples[offset] = Double.isFinite(meanSstC)
               ? new OisstOceanClimateSource.Sample(meanSstC, Mth.clamp(maxIceFraction, 0.0, 1.0), true)
               : fallbackForLatitude(latCenter);
         }
         return samples;
      } catch (IOException | NumberFormatException error) {
         Tellus.LOGGER.debug("Failed to read cached NOAA OISST block {}", key, error);
         return null;
      }
   }

   private void writeCachedBlock(OisstOceanClimateSource.BlockKey key, OisstOceanClimateSource.Sample[] samples, long generation) {
      StringBuilder value = new StringBuilder(BLOCK_AREA * 16);
      for (OisstOceanClimateSource.Sample sample : samples) {
         if (sample.available()) {
            value.append(String.format(Locale.ROOT, "%.4f,%.4f%n", sample.meanSstC(), sample.maxIceFraction()));
         } else {
            value.append("NaN,0\n");
         }
      }
      try {
         TellusCacheFiles.writeStringIfCurrent(TellusCacheDomain.OISST, generation, this.cachePath(key), value.toString(), StandardCharsets.UTF_8);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist NOAA OISST block {}", key, error);
      }
   }

   private Path cachePath(OisstOceanClimateSource.BlockKey key) {
      return this.cacheRoot
         .resolve(SOURCE_CACHE_ID)
         .resolve("ltm-1991-2020-block" + BLOCK_CELLS)
         .resolve(String.format(Locale.ROOT, "%02d_%02d.txt", key.latBlock(), key.lonBlock()));
   }

   private static OisstOceanClimateSource.CellKey cellKeyForLatLon(double lat, double lon) {
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
         return null;
      }

      double clampedLat = Mth.clamp(lat, -89.999999, 89.999999);
      double normalizedLon = lon % 360.0;
      if (normalizedLon < 0.0) {
         normalizedLon += 360.0;
      }

      int latIndex = Mth.clamp((int)Math.floor((clampedLat + 90.0) / GRID_DEGREES), 0, LAT_CELL_COUNT - 1);
      int lonIndex = Mth.clamp((int)Math.floor(normalizedLon / GRID_DEGREES), 0, LON_CELL_COUNT - 1);
      double latCenter = MIN_GRID_LAT + latIndex * GRID_DEGREES;
      double lonCenter = 0.125 + lonIndex * GRID_DEGREES;
      return new OisstOceanClimateSource.CellKey(latIndex, lonIndex, latCenter, lonCenter);
   }

   private static OisstOceanClimateSource.Sample fallbackForLatitude(double lat) {
      double absLat = Math.abs(lat);
      if (absLat >= 70.0) {
         return new OisstOceanClimateSource.Sample(-1.8, 0.6, false);
      } else if (absLat >= 55.0) {
         return new OisstOceanClimateSource.Sample(6.0, 0.0, false);
      } else {
         return absLat >= 35.0
            ? new OisstOceanClimateSource.Sample(16.0, 0.0, false)
            : new OisstOceanClimateSource.Sample(26.0, 0.0, false);
      }
   }

   private static double parseDouble(String value) {
      if (value == null || value.isBlank()) {
         return Double.NaN;
      }

      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException error) {
         return Double.NaN;
      }
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }

      try {
         return Math.max(1, Integer.parseInt(value));
      } catch (NumberFormatException error) {
         return defaultValue;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OISST;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.blocks.invalidateAll();
      this.blocks.cleanUp();
   }

   public record Sample(double meanSstC, double maxIceFraction, boolean available) {
   }

   private record CellKey(int latIndex, int lonIndex, double latCenter, double lonCenter) {
      BlockKey block() {
         return new BlockKey(this.latIndex / BLOCK_CELLS, this.lonIndex / BLOCK_CELLS);
      }

      int blockOffset() {
         return (this.latIndex % BLOCK_CELLS) * BLOCK_CELLS + this.lonIndex % BLOCK_CELLS;
      }
   }

   private record BlockKey(int latBlock, int lonBlock) {
   }
}
