package com.yucareux.tellus.worldgen;

/**
 * The vanilla snow biome that mapped snow and ice belong to, from the shape of the ground: glaciers
 * and ice caps are frozen peaks, steep faces and sharp crests are jagged peaks, and the snowfields
 * between them are snowy slopes. Blocks come from the snow map either way; the biome sets the name,
 * the animals and the weather.
 *
 * <p>The shape is read over about 240 m, so one biome covers a glacier, a face or an arete rather
 * than flickering between single columns.</p>
 */
public final class SnowBiomePolicy {
   static final double BASELINE_METERS = 240.0;
   static final int MIN_BASELINE_BLOCKS = 8;
   static final double GENTLE_DEGREES = 15.0;
   static final double STEEP_DEGREES = 35.0;
   // A crest whose sides fall at least this steeply on either axis, measured over the baseline:
   // aretes and sharp summits, but not the rounded domes of ice caps and snow summits.
   static final double CREST_SIDE_DEGREES = 30.0;
   private static final double CREST_SIDE_GRADIENT = Math.tan(Math.toRadians(CREST_SIDE_DEGREES));

   private SnowBiomePolicy() {
   }

   /** Distance in blocks from a point to the four elevations its snow biome reads. */
   public static int baselineBlocks(double worldScale) {
      return worldScale > 0.0 ? Math.max(MIN_BASELINE_BLOCKS, (int)Math.ceil(BASELINE_METERS / worldScale)) : MIN_BASELINE_BLOCKS;
   }

   /**
    * Snow biome for the elevations at a point and one baseline east, west, north and south of it,
    * those neighbours lying {@code eastWestMeters} and {@code northSouthMeters} away on the ground.
    * Missing elevations keep frozen peaks.
    */
   public static SnowBiomePolicy.Kind classify(
      double centerMeters,
      double eastMeters,
      double westMeters,
      double northMeters,
      double southMeters,
      double eastWestMeters,
      double northSouthMeters
   ) {
      if (!Double.isFinite(centerMeters)
         || !Double.isFinite(eastMeters)
         || !Double.isFinite(westMeters)
         || !Double.isFinite(northMeters)
         || !Double.isFinite(southMeters)
         || !(eastWestMeters > 0.0)
         || !(northSouthMeters > 0.0)) {
         return SnowBiomePolicy.Kind.FROZEN_PEAKS;
      }

      double riseEast = (eastMeters - westMeters) / (2.0 * eastWestMeters);
      double riseSouth = (southMeters - northMeters) / (2.0 * northSouthMeters);
      double slopeDegrees = Math.toDegrees(Math.atan(Math.hypot(riseEast, riseSouth)));
      double crestEastWest = (centerMeters - (eastMeters + westMeters) * 0.5) / eastWestMeters;
      double crestNorthSouth = (centerMeters - (northMeters + southMeters) * 0.5) / northSouthMeters;
      if (slopeDegrees >= STEEP_DEGREES || Math.max(crestEastWest, crestNorthSouth) >= CREST_SIDE_GRADIENT) {
         return SnowBiomePolicy.Kind.JAGGED_PEAKS;
      }

      return slopeDegrees < GENTLE_DEGREES ? SnowBiomePolicy.Kind.FROZEN_PEAKS : SnowBiomePolicy.Kind.SNOWY_SLOPES;
   }

   public enum Kind {
      FROZEN_PEAKS,
      SNOWY_SLOPES,
      JAGGED_PEAKS
   }
}
