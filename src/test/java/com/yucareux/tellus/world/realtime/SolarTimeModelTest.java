package com.yucareux.tellus.world.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SolarTimeModelTest {
   private static final double SEATTLE_LAT = 47.6062;
   private static final double SEATTLE_LON = -122.3321;
   private static final double SVALBARD_LAT = 78.2232;
   private static final double SVALBARD_LON = 15.6267;

   private static void assertCircular(int expectedTick, int actualTick, int tolerance) {
      int distance = Math.abs(actualTick - expectedTick);
      distance = Math.min(distance, SolarTimeModel.TICKS_PER_DAY - distance);
      assertTrue(distance <= tolerance, "expected tick " + expectedTick + " +/- " + tolerance + " but was " + actualTick);
   }

   private static long utc(int year, int month, int day, int hour, int minute) {
      return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli();
   }

   @Test
   void seattleSummerSolsticeSunriseAndSunsetMatchAlmanac() {
      // Almanac (timeanddate.com, PDT = UTC-7): sunrise 05:11, sunset 21:10 on 2026-06-21.
      SolarTimeModel.SolarDay day = SolarTimeModel.solarDay(utc(2026, 6, 21, 19, 0), SEATTLE_LAT, SEATTLE_LON);
      assertFalse(day.polarDay());
      assertFalse(day.polarNight());
      double sunrisePdt = SolarTimeModel.wrapMinutes(day.sunriseUtcMinutes() - 7 * 60);
      double sunsetPdt = SolarTimeModel.wrapMinutes(day.sunsetUtcMinutes() - 7 * 60);
      assertEquals(5 * 60 + 11, sunrisePdt, 8.0, "sunrise minutes PDT");
      assertEquals(21 * 60 + 10, sunsetPdt, 8.0, "sunset minutes PDT");
      assertEquals(15 * 60 + 59, day.dayLengthMinutes(), 10.0, "day length");
   }

   @Test
   void equatorOnEquinoxHasTwelveHourDay() {
      SolarTimeModel.SolarDay day = SolarTimeModel.solarDay(utc(2026, 3, 20, 12, 0), 0.0, 0.0);
      assertEquals(12 * 60, day.dayLengthMinutes(), 12.0);
   }

   @Test
   void svalbardHasPolarDayInJuneAndPolarNightInDecember() {
      assertTrue(SolarTimeModel.solarDay(utc(2026, 6, 21, 12, 0), SVALBARD_LAT, SVALBARD_LON).polarDay());
      assertTrue(SolarTimeModel.solarDay(utc(2026, 12, 21, 12, 0), SVALBARD_LAT, SVALBARD_LON).polarNight());
   }

   @Test
   void sunriseNoonSunsetMapOntoVanillaAnchors() {
      long base = utc(2026, 6, 21, 0, 0);
      SolarTimeModel.SolarDay day = SolarTimeModel.solarDay(base, SEATTLE_LAT, SEATTLE_LON);
      long sunrise = base + Math.round(day.sunriseUtcMinutes() * 60_000.0);
      long noon = base + Math.round(SolarTimeModel.wrapMinutes(day.solarNoonUtcMinutes()) * 60_000.0);
      long sunset = base + Math.round(day.sunsetUtcMinutes() * 60_000.0);
      assertCircular(SolarTimeModel.SUNRISE_TICK, SolarTimeModel.tickOfDay(sunrise, SEATTLE_LAT, SEATTLE_LON), 40);
      assertCircular(SolarTimeModel.NOON_TICK, SolarTimeModel.tickOfDay(noon, SEATTLE_LAT, SEATTLE_LON), 40);
      assertCircular(SolarTimeModel.SUNSET_TICK, SolarTimeModel.tickOfDay(sunset, SEATTLE_LAT, SEATTLE_LON), 40);
      // Real midnight falls in the middle of the Minecraft night, and the whole night is compressed into ticks 12000..24000.
      long midnight = noon + 12L * 3_600_000L;
      assertCircular(SolarTimeModel.MIDNIGHT_TICK, SolarTimeModel.tickOfDay(midnight, SEATTLE_LAT, SEATTLE_LON), 60);
   }

   @Test
   void tickOfDayIsMonotonicAcrossARegularDay() {
      long start = utc(2026, 9, 1, 0, 0);
      int previous = SolarTimeModel.tickOfDay(start, SEATTLE_LAT, SEATTLE_LON);
      int wraps = 0;
      for (int minute = 1; minute <= 1440; minute++) {
         int tick = SolarTimeModel.tickOfDay(start + minute * 60_000L, SEATTLE_LAT, SEATTLE_LON);
         if (tick < previous) {
            wraps++;
         }
         previous = tick;
      }
      assertEquals(1, wraps, "exactly one wrap from 23999 back to 0 per real day");
   }

   @Test
   void polarDayKeepsSunAboveHorizonAndPolarNightKeepsItBelow() {
      for (int hour = 0; hour < 24; hour++) {
         int summer = SolarTimeModel.tickOfDay(utc(2026, 6, 21, hour, 0), SVALBARD_LAT, SVALBARD_LON);
         assertTrue(summer >= SolarTimeModel.POLAR_DAY_HORIZON_MARGIN_TICKS && summer <= SolarTimeModel.SUNSET_TICK - SolarTimeModel.POLAR_DAY_HORIZON_MARGIN_TICKS, "polar day tick " + summer);
         int winter = SolarTimeModel.tickOfDay(utc(2026, 12, 21, hour, 0), SVALBARD_LAT, SVALBARD_LON);
         assertTrue(winter >= SolarTimeModel.POLAR_NIGHT_NOON_TICK && winter <= SolarTimeModel.MIDNIGHT_TICK, "polar night tick " + winter);
      }
   }

   @Test
   void moonPhaseMatchesKnownFullAndNewMoons() {
      // Full moon 2026-01-03 10:03 UTC, new moon 2026-01-18 19:52 UTC (USNO).
      assertEquals(0, SolarTimeModel.moonPhaseIndex(utc(2026, 1, 3, 10, 0)), "full moon");
      assertEquals(4, SolarTimeModel.moonPhaseIndex(utc(2026, 1, 18, 20, 0)), "new moon");
      // Full moon 2026-08-28 04:18 UTC.
      assertEquals(0, SolarTimeModel.moonPhaseIndex(utc(2026, 8, 28, 4, 0)), "August full moon");
   }

   @Test
   void alignedDayNumberCarriesMoonPhaseAndNeverDecreases() {
      long start = utc(2026, 1, 1, 0, 0);
      long previous = -1L;
      for (int day = 0; day < 120; day++) {
         long instant = start + day * 86_400_000L + 12L * 3_600_000L;
         long number = SolarTimeModel.alignedDayNumber(instant, SEATTLE_LON);
         assertEquals(SolarTimeModel.moonPhaseIndex(instant), (int)(number % 8), "phase on day " + day);
         assertTrue(number >= previous, "day number must not decrease");
         previous = number;
      }
   }

   @Test
   void extremeInputsAreClampedInsteadOfThrowing() {
      SolarTimeModel.tickOfDay(Instant.EPOCH.toEpochMilli(), 90.0, 200.0);
      SolarTimeModel.tickOfDay(Long.MAX_VALUE / 4, Double.NaN, Double.NaN);
      SolarTimeModel.alignedDayNumber(0L, Double.POSITIVE_INFINITY);
   }
}

