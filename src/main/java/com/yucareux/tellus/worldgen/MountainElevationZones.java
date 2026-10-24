package com.yucareux.tellus.worldgen;

/**
 * Places the mountain surface anchors at real elevations.
 *
 * <p>{@link MountainSurfaceRules} compares a column's height with two anchors,
 * highland ({@value MountainSurfaceRules#SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA})
 * and alpine ({@value MountainSurfaceRules#SURFACE_ALPINE_HEIGHT_ABOVE_SEA}).
 * Those were block heights above sea level, so the same rock sat in a different
 * zone at every world scale and latitude: at 1:30 the alpine anchor meant about
 * 4,200 m in the Alps but 2,800 m in Norway, where nothing reaches it. The
 * anchors now follow a treeline: {@link LocalTreeline}'s, from the local
 * climate, with the approximate climatic treeline for the column's latitude
 * below as its fallback. Highland starts {@value #HIGHLAND_BELOW_TREELINE_METERS} m below it
 * and alpine {@value #ALPINE_ABOVE_TREELINE_METERS} m above it, and
 * {@link #zoneHeight} maps a block height onto the anchors' scale so the rules
 * keep their units.</p>
 */
public final class MountainElevationZones {
   static final double HIGHLAND_BELOW_TREELINE_METERS = 400.0;
   static final double ALPINE_ABOVE_TREELINE_METERS = 300.0;
   static final double MIN_HIGHLAND_METERS = 400.0;
   static final double MIN_ALPINE_METERS = 900.0;
   private static final double MIN_ANCHOR_BLOCKS = 1.0;
   static final int UNIFORM_ZONE_HEIGHT = 80;
   private static final int MAX_ZONE_HEIGHT = 65536;
   // Approximate climatic treelines (m). Southern mid-latitudes are oceanic and
   // markedly lower than northern ones at the same latitude.
   private static final double[] NORTH_LATITUDES = {0.0, 15.0, 20.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0, 65.0, 70.0, 75.0};
   private static final double[] NORTH_TREELINES = {3700.0, 3700.0, 3800.0, 3900.0, 3300.0, 2900.0, 2200.0, 1700.0, 1100.0, 800.0, 500.0, 200.0, 0.0};
   private static final double[] SOUTH_LATITUDES = {0.0, 15.0, 20.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0};
   private static final double[] SOUTH_TREELINES = {3700.0, 3700.0, 3800.0, 3500.0, 2800.0, 1900.0, 1250.0, 900.0, 550.0, 200.0};

   private MountainElevationZones() {
   }

   public static double treelineMeters(double latitude) {
      if (!Double.isFinite(latitude)) {
         return NORTH_TREELINES[0];
      }

      return latitude >= 0.0
         ? interpolate(NORTH_LATITUDES, NORTH_TREELINES, latitude)
         : interpolate(SOUTH_LATITUDES, SOUTH_TREELINES, -latitude);
   }

   public static double highlandMeters(double latitude) {
      return highlandMetersForTreeline(treelineMeters(latitude));
   }

   public static double alpineMeters(double latitude) {
      return alpineMetersForTreeline(treelineMeters(latitude));
   }

   public static double highlandMetersForTreeline(double treelineMeters) {
      return Math.max(MIN_HIGHLAND_METERS, treelineMeters - HIGHLAND_BELOW_TREELINE_METERS);
   }

   public static double alpineMetersForTreeline(double treelineMeters) {
      return Math.max(MIN_ALPINE_METERS, treelineMeters + ALPINE_ABOVE_TREELINE_METERS);
   }

   /** Anchor heights in blocks above sea level for the latitude curve's treeline. */
   public static Band band(
      double latitude,
      double verticalWorldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      return bandForTreeline(
         treelineMeters(latitude), latitude, verticalWorldScale, terrestrialHeightScale, oceanicHeightScale,
         experimentalIncreaseHeight, automaticHeightScaling
      );
   }

