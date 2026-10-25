package com.yucareux.tellus.world.data.canopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class LercU8DecoderTest {
   private static final byte[] EMPTY_ARCGIS_TILE = Base64.getDecoder()
      .decode(
         "TGVyYzIgBQAAAEIx+msAAQAAAAEAAAEAAAAAAAEACAAAAEYAAAABAAAAAAAAAAAA4D8AAAAAAAAAAAAAAAAAAAAAAAAAAA=="
      );

   @Test
   void decodesOfficialEmptyLivingAtlasTile() throws Exception {
      LercU8Decoder.DecodedRaster raster = new LercU8Decoder().decode(EMPTY_ARCGIS_TILE);

      assertEquals(256, raster.width());
      assertEquals(256, raster.height());
      assertEquals(256 * 256, raster.pixels().length);
      for (int index = 0; index < raster.pixels().length; index++) {
         assertTrue(!raster.valid(index) || raster.pixels()[index] == 0);
      }
   }

   @Test
   void decodesOnInterruptedThreadAndKeepsTheInterrupt() throws Exception {
      LercU8Decoder decoder = new LercU8Decoder();
      Thread.currentThread().interrupt();
      try {
         // The interpreter would otherwise abandon the call (and its allocator state) mid-decode.
         assertEquals(256 * 256, decoder.decode(EMPTY_ARCGIS_TILE).pixels().length);
         assertTrue(Thread.currentThread().isInterrupted(), "the caller's interrupt must survive the decode");
      } finally {
         Thread.interrupted();
      }
      assertEquals(256, decoder.decode(EMPTY_ARCGIS_TILE).width());
   }
}
