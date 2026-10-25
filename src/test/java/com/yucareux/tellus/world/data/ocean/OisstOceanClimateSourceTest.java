package com.yucareux.tellus.world.data.ocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Parses NOAA PSL OPeNDAP ASCII blocks (16x16 fixtures captured from the 1991-2020 OISST climatology). */
class OisstOceanClimateSourceTest {
   @Test
   void parsesMonthlySeaSurfaceTemperatureBlockWithLandGaps() throws IOException {
      double[] values = OisstOceanClimateSource.parseAsciiGrid(fixture("sst-block-27-63.ascii"), "sst", 16);
      assertEquals(12 * 256, values.length);
      int finite = 0;
      int missing = 0;
      for (double value : values) {
         if (Double.isFinite(value)) {
            finite++;
            assertTrue(value > 10.0 && value < 35.0, "tropical Pacific SST out of range: " + value);
         } else {
            missing++;
         }
      }
      // The block off Jalisco contains open ocean and coastline (land cells are fill values).
      assertTrue(finite > 12 * 128, "expected mostly ocean, got " + finite);
      assertTrue(missing > 0, "expected land cells in the coastal block");
      // First value of the first row, as published.
      assertEquals(25.828186, values[0], 1.0E-6);
   }

   @Test
   void parsesIceBlockAsFractions() throws IOException {
      double[] values = OisstOceanClimateSource.parseAsciiGrid(fixture("icec-block-27-63.ascii"), "icec", 16);
      assertEquals(12 * 256, values.length);
      for (double value : values) {
         assertTrue(Double.isNaN(value) || value >= 0.0 && value <= 1.0, "ice fraction out of range: " + value);
      }
   }

   @Test
   void rejectsTruncatedGrid() {
      String truncated = "sst.sst[12][16][16]\n[0][0], 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16\n\n";
      assertThrows(IOException.class, () -> OisstOceanClimateSource.parseAsciiGrid(truncated.getBytes(StandardCharsets.UTF_8), "sst", 16));
   }

   private static byte[] fixture(String name) throws IOException {
      try (InputStream input = Objects.requireNonNull(
         OisstOceanClimateSourceTest.class.getResourceAsStream("/oisst/" + name), "missing fixture " + name)) {
         return input.readAllBytes();
      }
   }
}
