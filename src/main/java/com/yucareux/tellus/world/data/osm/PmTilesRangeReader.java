package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.world.data.pmtiles.PmTilesSafety;
import com.yucareux.tellus.world.data.source.DownloadProgressReporter;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public final class PmTilesRangeReader {
   private static final int HEADER_SIZE = 127;
   private static final int MAX_DIRECTORY_DEPTH = 6;
   private static final int PMTILES_VERSION = 3;
   private static final long MAX_DIRECTORY_CACHE_BYTES = 128L * 1024L * 1024L;
   private static final long MAX_TILE_PAYLOAD_CACHE_BYTES = 128L * 1024L * 1024L;
   private static final ConcurrentHashMap<ReaderKey, PmTilesRangeReader> SHARED_READERS = new ConcurrentHashMap<>();
   private final URI uri;
   private final int connectTimeoutMs;
   private final int readTimeoutMs;
   private final LoadingCache<PmTilesRangeReader.DirectoryKey, PmTilesRangeReader.Directory> directoryCache;
   private final LoadingCache<Long, TilePayload> tilePayloadCache;
   
   private PmTilesRangeReader.PmTilesHeader header;
   
   private PmTilesRangeReader.Directory rootDirectory;

   public PmTilesRangeReader(String url, int connectTimeoutMs, int readTimeoutMs, int directoryCacheEntries) {
      this.uri = requireHttpUri(URI.create(Objects.requireNonNull(url, "url")));
      this.connectTimeoutMs = Math.max(1, connectTimeoutMs);
      this.readTimeoutMs = Math.max(1, readTimeoutMs);
      this.directoryCache = CacheBuilder.newBuilder()
         .maximumWeight(directoryCacheBudget(directoryCacheEntries))
         .weigher((PmTilesRangeReader.DirectoryKey key, PmTilesRangeReader.Directory directory) -> directoryWeight(directory.entries.size()))
         .build(new CacheLoader<PmTilesRangeReader.DirectoryKey, PmTilesRangeReader.Directory>() {
            public PmTilesRangeReader.Directory load(PmTilesRangeReader.DirectoryKey key) throws Exception {
               return PmTilesRangeReader.this.readDirectory(key.offset, key.length);
            }
         });
      this.tilePayloadCache = CacheBuilder.newBuilder()
         .maximumWeight(MAX_TILE_PAYLOAD_CACHE_BYTES)
         .weigher((Long tileId, TilePayload payload) -> Math.max(64, payload.bytes().length))
         .build(new CacheLoader<Long, TilePayload>() {
            @Override
            public TilePayload load(Long tileId) throws Exception {
               return PmTilesRangeReader.this.loadTilePayload(tileId);
            }
         });
   }

   /**
    * Shares archive metadata and leaf-directory caches between source layers that
    * read the same Overture PMTiles archive. Tile payloads remain owned and
    * decoded by each source-specific cache.
    */
   public static PmTilesRangeReader shared(String url, int connectTimeoutMs, int readTimeoutMs, int directoryCacheEntries) {
      URI uri = requireHttpUri(URI.create(Objects.requireNonNull(url, "url")).normalize());
      ReaderKey key = new ReaderKey(
         uri,
         Math.max(1, connectTimeoutMs),
         Math.max(1, readTimeoutMs),
         Math.max(1, directoryCacheEntries)
      );
      return SHARED_READERS.computeIfAbsent(
         key,
         ignored -> new PmTilesRangeReader(uri.toString(), key.connectTimeoutMs(), key.readTimeoutMs(), key.directoryCacheEntries())
      );
   }

   public synchronized PmTilesRangeReader.PmTilesHeader header() throws IOException {
      if (this.header == null) {
         this.header = this.readHeader();
      }

      return this.header;
   }

   
   public byte[] getTileBytes(int z, int x, int y) throws IOException {
      long tileId = zxyToTileId(z, x, y);
      try {
         TilePayload payload = this.tilePayloadCache.get(tileId);
         return payload.found() ? payload.bytes() : null;
      } catch (ExecutionException error) {
         if (error.getCause() instanceof IOException io) {
            throw io;
         }
         throw new IOException("Failed to read PMTiles tile " + z + "/" + x + "/" + y, error.getCause());
      }
   }

   private TilePayload loadTilePayload(long tileId) throws IOException {
      PmTilesRangeReader.PmTilesHeader header = this.header();
      PmTilesRangeReader.Directory directory = this.getRootDirectory();

      for (int depth = 0; depth < MAX_DIRECTORY_DEPTH; depth++) {
         PmTilesRangeReader.Entry entry = findTile(directory.entries, tileId);
         if (entry == null) {
            return TilePayload.missing();
         }

         if (entry.runLength != 0L) {
            long dataOffset = PmTilesSafety.checkedAdd(header.tileDataOffset, entry.offset, "PMTiles tile data");
            int tileLength = PmTilesSafety.checkedLength(
               entry.length, PmTilesSafety.MAX_COMPRESSED_TILE_BYTES, "PMTiles tile"
            );
            byte[] tileBytes = this.readBytes(dataOffset, tileLength);
            return TilePayload.found(
               PmTilesSafety.decompress(
                  tileBytes,
                  header.tileCompression,
                  PmTilesSafety.MAX_DECOMPRESSED_TILE_BYTES,
                  "PMTiles tile"
               )
            );
         }

         long dirOffset = PmTilesSafety.checkedAdd(header.leafDirectoryOffset, entry.offset, "PMTiles leaf directory");
         long dirLength = entry.length;
         directory = this.getDirectory(dirOffset, dirLength);
      }

      return TilePayload.missing();
   }

   private synchronized PmTilesRangeReader.Directory getRootDirectory() throws IOException {
      if (this.rootDirectory == null) {
         PmTilesRangeReader.PmTilesHeader header = this.header();
         this.rootDirectory = this.getDirectory(header.rootOffset, header.rootLength);
      }

      return this.rootDirectory;
   }

   private PmTilesRangeReader.Directory getDirectory(long offset, long length) throws IOException {
      try {
         return (PmTilesRangeReader.Directory)this.directoryCache.get(new PmTilesRangeReader.DirectoryKey(offset, length));
      } catch (Exception var8) {
         if (var8.getCause() instanceof IOException io) {
            throw io;
         } else {
            throw new IOException("Failed to read PMTiles directory", var8);
         }
      }
   }

   private PmTilesRangeReader.PmTilesHeader readHeader() throws IOException {
      byte[] headerBytes = this.readBytes(0L, HEADER_SIZE);
      if (!"PMTiles".equals(new String(headerBytes, 0, 7, StandardCharsets.US_ASCII))) {
         throw new IOException("PMTiles header missing");
      } else {
         int version = headerBytes[7] & 255;
         if (version != PMTILES_VERSION) {
            throw new IOException("Unsupported PMTiles version " + version);
         } else {
            long rootOffset = readUint64(headerBytes, 8);
            long rootLength = readUint64(headerBytes, 16);
            long leafOffset = readUint64(headerBytes, 40);
            long tileOffset = readUint64(headerBytes, 56);
            int internalCompression = headerBytes[97] & 255;
            int tileCompression = headerBytes[98] & 255;
            int tileType = headerBytes[99] & 255;
            int minZoom = headerBytes[100] & 255;
            int maxZoom = headerBytes[101] & 255;
            validateHeader(rootOffset, rootLength, leafOffset, tileOffset, internalCompression, tileCompression, minZoom, maxZoom);
            return new PmTilesRangeReader.PmTilesHeader(
               rootOffset,
               rootLength,
               leafOffset,
               tileOffset,
               internalCompression,
               tileCompression,
               tileType,
               minZoom,
               maxZoom
            );
         }
      }
   }

   private PmTilesRangeReader.Directory readDirectory(long offset, long length) throws IOException {
      if (length <= 0L) {
         return new PmTilesRangeReader.Directory(List.of());
      } else {
         PmTilesRangeReader.PmTilesHeader header = this.header();
         int compressedLength = PmTilesSafety.checkedLength(
            length, PmTilesSafety.MAX_COMPRESSED_DIRECTORY_BYTES, "PMTiles directory"
         );
         byte[] compressed = this.readBytes(offset, compressedLength);
         byte[] decompressed = PmTilesSafety.decompress(
            compressed,
            header.internalCompression,
            PmTilesSafety.MAX_DECOMPRESSED_DIRECTORY_BYTES,
            "PMTiles directory"
         );
         ByteArrayInputStream input = new ByteArrayInputStream(decompressed);
         long entryCount = PmTilesSafety.readVarint(input);
         long encodedEntryLimit = decompressed.length / 4L;
         if (entryCount > PmTilesSafety.MAX_DIRECTORY_ENTRIES || entryCount > encodedEntryLimit) {
            throw new IOException("PMTiles directory declares an unsafe entry count: " + entryCount);
         }
         int numEntries = (int)entryCount;
         List<PmTilesRangeReader.Entry> entries = new ArrayList<>(numEntries);
         long lastId = 0L;

         for (int i = 0; i < numEntries; i++) {
            long delta = PmTilesSafety.readVarint(input);
            long tileId = PmTilesSafety.checkedAdd(lastId, delta, "PMTiles tile id");
            entries.add(new PmTilesRangeReader.Entry(tileId, 0L, 0L, 0L));
            lastId = tileId;
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).runLength = PmTilesSafety.readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            entries.get(i).length = PmTilesSafety.readVarint(input);
         }

         for (int i = 0; i < numEntries; i++) {
            long tmp = PmTilesSafety.readVarint(input);
            if (i > 0 && tmp == 0L) {
               PmTilesRangeReader.Entry previous = entries.get(i - 1);
               entries.get(i).offset = PmTilesSafety.checkedAdd(previous.offset, previous.length, "PMTiles entry");
            } else {
               if (tmp == 0L) {
                  throw new IOException("PMTiles first directory offset must be positive");
               }
               entries.get(i).offset = tmp - 1L;
            }
         }

         return new PmTilesRangeReader.Directory(entries);
      }
   }

   private byte[] readBytes(long offset, int length) throws IOException {
      if (length <= 0) {
         return new byte[0];
      } else {
         long endInclusive = PmTilesSafety.checkedAdd(offset, length - 1L, "PMTiles HTTP range");
         if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
            throw new IOException("Network access is disabled during managed Distant Horizons generation");
         }
         HttpURLConnection connection = (HttpURLConnection)this.uri.toURL().openConnection();
         connection.setRequestProperty("Range", "bytes=" + offset + "-" + endInclusive);
         connection.setInstanceFollowRedirects(true);
         connection.setConnectTimeout(this.connectTimeoutMs);
         connection.setReadTimeout(this.readTimeoutMs);
         int code = connection.getResponseCode();
         long expectedBytes = connection.getContentLengthLong();
         DownloadProgressReporter.requestStarted(expectedBytes);

         byte[] var9;
         // A fully read response leaves the socket to the JDK keep-alive cache; disconnecting would
         // force a new TCP+TLS handshake (hundreds of ms to S3) for every tile.
         boolean reusable = false;
         try (InputStream input = openStream(connection, code)) {
            if (code == 200) {
               if (offset != 0L) {
                  throw new IOException("PMTiles server ignored a nonzero HTTP range request");
               }
               return readFully(input, length);
            }

            if (code != 206) {
               throw new IOException("PMTiles HTTP error " + code);
            }

            var9 = readFully(input, length);
            reusable = expectedBytes == length;
         } finally {
            DownloadProgressReporter.requestFinished();
            if (!reusable) {
               connection.disconnect();
            }
         }

         return var9;
      }
   }

   private static InputStream openStream(HttpURLConnection connection, int code) throws IOException {
      if (code >= 400) {
         InputStream error = connection.getErrorStream();

         try {
            if (error == null) {
               throw new IOException("PMTiles HTTP error " + code);
            } else {
               byte[] message = error.readNBytes(512);
               throw new IOException("PMTiles HTTP error " + code + ": " + new String(message, StandardCharsets.UTF_8).trim());
            }
         } catch (Throwable var6) {
            if (error != null) {
               try {
                  error.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }
      } else {
         InputStream stream = connection.getInputStream();
         if (stream == null) {
            throw new IOException("PMTiles HTTP error " + code);
         } else {
            return stream;
         }
      }
   }

   private static byte[] readFully(InputStream input, int length) throws IOException {
      byte[] buffer = new byte[length];
      int offset = 0;

      while (offset < length) {
         int read = input.read(buffer, offset, length - offset);
         if (read == -1) {
            throw new EOFException("Unexpected EOF while reading bytes");
         }

         if (read != 0) {
            offset += read;
            DownloadProgressReporter.bytesRead(read);
         }
      }

      return buffer;
   }

   private static long readUint64(byte[] buffer, int pos) {
      return buffer[pos] & 255L
         | (buffer[pos + 1] & 255L) << 8
         | (buffer[pos + 2] & 255L) << 16
         | (buffer[pos + 3] & 255L) << 24
         | (buffer[pos + 4] & 255L) << 32
         | (buffer[pos + 5] & 255L) << 40
         | (buffer[pos + 6] & 255L) << 48
         | (buffer[pos + 7] & 255L) << 56;
   }

   private static long zxyToTileId(int z, int x, int y) {
      if (z < 0 || z > 31) {
         throw new IllegalArgumentException("Tile zoom exceeds 64-bit limit");
      } else {
         int max = (1 << z) - 1;
         if (x >= 0 && y >= 0 && x <= max && y <= max) {
            long acc = ((1L << z * 2) - 1L) / 3L;

            for (int level = z - 1; level >= 0; level--) {
               int scale = 1 << level;
               int rx = scale & x;
               int ry = scale & y;
               acc += (long)(3 * rx ^ ry) << level;
               if (ry == 0) {
                  if (rx != 0) {
                     x = scale - 1 - x;
                     y = scale - 1 - y;
                  }

                  int swapped = x;
                  x = y;
                  y = swapped;
               }
            }

            return acc;
         } else {
            throw new IllegalArgumentException("Tile x/y outside zoom bounds");
         }
      }
   }

   private static void validateHeader(
      long rootOffset,
      long rootLength,
      long leafOffset,
      long tileOffset,
      int internalCompression,
      int tileCompression,
      int minZoom,
      int maxZoom
   ) throws IOException {
      PmTilesSafety.checkedAdd(rootOffset, rootLength, "PMTiles root directory");
      PmTilesSafety.checkedLength(rootLength, PmTilesSafety.MAX_COMPRESSED_DIRECTORY_BYTES, "PMTiles root directory");
      if (leafOffset < 0L || tileOffset < 0L) {
         throw new IOException("PMTiles header contains an unsupported unsigned offset");
      }
      if ((internalCompression != PmTilesSafety.COMPRESSION_NONE && internalCompression != PmTilesSafety.COMPRESSION_GZIP)
         || (tileCompression != PmTilesSafety.COMPRESSION_NONE && tileCompression != PmTilesSafety.COMPRESSION_GZIP)) {
         throw new IOException("PMTiles header uses unsupported compression");
      }
      if (minZoom < 0 || maxZoom > 31 || minZoom > maxZoom) {
         throw new IOException("PMTiles header contains invalid zoom bounds");
      }
   }

   private static PmTilesRangeReader.Entry findTile(List<PmTilesRangeReader.Entry> entries, long tileId) {
      int low = 0;
      int high = entries.size() - 1;

      while (low <= high) {
         int mid = low + high >>> 1;
         int comparison = Long.compare(tileId, entries.get(mid).tileId);
         if (comparison > 0) {
            low = mid + 1;
         } else {
            if (comparison == 0) {
               return entries.get(mid);
            }

            high = mid - 1;
         }
      }

      if (high >= 0) {
         PmTilesRangeReader.Entry entry = entries.get(high);
         if (entry.runLength == 0L) {
            return entry;
         }

         if (tileId - entry.tileId < entry.runLength) {
            return entry;
         }
      }

      return null;
   }

   private static URI requireHttpUri(URI uri) {
      String scheme = uri.getScheme();
      if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
         throw new IllegalArgumentException("PMTiles URL must use HTTP or HTTPS");
      }
      return uri;
   }

   private static long directoryCacheBudget(int configuredEntries) {
      long requested = Math.max(1L, configuredEntries) * 512L * 1024L;
      return Math.max(8L * 1024L * 1024L, Math.min(MAX_DIRECTORY_CACHE_BYTES, requested));
   }

   private static int directoryWeight(int entries) {
      return (int)Math.min(Integer.MAX_VALUE, 128L + Math.max(0L, entries) * 48L);
   }

   private static final class Directory {
      private final List<PmTilesRangeReader.Entry> entries;

      private Directory(List<PmTilesRangeReader.Entry> entries) {
         this.entries = entries;
      }
   }

   private record DirectoryKey(long offset, long length) {
   }

   private record ReaderKey(URI uri, int connectTimeoutMs, int readTimeoutMs, int directoryCacheEntries) {
   }

   private record TilePayload(byte[] bytes, boolean found) {
      private static TilePayload found(byte[] bytes) {
         return new TilePayload(Objects.requireNonNull(bytes, "bytes"), true);
      }

      private static TilePayload missing() {
         return new TilePayload(new byte[0], false);
      }
   }

   private static final class Entry {
      private final long tileId;
      private long offset;
      private long length;
      private long runLength;

      private Entry(long tileId, long offset, long length, long runLength) {
         this.tileId = tileId;
         this.offset = offset;
         this.length = length;
         this.runLength = runLength;
      }
   }

   public static final class PmTilesHeader {
      private final long rootOffset;
      private final long rootLength;
      private final long leafDirectoryOffset;
      private final long tileDataOffset;
      private final int internalCompression;
      private final int tileCompression;
      private final int tileType;
      private final int minZoom;
      private final int maxZoom;

      private PmTilesHeader(
         long rootOffset,
         long rootLength,
         long leafDirectoryOffset,
         long tileDataOffset,
         int internalCompression,
         int tileCompression,
         int tileType,
         int minZoom,
         int maxZoom
      ) {
         this.rootOffset = rootOffset;
         this.rootLength = rootLength;
         this.leafDirectoryOffset = leafDirectoryOffset;
         this.tileDataOffset = tileDataOffset;
         this.internalCompression = internalCompression;
         this.tileCompression = tileCompression;
         this.tileType = tileType;
         this.minZoom = minZoom;
         this.maxZoom = maxZoom;
      }

      public int minZoom() {
         return this.minZoom;
      }

      public int maxZoom() {
         return this.maxZoom;
      }

      public int tileType() {
         return this.tileType;
      }
   }
}
