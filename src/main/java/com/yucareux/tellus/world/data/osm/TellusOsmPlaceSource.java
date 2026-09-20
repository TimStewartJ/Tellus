package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.pmtiles.PmTilesSafety;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.util.Mth;

public final class TellusOsmPlaceSource implements TellusCacheHandle {
   private static final String PM_TILES_THEME = "base";
   private static final double MIN_LAT = -85.05112878;
   private static final double MAX_LAT = 85.05112878;
   private static final double MIN_LON = -180.0;
   private static final double MAX_LON = 180.0;
   private static final int DEFAULT_TILE_EXTENT = 4096;
   private static final int CONNECT_TIMEOUT_MS = intProperty("tellus.overture.places.connectTimeoutMs", 30000, 1, 120000);
   private static final int READ_TIMEOUT_MS = intProperty("tellus.overture.places.readTimeoutMs", 60000, 1, 180000);
   private static final int DIRECTORY_CACHE_ENTRIES = intProperty("tellus.overture.places.dirCache", 256, 1, 8192);
   private static final int MAX_CACHE_TILES = intProperty("tellus.osm.places.cacheTiles", 256, 1, 8192);
   private static final int MAX_ASYNC_PREFETCH_LOADS = intProperty("tellus.osm.places.prefetchAsyncMax", 96, 0, 8192);
   private static final int FETCH_RETRY_ATTEMPTS = intProperty("tellus.overture.places.fetchRetries", 3, 1, 8);
   private static final int PMTILES_TILETYPE_MVT = 1;
   private static final String LAND_LAYER_NAME = "land";
   private static final String WATER_LAYER_NAME = "water";
   private static final byte[] EMPTY_TILE_PAYLOAD = new byte[0];

   private final Path cacheRoot;
   private final PmTilesRangeReader pmTilesReader;
   private final Object initLock = new Object();
   private final LoadingCache<TellusOsmPlaceSource.TileKey, OsmPlaceTile> cache;
   private final Set<TellusOsmPlaceSource.TileKey> pendingAsyncLoads;
   private final Set<TellusOsmPlaceSource.TileKey> tileLoadFailures;
   private volatile boolean available;
   private volatile int minZoom;
   private volatile int maxZoom;
   private volatile boolean initialized;
   private volatile boolean pendingAsyncInitialization;
   private volatile int initializationFailures;
   private volatile long nextInitializationAttemptNanos;

