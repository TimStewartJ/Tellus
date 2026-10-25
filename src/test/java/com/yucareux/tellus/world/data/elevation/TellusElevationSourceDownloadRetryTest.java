package com.yucareux.tellus.world.data.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.net.httpserver.HttpServer;
import com.yucareux.tellus.cache.TellusCacheDomain;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class TellusElevationSourceDownloadRetryTest {
   @TempDir
   Path gameDirectory;

   @BeforeAll
   static void bootstrapMinecraft() {
      // Building the source loads TellusCommon, whose registry keys need vanilla's bootstrap.
      assumeFalse(
         TellusElevationSourceDownloadRetryTest.class.getClassLoader().getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries"
      );
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void failedDownloadIsRetriedAfterAPauseInsteadOfCachedAsMissing() throws Exception {
      AtomicInteger requests = new AtomicInteger();
      HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", exchange -> {
         requests.incrementAndGet();
         exchange.sendResponseHeaders(503, -1);
         exchange.close();
      });
      server.start();
      String previousGameDir = System.getProperty("tellus.gameDir");
      System.setProperty("tellus.gameDir", this.gameDirectory.toString());
      try {
         TellusElevationSource source = new TellusElevationSource();
         Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
         Constructor<?> keyConstructor = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
         keyConstructor.setAccessible(true);
         Method loadTile = TellusElevationSource.class.getDeclaredMethod(
            "loadTile", keyClass, Path.class, TellusCacheDomain.class, String.class, String.class
         );
         loadTile.setAccessible(true);
         Object key = keyConstructor.newInstance(11, 1, 1);
         // The path samplers use for this tile, so they see the failure recorded below.
         Method cachePathOf = TellusElevationSource.class.getDeclaredMethod("cachePath", keyClass);
         cachePathOf.setAccessible(true);
         Path cachePath = (Path)cachePathOf.invoke(source, key);
         String endpoint = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
         Executable load = () -> {
            try {
               loadTile.invoke(source, key, cachePath, TellusCacheDomain.TERRAIN, endpoint, "Test");
            } catch (InvocationTargetException error) {
               throw error.getCause();
            }
         };

         // Old behavior returned MISSING_RASTER here, which the tile cache then kept for the whole session.
         assertThrows(IOException.class, load);
         int attempts = requests.get();
         assertThrows(IOException.class, load);
         assertEquals(attempts, requests.get(), "a tile that just failed is not downloaded again right away");

         source.retryMissingTiles();
         assertThrows(IOException.class, load);
         assertEquals(2 * attempts, requests.get(), "a retry pass downloads the failed tile again");

         // Chunk generation reads tiles through getTile and defers a chunk when this count changes.
         Method getTile = TellusElevationSource.class.getDeclaredMethod("getTile", keyClass);
         getTile.setAccessible(true);
         int unavailableReads = TellusElevationSource.unavailableTileReads();
         assertNull(getTile.invoke(source, key));
         assertEquals(unavailableReads + 1, TellusElevationSource.unavailableTileReads());
         assertEquals(2 * attempts, requests.get(), "a sampler does not download a tile that just failed");
      } finally {
         if (previousGameDir == null) {
            System.clearProperty("tellus.gameDir");
         } else {
            System.setProperty("tellus.gameDir", previousGameDir);
         }
         server.stop(0);
      }
   }
}
