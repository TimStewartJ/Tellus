package com.yucareux.tellus.world.data.osm;

import java.util.Locale;

/**
 * Kind of non-road transportation segment exposed by {@link TellusOsmRoadSource}.
 *
 * <p>Values map one-to-one onto explicit Overture {@code transportation} segment {@code subtype}
 * values. Nothing here is ever inferred from ordinary road segments: a segment only becomes a
 * {@link TransportFeature} when Overture itself tagged it {@code subtype=rail} or
 * {@code subtype=water}.
 */
public enum TransportKind {
   /**
    * Overture {@code subtype=rail}: railway segments such as heavy rail, subway, light rail, tram,
    * monorail, funicular and narrow gauge. The concrete flavour is carried by
    * {@link TransportFeature#transportClass()}.
    */
   RAIL("rail"),
   /**
    * Overture {@code subtype=water}: traversable water routes.
    *
    * <p>In the current Overture transportation schema this subtype models routable water crossings
    * of the transportation graph, and in practice the published segments are ferry / water-shuttle
    * routes (Overture documents a water segment as representing "a ferry route" in the same way a
    * road segment represents a street). The schema nevertheless describes the subtype as generic
    * traversable water routes rather than a ferry-only enumeration, so this API deliberately uses
    * the neutral name {@code WATER_ROUTE}. Consumers that want a user-facing label should either use
    * a neutral wording ("water route") or narrow on {@link TransportFeature#transportClass()} (for
    * example {@code ferry}) instead of assuming every water segment is a ferry.
    */
   WATER_ROUTE("water");

   private final String overtureSubtype;

   TransportKind(String overtureSubtype) {
      this.overtureSubtype = overtureSubtype;
   }

   /** Exact Overture {@code subtype} token this kind is parsed from. */
   public String overtureSubtype() {
      return this.overtureSubtype;
   }

   /**
    * Resolves an explicit Overture segment {@code subtype} value.
    *
    * @return the matching kind, or {@code null} for {@code null}, blank, {@code road} or any other
    *     subtype. Never guesses from road classes.
    */
   public static TransportKind fromSubtype(String subtype) {
      if (subtype == null || subtype.isBlank()) {
         return null;
      }

      String normalized = subtype.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      return switch (normalized) {
         case "rail" -> RAIL;
         case "water" -> WATER_ROUTE;
         default -> null;
      };
   }

   /** Resolves a kind from its serialized ordinal, or {@code null} when the ordinal is unknown. */
   public static TransportKind fromOrdinal(int ordinal) {
      TransportKind[] values = values();
      return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
   }
}