   public TellusOsmPlaceSource() {
      String pmTilesUrl = System.getProperty("tellus.overture.places.pmtiles");
      if (pmTilesUrl == null || pmTilesUrl.isBlank()) {
         pmTilesUrl = OvertureTileUrls.defaultThemeUrl(PM_TILES_THEME);
      }

      this.cacheRoot = TellusPlatform.gameDir()
         .resolve("tellus/cache/map/places")
         .resolve(OvertureTileUrls.cacheNamespace(pmTilesUrl));
      this.pmTilesReader = PmTilesRangeReader.shared(pmTilesUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, DIRECTORY_CACHE_ENTRIES);
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_TILES).build(new CacheLoader<TellusOsmPlaceSource.TileKey, OsmPlaceTile>() {
         public OsmPlaceTile load(TellusOsmPlaceSource.TileKey key) {
            return TellusOsmPlaceSource.this.loadTile(key);
         }
      });
      this.pendingAsyncLoads = ConcurrentHashMap.newKeySet();
      this.tileLoadFailures = ConcurrentHashMap.newKeySet();
      TellusCacheRegistry.register(this);
   }

   public boolean available() {
      this.ensureInitialized();
      return this.available;
   }

   public PlaceQueryResult placesForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection, OsmQueryMode mode
   ) {
      OsmQueryMode queryMode = mode == null ? OsmQueryMode.BLOCKING : mode;
      if (queryMode == OsmQueryMode.NON_BLOCKING && !this.initialized) {
         this.queueAsyncInitialization();
         return new PlaceQueryResult(List.of(), false);
      }

      this.ensureInitialized();
      if (!this.available) {
         return new PlaceQueryResult(List.of(), false);
      }
      if (projection.worldScale() <= 0.0) {
         return new PlaceQueryResult(List.of(), true);
      }

      GeoBounds bounds = geoBoundsForBlockArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, projection);
      if (bounds == null) {
         return new PlaceQueryResult(List.of(), true);
      }

      List<TileKey> keys = tileKeysForBounds(bounds, this.maxZoom);
      if (keys.isEmpty()) {
         return new PlaceQueryResult(List.of(), true);
      }

      Map<String, OsmPlaceFeature> placesById = new LinkedHashMap<>();
      boolean complete = true;
      for (TileKey key : keys) {
         TileLookup lookup = this.getTileLookup(key, queryMode);
         complete &= !lookup.cacheMiss();
         OsmPlaceTile tile = lookup.tile();
         if (tile.isEmpty()) {
            continue;
         }
         List<OsmPlaceFeature> matches;
         if (bounds.wrapsLongitude()) {
            matches = new ArrayList<>();
            matches.addAll(tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), 180.0));
            matches.addAll(tile.featuresInBounds(bounds.south(), -180.0, bounds.north(), bounds.east()));
         } else {
            matches = tile.featuresInBounds(bounds.south(), bounds.west(), bounds.north(), bounds.east());
         }
         for (OsmPlaceFeature feature : matches) {
            placesById.putIfAbsent(feature.id(), feature);
         }
      }

      return new PlaceQueryResult(List.copyOf(placesById.values()), complete);
   }

   private TileLookup getTileLookup(TileKey key, OsmQueryMode mode) {
      OsmPlaceTile cached = this.cache.getIfPresent(key);
      if (cached != null) {
         if (!cached.isEmpty() || !this.tileLoadFailures.contains(key)) {
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.MEMORY);
            return new TileLookup(cached, false);
         }

         this.cache.invalidate(key);
      }

      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         if (this.tileLoadFailures.contains(key)) {
            return new TileLookup(OsmPlaceTile.empty(), true);
         }
         OsmPlaceTile local = this.loadTile(key);
         boolean incomplete = local.isEmpty() && this.tileLoadFailures.contains(key);
         if (!incomplete) {
            this.cache.put(key, local);
         }
         return new TileLookup(local, incomplete);
      }

      if (mode == OsmQueryMode.NON_BLOCKING) {
         this.queueAsyncLoad(key);
         return new TileLookup(OsmPlaceTile.empty(), true);
      }

      try {
         return new TileLookup(this.cache.get(key), false);
      } catch (Exception error) {
         Tellus.LOGGER.debug("Failed to load Overture place tile {}", key, error);
         this.tileLoadFailures.add(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.FAILURE);
         return new TileLookup(OsmPlaceTile.empty(), false);
      }
   }

   private void queueAsyncInitialization() {
      long now = System.nanoTime();
      if (this.initialized || this.pendingAsyncInitialization || now < this.nextInitializationAttemptNanos) {
         return;
      }
      synchronized (this.initLock) {
         if (this.initialized || this.pendingAsyncInitialization || System.nanoTime() < this.nextInitializationAttemptNanos) {
            return;
         }
         this.pendingAsyncInitialization = true;
      }
      MinecraftVersionCompat.backgroundExecutor().execute(() -> {
         try {
            this.ensureInitialized();
         } finally {
            this.pendingAsyncInitialization = false;
         }
      });
   }

   private void queueAsyncLoad(TileKey key) {
      if (MAX_ASYNC_PREFETCH_LOADS <= 0 || this.pendingAsyncLoads.size() < MAX_ASYNC_PREFETCH_LOADS) {
         if (this.pendingAsyncLoads.add(key)) {
            MinecraftVersionCompat.backgroundExecutor().execute(() -> {
               try {
                  this.cache.get(key);
               } catch (Exception error) {
                  Tellus.LOGGER.debug("Async load failed for Overture place tile {}", key, error);
                  this.tileLoadFailures.add(key);
                  OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.FAILURE);
               } finally {
                  this.pendingAsyncLoads.remove(key);
               }
            });
         }
      }
   }

   private OsmPlaceTile loadTile(TileKey key) {
      this.ensureInitialized();
      if (!this.available) {
         return OsmPlaceTile.empty();
      }

      Path cachePath = this.cachePathFor(key);
      Path parsedCachePath = this.parsedCachePathFor(key);
      if (Files.exists(parsedCachePath)) {
         try {
            OsmPlaceTile parsed = ParsedTileCodec.readPlaceTile(parsedCachePath);
            this.tileLoadFailures.remove(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.PARSED_DISK);
            return parsed;
         } catch (RuntimeException | IOException error) {
            Tellus.LOGGER.debug("Invalid parsed Overture place cache tile {}, refetching", key, error);
            try {
               Files.deleteIfExists(parsedCachePath);
            } catch (IOException deleteError) {
               Tellus.LOGGER.debug("Failed to delete invalid parsed Overture place cache tile {}", parsedCachePath, deleteError);
            }
         }
      }

      if (Files.exists(cachePath)) {
         try {
            byte[] payload = this.readCompressed(cachePath);
            OsmPlaceTile parsed = parseVectorTile(payload, key.zoom(), key.x(), key.y());
            this.cacheParsedTile(parsedCachePath, parsed);
            this.tileLoadFailures.remove(key);
            OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.RAW_DISK);
            return parsed;
         } catch (RuntimeException | IOException error) {
            Tellus.LOGGER.debug("Invalid Overture place cache tile {}, refetching", key, error);
            try {
               Files.deleteIfExists(cachePath);
            } catch (IOException deleteError) {
               Tellus.LOGGER.debug("Failed to delete invalid Overture place cache tile {}", cachePath, deleteError);
            }
         }
      }

      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         this.tileLoadFailures.add(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.FAILURE);
         return OsmPlaceTile.empty();
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      byte[] payload = this.fetchTilePayloadWithRetry(key);
      if (!TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, generation)) {
         throw new RuntimeException("Discarded stale Overture place tile " + key);
      }

      OsmPlaceTile parsed;
      try {
         parsed = parseVectorTile(payload, key.zoom(), key.x(), key.y());
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Overture place parse failed for tile {}", key, error);
         this.tileLoadFailures.add(key);
         OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.FAILURE);
         throw new RuntimeException("Overture place parse failed for tile " + key, error);
      }

      if (!this.cacheTile(cachePath, payload, generation)) {
         throw new RuntimeException("Discarded stale Overture place cache write for tile " + key);
      }

      this.cacheParsedTile(parsedCachePath, parsed);
      this.tileLoadFailures.remove(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.NETWORK);
      return parsed;
   }

   private void ensureInitialized() {
      if (this.initialized) {
         return;
      }
      long now = System.nanoTime();
      if (now < this.nextInitializationAttemptNanos) {
         return;
      }

      synchronized (this.initLock) {
         if (this.initialized || System.nanoTime() < this.nextInitializationAttemptNanos) {
            return;
         }

         int resolvedMinZoom = 0;
         int resolvedMaxZoom = 0;
         boolean sourceAvailable;

         try {
            PmTilesRangeReader.PmTilesHeader header = this.pmTilesReader.header();
            if (header.tileType() != PMTILES_TILETYPE_MVT) {
               Tellus.LOGGER.warn("Unexpected Overture places PMTiles tile type {}, expected MVT", header.tileType());
            }

            resolvedMinZoom = header.minZoom();
            resolvedMaxZoom = header.maxZoom();
            sourceAvailable = true;
            this.initializationFailures = 0;
            this.nextInitializationAttemptNanos = 0L;
         } catch (IOException error) {
            sourceAvailable = false;
            int failures = ++this.initializationFailures;
            long delaySeconds = retryDelaySeconds(failures);
            this.nextInitializationAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds);
            Tellus.LOGGER.warn("Overture places PMTiles unavailable; retrying in {}s", delaySeconds, error);
         }

         this.available = sourceAvailable;
         this.minZoom = resolvedMinZoom;
         this.maxZoom = resolvedMaxZoom;
         this.initialized = sourceAvailable;
      }
   }

   static long retryDelaySeconds(int failureCount) {
      if (failureCount <= 0) {
         return 0L;
      }
      if (failureCount <= 5) {
         return 1L << failureCount - 1;
      }
      return 60L;
   }

   private byte[] fetchTilePayloadWithRetry(TileKey key) {
      RuntimeException lastFailure = null;

      for (int attempt = 1; attempt <= FETCH_RETRY_ATTEMPTS; attempt++) {
         try {
            byte[] fetched = this.pmTilesReader.getTileBytes(key.zoom(), key.x(), key.y());
            return fetched == null ? EMPTY_TILE_PAYLOAD : fetched;
         } catch (IOException error) {
            lastFailure = new RuntimeException("Overture place fetch failed for tile " + key, error);
            if (attempt < FETCH_RETRY_ATTEMPTS) {
               continue;
            }
            break;
         }
      }

      this.tileLoadFailures.add(key);
      OsmPerf.recordTileLoad(OsmPerf.TileSource.OSM_PLACES, OsmPerf.TileLoadPath.FAILURE);
      if (lastFailure != null) {
         throw lastFailure;
      }
      return EMPTY_TILE_PAYLOAD;
   }

   private boolean cacheTile(Path cachePath, byte[] payload, long generation) {
      try {
         return TellusCacheFiles.writeIfCurrent(TellusCacheDomain.OSM, generation, cachePath, output -> {
            try (OutputStream gzipOutput = new GZIPOutputStream(output)) {
               gzipOutput.write(payload);
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache Overture place tile {}", cachePath, error);
         return false;
      }
   }

   private void cacheParsedTile(Path cachePath, OsmPlaceTile tile) {
      try {
         ParsedTileCodec.writePlaceTile(cachePath, tile);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache parsed Overture place tile {}", cachePath, error);
      }
   }

   private byte[] readCompressed(Path path) throws IOException {
      try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
         return PmTilesSafety.readBounded(
            input, PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES, "Cached Overture place tile"
         );
      }
   }

   static OsmPlaceTile parseVectorTile(byte[] payload, int zoom, int tileX, int tileY) {
      if (payload.length == 0) {
         return OsmPlaceTile.empty();
      }

      Tile tile;
      try {
         tile = Tile.parseFrom(payload);
      } catch (Exception error) {
         throw new RuntimeException("Failed to decode place MVT payload", error);
      }

      if (tile.getLayersCount() == 0) {
         return OsmPlaceTile.empty();
      }

      TileKey key = new TileKey(zoom, tileX, tileY);
      List<OsmPlaceFeature> features = new ArrayList<>();
      for (Layer layer : tile.getLayersList()) {
         if (LAND_LAYER_NAME.equals(layer.getName()) || WATER_LAYER_NAME.equals(layer.getName())) {
            int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_TILE_EXTENT;
            for (int featureIndex = 0; featureIndex < layer.getFeaturesCount(); featureIndex++) {
               Feature feature = layer.getFeatures(featureIndex);
               if (feature.getType() == GeomType.POINT) {
                  OsmPlaceFeature parsed = parseFeature(feature, layer, key, extent, featureIndex);
                  if (parsed != null) {
                     features.add(parsed);
                  }
               }
            }
         }
      }

      return features.isEmpty() ? OsmPlaceTile.empty() : new OsmPlaceTile(features);
   }

   private static OsmPlaceFeature parseFeature(Feature feature, Layer layer, TileKey key, int extent, int featureIndex) {
      Map<String, Object> tags = decodeTags(feature, layer);
      String name = placeName(tags);
      if (name == null) {
         return null;
      }

      List<TilePoint> points = decodePoints(feature.getGeometryList());
      if (points.isEmpty()) {
         return null;
      }

      TilePoint point = points.get(0);
      double longitude = tilePointToLon(key.zoom(), key.x(), (double)point.x / extent);
      double latitude = tilePointToLat(key.zoom(), key.y(), (double)point.y / extent);
      if (!Double.isFinite(latitude)
         || !Double.isFinite(longitude)
         || latitude < MIN_LAT
         || latitude > MAX_LAT
         || longitude < MIN_LON
         || longitude > MAX_LON) {
         return null;
      }

      String layerName = layer.getName();
      String id = featureId(tags, layerName, key, featureIndex);
      return new OsmPlaceFeature(
         id,
         name,
         layerName,
         nonBlank(asString(tags.get("subtype"))),
         nonBlank(asString(tags.get("class"))),
         latitude,
         longitude,
         asDouble(tags.get("elevation"), Double.NaN),
         nonBlank(asString(tags.get("wikidata")))
      );
   }

   private static String placeName(Map<String, Object> tags) {
      String name = nonBlank(asString(tags.get("@name")));
      if (name != null) {
         return name;
      }
      Object namesValue = tags.get("names");
      if (!(namesValue instanceof String names) || names.isBlank()) {
         return null;
      }
      try {
         JsonElement parsed = JsonParser.parseString(names);
         if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            JsonElement primary = object.get("primary");
            if (primary != null && primary.isJsonPrimitive()) {
               return nonBlank(primary.getAsString());
            }
         }
      } catch (RuntimeException ignored) {
      }
      return null;
   }

   private static String featureId(Map<String, Object> tags, String layerName, TileKey key, int featureIndex) {
      String id = nonBlank(asString(tags.get("id")));
      return id != null ? id : layerName + ":" + key.zoom() + "/" + key.x() + "/" + key.y() + "#" + featureIndex;
   }

   private static List<TilePoint> decodePoints(List<Integer> geometry) {
      if (geometry == null || geometry.isEmpty()) {
         return List.of();
      }

      List<TilePoint> points = new ArrayList<>();
      int cursorX = 0;
      int cursorY = 0;
      int cursor = 0;
      while (cursor < geometry.size()) {
         int commandAndCount = geometry.get(cursor++);
         int command = commandAndCount & 7;
         int count = commandAndCount >>> 3;
         if (command != 1 || count <= 0) {
            return points;
         }
         for (int i = 0; i < count; i++) {
            if (cursor + 1 >= geometry.size()) {
               return points;
            }
            cursorX += zigZagDecode(geometry.get(cursor++));
            cursorY += zigZagDecode(geometry.get(cursor++));
            points.add(new TilePoint(cursorX, cursorY));
         }
      }
      return points.isEmpty() ? List.of() : List.copyOf(points);
   }

   private static int zigZagDecode(int encoded) {
      return encoded >>> 1 ^ -(encoded & 1);
   }

   private static double tilePointToLon(int zoom, int tileX, double localX) {
      double n = tilesPerAxis(zoom);
      double normalizedX = (tileX + localX) / n;
      return normalizedX * 360.0 - 180.0;
   }

   private static double tilePointToLat(int zoom, int tileY, double localY) {
      double n = tilesPerAxis(zoom);
      double normalizedY = (tileY + localY) / n;
      double mercator = Math.PI * (1.0 - 2.0 * normalizedY);
      return Math.toDegrees(Math.atan(Math.sinh(mercator)));
   }

   private static Map<String, Object> decodeTags(Feature feature, Layer layer) {
      List<Integer> tags = feature.getTagsList();
      if (tags.isEmpty()) {
         return Map.of();
      }

      Map<String, Object> values = new HashMap<>(tags.size() / 2);
      for (int i = 0; i + 1 < tags.size(); i += 2) {
         int keyIndex = tags.get(i);
         int valueIndex = tags.get(i + 1);
         if (keyIndex >= 0 && keyIndex < layer.getKeysCount() && valueIndex >= 0 && valueIndex < layer.getValuesCount()) {
            String key = layer.getKeys(keyIndex);
            Object value = decodeValue(layer.getValues(valueIndex));
            if (key != null && !key.isBlank() && value != null) {
               values.put(key, value);
            }
         }
      }
      return values;
   }

   private static Object decodeValue(Value value) {
      if (value.hasStringValue()) {
         return value.getStringValue();
      } else if (value.hasDoubleValue()) {
         return value.getDoubleValue();
      } else if (value.hasFloatValue()) {
         return (double)value.getFloatValue();
      } else if (value.hasSintValue()) {
         return value.getSintValue();
      } else if (value.hasIntValue()) {
         return value.getIntValue();
      } else if (value.hasUintValue()) {
         return value.getUintValue();
      }
      return value.hasBoolValue() ? value.getBoolValue() : null;
   }

   private static GeoBounds geoBoundsForBlockArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, WorldProjection projection) {
      if (projection.worldScale() <= 0.0) {
         return null;
      }
      double west = projection.blockXToLon(Math.min(minBlockX, maxBlockX));
      double east = projection.blockXToLon(Math.max(minBlockX, maxBlockX));
      double north = clampLat(projection.blockZToLat(Math.min(minBlockZ, maxBlockZ)));
      double south = clampLat(projection.blockZToLat(Math.max(minBlockZ, maxBlockZ)));
      if (south > north) {
         double swap = south;
         south = north;
         north = swap;
      }
      return new GeoBounds(south, west, north, east);
   }

   private static List<TileKey> tileKeysForBounds(GeoBounds bounds, int zoom) {
      int tilesPerAxis = 1 << zoom;
      int minX = Mth.clamp(lonToTileX(bounds.west(), zoom), 0, tilesPerAxis - 1);
      int maxX = Mth.clamp(lonToTileX(bounds.east(), zoom), 0, tilesPerAxis - 1);
      int minY = Mth.clamp(latToTileY(bounds.north(), zoom), 0, tilesPerAxis - 1);
      int maxY = Mth.clamp(latToTileY(bounds.south(), zoom), 0, tilesPerAxis - 1);
      if (maxY < minY) {
         return List.of();
      }

      int columns = bounds.wrapsLongitude() ? tilesPerAxis - minX + maxX + 1 : maxX - minX + 1;
      if (columns <= 0) {
         return List.of();
      }
      List<TileKey> keys = new ArrayList<>(columns * (maxY - minY + 1));
      for (int y = minY; y <= maxY; y++) {
         if (bounds.wrapsLongitude()) {
            addTileKeys(keys, zoom, y, minX, tilesPerAxis - 1);
            addTileKeys(keys, zoom, y, 0, maxX);
         } else {
            addTileKeys(keys, zoom, y, minX, maxX);
         }
      }
      return keys;
   }

   private static void addTileKeys(List<TileKey> keys, int zoom, int tileY, int minTileX, int maxTileX) {
      for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
         keys.add(new TileKey(zoom, tileX, tileY));
      }
   }

   private static int lonToTileX(double lon, int zoom) {
      double n = tilesPerAxis(zoom);
      double x = (clampLon(lon) + 180.0) / 360.0 * n;
      if (x >= n) {
         x = n - 1.0;
      }
      return Mth.floor(x);
   }

   private static int latToTileY(double lat, int zoom) {
      double clampedLat = clampLat(lat);
      double n = tilesPerAxis(zoom);
      double latRad = Math.toRadians(clampedLat);
      double y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
      if (y >= n) {
         y = n - 1.0;
      }
      return Mth.floor(y);
   }

   private Path cachePathFor(TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".mvt.gz");
   }

   private Path parsedCachePathFor(TileKey key) {
      return this.cacheRoot.resolve(key.zoom() + "/" + key.x() + "/" + key.y() + ".pm.parsed.bin");
   }

   private static double clampLat(double latitude) {
      return Mth.clamp(latitude, MIN_LAT, MAX_LAT);
   }

   private static double clampLon(double longitude) {
      return Mth.clamp(longitude, MIN_LON, MAX_LON);
   }

   private static double tilesPerAxis(int zoom) {
      return (double)(1 << zoom);
   }

   private static String asString(Object value) {
      return value == null ? null : String.valueOf(value);
   }

   private static String nonBlank(String value) {
      return value != null && !value.isBlank() ? value : null;
   }

   private static double asDouble(Object value, double fallback) {
      if (value instanceof Number number) {
         return number.doubleValue();
      }
      if (value != null) {
         try {
            return Double.parseDouble(String.valueOf(value));
         } catch (NumberFormatException ignored) {
         }
      }
      return fallback;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }
      try {
         int parsed = Integer.parseInt(value);
         return Mth.clamp(parsed, minInclusive, maxInclusive);
      } catch (NumberFormatException error) {
         Tellus.LOGGER.debug("Invalid integer system property {}='{}', using {}", new Object[]{key, value, defaultValue});
         return defaultValue;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.pendingAsyncLoads.clear();
      this.tileLoadFailures.clear();
   }

   private record GeoBounds(double south, double west, double north, double east) {
      private boolean wrapsLongitude() {
         return this.east < this.west;
      }
   }

   private record TileKey(int zoom, int x, int y) {
   }

   private record TileLookup(OsmPlaceTile tile, boolean cacheMiss) {
   }

   private record TilePoint(int x, int y) {
   }
}