   /**
    * Anchor heights in blocks above sea level around a treeline, such as {@link LocalTreeline}'s,
    * using the terrain's own height transform.
    */
   public static Band bandForTreeline(
      double treelineMeters,
      double latitude,
      double verticalWorldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      double treeline = Double.isFinite(treelineMeters) ? treelineMeters : treelineMeters(latitude);
      double highland = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         highlandMetersForTreeline(treeline), latitude, verticalWorldScale, terrestrialHeightScale, oceanicHeightScale,
         experimentalIncreaseHeight, automaticHeightScaling
      );
      double alpine = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         alpineMetersForTreeline(treeline), latitude, verticalWorldScale, terrestrialHeightScale, oceanicHeightScale,
         experimentalIncreaseHeight, automaticHeightScaling
      );
      return Band.of(highland, alpine);
   }

   /**
    * Height in blocks below which a column's zone height stays under
    * {@value #UNIFORM_ZONE_HEIGHT} for every treeline from {@code lowestTreelineMeters}
    * up. The mountain rules read no difference between such heights (snow retention,
    * the lowest reader, starts at 87), so callers can skip the local treeline there.
    */
   public static double uniformZoneCeilingBlocks(
      double lowestTreelineMeters,
      double latitude,
      double verticalWorldScale,
      double terrestrialHeightScale,
      double oceanicHeightScale,
      boolean experimentalIncreaseHeight,
      boolean automaticHeightScaling
   ) {
      double highland = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
         highlandMetersForTreeline(lowestTreelineMeters), latitude, verticalWorldScale, terrestrialHeightScale, oceanicHeightScale,
         experimentalIncreaseHeight, automaticHeightScaling
      );
      return Double.isFinite(highland)
         ? Math.max(MIN_ANCHOR_BLOCKS, highland) * UNIFORM_ZONE_HEIGHT / MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA
         : 0.0;
   }

   /**
    * Maps a height above sea level onto the anchors' scale: the highland anchor
    * becomes {@value MountainSurfaceRules#SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA}, the
    * alpine anchor {@value MountainSurfaceRules#SURFACE_ALPINE_HEIGHT_ABOVE_SEA},
    * and heights above alpine continue at the highland-to-alpine rate.
    */
   public static int zoneHeight(int heightAboveSea, Band band) {
      if (heightAboveSea <= 0 || band == null) {
         return heightAboveSea;
      }

      double highlandZone = MountainSurfaceRules.SURFACE_HIGHLAND_HEIGHT_ABOVE_SEA;
      double alpineZone = MountainSurfaceRules.SURFACE_ALPINE_HEIGHT_ABOVE_SEA;
      double span = band.alpineBlocks() - band.highlandBlocks();
      double zone;
      if (heightAboveSea < band.highlandBlocks()) {
         zone = highlandZone * heightAboveSea / band.highlandBlocks();
      } else if (heightAboveSea < band.alpineBlocks()) {
         zone = highlandZone + (alpineZone - highlandZone) * (heightAboveSea - band.highlandBlocks()) / span;
      } else {
         zone = alpineZone + (alpineZone - highlandZone) * (heightAboveSea - band.alpineBlocks()) / span;
      }
      return (int)Math.min(MAX_ZONE_HEIGHT, Math.floor(zone));
   }

   private static double interpolate(double[] latitudes, double[] values, double latitude) {
      if (latitude <= latitudes[0]) {
         return values[0];
      }

      for (int i = 1; i < latitudes.length; i++) {
         if (latitude <= latitudes[i]) {
            double fraction = (latitude - latitudes[i - 1]) / (latitudes[i] - latitudes[i - 1]);
            return values[i - 1] + fraction * (values[i] - values[i - 1]);
         }
      }
      return values[values.length - 1];
   }

   public record Band(double highlandBlocks, double alpineBlocks) {
      static Band of(double highlandBlocks, double alpineBlocks) {
         double highland = Double.isFinite(highlandBlocks) ? Math.max(MIN_ANCHOR_BLOCKS, highlandBlocks) : MIN_ANCHOR_BLOCKS;
         double alpine = Double.isFinite(alpineBlocks) ? Math.max(highland + MIN_ANCHOR_BLOCKS, alpineBlocks) : highland + MIN_ANCHOR_BLOCKS;
         return new Band(highland, alpine);
      }
   }
}
