package com.yucareux.tellus.world.data.source;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NominatimGeocoderTest {
   @Test
   void requestsWaitForTheirOneSecondSlot() throws IOException {
      NominatimGeocoder.awaitRequestSlot();
      long start = System.nanoTime();
      NominatimGeocoder.awaitRequestSlot();
      long waited = System.nanoTime() - start;
      // Nominatim's usage policy allows at most one request per second.
      assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(950L), "the second request waited only " + TimeUnit.NANOSECONDS.toMillis(waited) + " ms");
   }

   @Test
   void formatsCityStateAndCountry() {
      NominatimGeocoder.Location location = NominatimGeocoder.parseLocation(
         JsonParser.parseString(
               """
               {
                 "address": {
                   "city": "Guadalajara",
                   "state": "Jalisco",
                   "country": "México"
                 }
               }
               """
            )
            .getAsJsonObject()
      );

      assertNotNull(location);
      assertEquals("Guadalajara, Jalisco, México", location.displayName());
   }

   @Test
   void fallsBackFromCityToTown() {
      NominatimGeocoder.Location location = NominatimGeocoder.parseLocation(
         JsonParser.parseString(
               """
               {
                 "address": {
                   "town": "Banff",
                   "state": "Alberta",
                   "country": "Canada"
                 }
               }
               """
            )
            .getAsJsonObject()
      );

      assertNotNull(location);
      assertEquals("Banff, Alberta, Canada", location.displayName());
   }
}
