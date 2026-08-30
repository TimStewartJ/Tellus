package com.yucareux.tellus.world.data.osm;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable line geometry for one explicit Overture non-road transportation segment.
 *
 * <p>Instances are only produced for segments whose Overture {@code subtype} is literally
 * {@code rail} or {@code water}; rail and ferry traces are never inferred from ordinary road
 * segments. Road generation does not read this type, so exposing it changes no worldgen behavior.
 *
 * <p>Geometry is stored as parallel longitude/latitude arrays in WGS84 degrees, in segment order,
 * clipped to the tile the segment was decoded from. A single real-world line that crosses several
 * source tiles is therefore published as several features that share {@link #featureId()}.
 */
public final class TransportFeature {
   private final long featureId;
   private final TransportKind kind;
   private final String transportClass;
   private final String subclass;
   private final double[] longitudes;
   private final double[] latitudes;
   private final double minLon;
   private final double maxLon;
   private final double minLat;
   private final double maxLat;

   public TransportFeature(long featureId, TransportKind kind, String transportClass, String subclass, double[] longitudes, double[] latitudes) {
      this.featureId = featureId;
      this.kind = Objects.requireNonNull(kind, "kind");
      this.transportClass = normalizeToken(transportClass);
      this.subclass = normalizeToken(subclass);
      this.longitudes = Objects.requireNonNull(longitudes, "longitudes").clone();
      this.latitudes = Objects.requireNonNull(latitudes, "latitudes").clone();
      if (this.longitudes.length != this.latitudes.length || this.longitudes.length < 2) {
         throw new IllegalArgumentException("TransportFeature requires at least two matching lon/lat points");
      }

      double lowLon = Double.POSITIVE_INFINITY;
      double highLon = Double.NEGATIVE_INFINITY;
      double lowLat = Double.POSITIVE_INFINITY;
      double highLat = Double.NEGATIVE_INFINITY;

      for (int i = 0; i < this.longitudes.length; i++) {
         double lon = this.longitudes[i];
         double lat = this.latitudes[i];
         if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
            throw new IllegalArgumentException("TransportFeature requires finite lon/lat points");
         }

         lowLon = Math.min(lowLon, lon);
         highLon = Math.max(highLon, lon);
         lowLat = Math.min(lowLat, lat);
         highLat = Math.max(highLat, lat);
      }

      this.minLon = lowLon;
      this.maxLon = highLon;
      this.minLat = lowLat;
      this.maxLat = highLat;
   }

   /** Stable Overture/OSM identifier of the source segment; shared by tile-clipped pieces of one line. */
   public long featureId() {
      return this.featureId;
   }

   /** Explicit Overture subtype of this segment, never inferred from road tags. */
   public TransportKind kind() {
      return this.kind;
   }

   /**
    * Normalized Overture {@code class} value (lower case, {@code -} and spaces folded to {@code _}),
    * for example {@code rail}, {@code subway}, {@code tram} or {@code ferry}. Empty when the source
    * segment carries no class.
    */
   public String transportClass() {
      return this.transportClass;
   }

   /** Normalized Overture {@code subclass} value, or an empty string when absent. */
   public String subclass() {
      return this.subclass;
   }

   /** {@code true} when this segment is an explicit Overture rail segment. */
   public boolean isRail() {
      return this.kind == TransportKind.RAIL;
   }

   /** {@code true} when this segment is an explicit Overture traversable water route segment. */
   public boolean isWaterRoute() {
      return this.kind == TransportKind.WATER_ROUTE;
   }

   /** Compares against a normalized Overture class token. */
   public boolean matchesClass(String transportClass) {
      return this.transportClass.equals(normalizeToken(transportClass));
   }

   public int pointCount() {
      return this.longitudes.length;
   }

   public double lonAt(int index) {
      return this.longitudes[index];
   }

   public double latAt(int index) {
      return this.latitudes[index];
   }

   /** Defensive copy of the longitude ordinates. */
   public double[] longitudes() {
      return Arrays.copyOf(this.longitudes, this.longitudes.length);
   }

   /** Defensive copy of the latitude ordinates. */
   public double[] latitudes() {
      return Arrays.copyOf(this.latitudes, this.latitudes.length);
   }

   public double minLon() {
      return this.minLon;
   }

   public double maxLon() {
      return this.maxLon;
   }

   public double minLat() {
      return this.minLat;
   }

   public double maxLat() {
      return this.maxLat;
   }

   public boolean intersects(double south, double west, double north, double east) {
      return this.maxLon >= west && this.minLon <= east && this.maxLat >= south && this.minLat <= north;
   }

   /**
    * Identity used to drop exact duplicates when the same tile geometry is visited twice, for
    * example by an antimeridian-wrapping query. Distinct tile-clipped pieces of one line keep
    * different identities so deduplication never drops real geometry.
    */
   public String dedupeKey() {
      StringBuilder key = new StringBuilder(48);
      key.append(this.featureId).append('|').append(this.kind.ordinal()).append('|').append(this.longitudes.length);
      for (int i = 0; i < this.longitudes.length; i++) {
         key.append('|').append(Double.doubleToLongBits(this.longitudes[i]));
         key.append(':').append(Double.doubleToLongBits(this.latitudes[i]));
      }

      return key.toString();
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      }
      if (!(other instanceof TransportFeature feature)) {
         return false;
      }

      return this.featureId == feature.featureId
         && this.kind == feature.kind
         && this.transportClass.equals(feature.transportClass)
         && this.subclass.equals(feature.subclass)
         && Arrays.equals(this.longitudes, feature.longitudes)
         && Arrays.equals(this.latitudes, feature.latitudes);
   }

   @Override
   public int hashCode() {
      int result = Long.hashCode(this.featureId);
      result = 31 * result + this.kind.hashCode();
      result = 31 * result + this.transportClass.hashCode();
      result = 31 * result + this.subclass.hashCode();
      result = 31 * result + Arrays.hashCode(this.longitudes);
      return 31 * result + Arrays.hashCode(this.latitudes);
   }

   @Override
   public String toString() {
      return "TransportFeature[id="
         + this.featureId
         + ", kind="
         + this.kind
         + ", class="
         + this.transportClass
         + ", points="
         + this.longitudes.length
         + "]";
   }

   static String normalizeToken(String value) {
      return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
   }
}
