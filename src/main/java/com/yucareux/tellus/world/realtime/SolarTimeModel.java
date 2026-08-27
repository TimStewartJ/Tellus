package com.yucareux.tellus.world.realtime;

/**
 * Maps real-world instants at a geographic position onto Minecraft's 24000-tick day so that the vanilla
 * sun tracks the real sun.
 *
 * <p>Vanilla's {@code visual/sun_angle} timeline puts the sun exactly on the eastern horizon at tick 0,
 * at the zenith at tick 6000, on the western horizon at tick 12000 and at the nadir at tick 18000. This
 * model anchors those four ticks to the real sunrise, solar noon, sunset and solar midnight computed with
 * the NOAA solar position approximation, then interpolates linearly inside the day and inside the night.
 * Real day length, season and latitude therefore drive how long the Minecraft day and night last, which
 * the previous linear "06:00 local equals tick 0" mapping could not express.</p>
 *
 * <p>Polar day and polar night are handled explicitly: the sun stays above (or below) the horizon and
 * moves between a low point at real solar midnight and its highest (or least deep) point at solar noon.</p>
 *
 * <p>All methods are pure and safe to call from any thread.</p>
 */
public final class SolarTimeModel {
   public static final int TICKS_PER_DAY = 24000;
   public static final int SUNRISE_TICK = 0;
   public static final int NOON_TICK = 6000;
   public static final int SUNSET_TICK = 12000;
   public static final int MIDNIGHT_TICK = 18000;
   /** Ticks the sun stays away from the horizon at real midnight during polar day. */
   static final int POLAR_DAY_HORIZON_MARGIN_TICKS = 800;
   /** Tick used at real noon during polar night: just past sunset, i.e. deep civil twilight. */
   static final int POLAR_NIGHT_NOON_TICK = 12800;
   private static final double MINUTES_PER_DAY = 1440.0;
   private static final double MILLIS_PER_DAY = 86_400_000.0;
   private static final double JULIAN_UNIX_EPOCH = 2_440_587.5;
   private static final double J2000 = 2_451_545.0;
   /** Geometric zenith of sunrise/sunset including standard refraction and the solar radius. */
   private static final double SUNRISE_ZENITH_DEGREES = 90.833;
   private static final double SYNODIC_MONTH_DAYS = 29.530588853;
   /** Julian day of the new moon of 2000-01-06 18:14 UTC, the usual phase reference epoch. */
   private static final double REFERENCE_NEW_MOON_JD = 2_451_550.26;
   private static final int MOON_PHASES = 8;
   /** Vanilla moon phase index of the new moon ({@code 0} is the full moon). */
   private static final int VANILLA_NEW_MOON_INDEX = 4;

   private SolarTimeModel() {
   }

   /**
    * Describes the solar day containing {@code epochMillis} at the given position.
    *
    * @param solarNoonUtcMinutes minutes after UTC midnight of local solar noon (may fall outside 0..1440 before wrapping)
    * @param halfDayMinutes minutes between solar noon and sunset; {@code NaN} when the sun never rises or never sets
    * @param declinationDegrees solar declination in degrees
    */
   public record SolarDay(double solarNoonUtcMinutes, double halfDayMinutes, boolean polarDay, boolean polarNight, double declinationDegrees) {
      public double sunriseUtcMinutes() {
         return wrapMinutes(this.solarNoonUtcMinutes - this.halfDayMinutes);
      }

      public double sunsetUtcMinutes() {
         return wrapMinutes(this.solarNoonUtcMinutes + this.halfDayMinutes);
      }

      public double dayLengthMinutes() {
         if (this.polarDay) {
            return MINUTES_PER_DAY;
         }
         return this.polarNight ? 0.0 : this.halfDayMinutes * 2.0;
      }
   }

   public static SolarDay solarDay(long epochMillis, double latitudeDegrees, double longitudeDegrees) {
      double latitude = clamp(latitudeDegrees, -89.999, 89.999);
      double longitude = wrapLongitude(longitudeDegrees);
      double julianDay = epochMillis / MILLIS_PER_DAY + JULIAN_UNIX_EPOCH;
      double century = (julianDay - J2000) / 36525.0;
      double meanLongitude = floorMod(280.46646 + century * (36000.76983 + century * 0.0003032), 360.0);
      double meanAnomaly = 357.52911 + century * (35999.05029 - 0.0001537 * century);
      double eccentricity = 0.016708634 - century * (0.000042037 + 0.0000001267 * century);
      double anomalyRad = Math.toRadians(meanAnomaly);
      double equationOfCenter = Math.sin(anomalyRad) * (1.914602 - century * (0.004817 + 0.000014 * century))
         + Math.sin(2.0 * anomalyRad) * (0.019993 - 0.000101 * century)
         + Math.sin(3.0 * anomalyRad) * 0.000289;
      double trueLongitude = meanLongitude + equationOfCenter;
      double omega = Math.toRadians(125.04 - 1934.136 * century);
      double apparentLongitude = trueLongitude - 0.00569 - 0.00478 * Math.sin(omega);
      double meanObliquity = 23.0 + (26.0 + (21.448 - century * (46.815 + century * (0.00059 - century * 0.001813))) / 60.0) / 60.0;
      double obliquity = meanObliquity + 0.00256 * Math.cos(omega);
      double declination = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(obliquity)) * Math.sin(Math.toRadians(apparentLongitude))));
      double y = Math.tan(Math.toRadians(obliquity / 2.0));
      y *= y;
      double meanLongitudeRad = Math.toRadians(meanLongitude);
      double equationOfTimeMinutes = 4.0
         * Math.toDegrees(
            y * Math.sin(2.0 * meanLongitudeRad)
               - 2.0 * eccentricity * Math.sin(anomalyRad)
               + 4.0 * eccentricity * y * Math.sin(anomalyRad) * Math.cos(2.0 * meanLongitudeRad)
               - 0.5 * y * y * Math.sin(4.0 * meanLongitudeRad)
               - 1.25 * eccentricity * eccentricity * Math.sin(2.0 * anomalyRad)
         );
      double solarNoonUtcMinutes = 720.0 - 4.0 * longitude - equationOfTimeMinutes;
      double latitudeRad = Math.toRadians(latitude);
      double declinationRad = Math.toRadians(declination);
      double cosHourAngle = Math.cos(Math.toRadians(SUNRISE_ZENITH_DEGREES)) / (Math.cos(latitudeRad) * Math.cos(declinationRad))
         - Math.tan(latitudeRad) * Math.tan(declinationRad);
      if (cosHourAngle >= 1.0) {
         return new SolarDay(solarNoonUtcMinutes, Double.NaN, false, true, declination);
      }
      if (cosHourAngle <= -1.0) {
         return new SolarDay(solarNoonUtcMinutes, Double.NaN, true, false, declination);
      }
      double hourAngleDegrees = Math.toDegrees(Math.acos(cosHourAngle));
      return new SolarDay(solarNoonUtcMinutes, hourAngleDegrees * 4.0, false, false, declination);
   }

   /** Returns the vanilla tick of day (0..23999) whose sun position matches the real sun at the instant and position. */
   public static int tickOfDay(long epochMillis, double latitudeDegrees, double longitudeDegrees) {
      SolarDay day = solarDay(epochMillis, latitudeDegrees, longitudeDegrees);
      double utcMinutes = floorMod(epochMillis / 60_000.0, MINUTES_PER_DAY);
      // Signed minutes since solar noon, in -720..720.
      double sinceNoon = wrapSigned(utcMinutes - day.solarNoonUtcMinutes());
      double tick;
      if (day.polarDay()) {
         double span = NOON_TICK - POLAR_DAY_HORIZON_MARGIN_TICKS;
         tick = NOON_TICK + sinceNoon / 720.0 * span;
      } else if (day.polarNight()) {
         double span = MIDNIGHT_TICK - POLAR_NIGHT_NOON_TICK;
         tick = POLAR_NIGHT_NOON_TICK + Math.abs(sinceNoon) / 720.0 * span;
      } else {
         double half = day.halfDayMinutes();
         if (Math.abs(sinceNoon) <= half) {
            tick = NOON_TICK + sinceNoon / half * (NOON_TICK - SUNRISE_TICK);
         } else {
            double nightLength = MINUTES_PER_DAY - 2.0 * half;
            double sinceSunset = sinceNoon > 0.0 ? sinceNoon - half : sinceNoon + MINUTES_PER_DAY - half;
            tick = SUNSET_TICK + sinceSunset / nightLength * (TICKS_PER_DAY - SUNSET_TICK);
         }
      }
      int rounded = (int)Math.floor(tick);
      return Math.floorMod(rounded, TICKS_PER_DAY);
   }

   /** Returns the vanilla moon phase index (0 = full moon, 4 = new moon) of the real moon at the instant. */
   public static int moonPhaseIndex(long epochMillis) {
      double julianDay = epochMillis / MILLIS_PER_DAY + JULIAN_UNIX_EPOCH;
      double age = floorMod(julianDay - REFERENCE_NEW_MOON_JD, SYNODIC_MONTH_DAYS);
      double fraction = age / SYNODIC_MONTH_DAYS;
      int sinceNewMoon = (int)Math.round(fraction * MOON_PHASES) % MOON_PHASES;
      return (sinceNewMoon + VANILLA_NEW_MOON_INDEX) % MOON_PHASES;
   }

   /**
    * Chooses the Minecraft day number used as the base of the clock so that {@code dayNumber % 8}
    * equals the real moon phase. The result grows with real local calendar days so that "days played"
    * style counters stay monotonic between restarts.
    */
   public static long alignedDayNumber(long epochMillis, double longitudeDegrees) {
      int phase = moonPhaseIndex(epochMillis);
      long localDay = (long)Math.floor((epochMillis / MILLIS_PER_DAY) + wrapLongitude(longitudeDegrees) / 360.0);
      long candidate = Math.max(0L, localDay);
      int offset = (int)Math.floorMod(phase - candidate, (long)MOON_PHASES);
      return candidate + offset;
   }

   static double wrapMinutes(double minutes) {
      return floorMod(minutes, MINUTES_PER_DAY);
   }

   private static double wrapSigned(double minutes) {
      double wrapped = floorMod(minutes + 720.0, MINUTES_PER_DAY) - 720.0;
      return wrapped;
   }

   private static double wrapLongitude(double longitude) {
      double wrapped = floorMod(longitude + 180.0, 360.0) - 180.0;
      return Double.isFinite(wrapped) ? wrapped : 0.0;
   }

   private static double floorMod(double value, double modulus) {
      double result = value % modulus;
      return result < 0.0 ? result + modulus : result;
   }

   private static double clamp(double value, double min, double max) {
      if (!Double.isFinite(value)) {
         return 0.0;
      }
      return Math.max(min, Math.min(max, value));
   }
}
