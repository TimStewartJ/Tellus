package com.yucareux.tellus.api.detail;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One deterministic claim made by a chunk-detail plan.
 */
public final class ChunkDetailClaim {
   private final String stableKey;
   private final int priority;
   private final Set<ChunkDetailDomain> domains;
   private final ChunkDetailArea area;

   public ChunkDetailClaim(
      String stableKey,
      int priority,
      Set<ChunkDetailDomain> domains,
      ChunkDetailArea area
   ) {
      this.stableKey = requireStableKey(stableKey);
      this.priority = priority;
      Objects.requireNonNull(domains, "domains");
      if (domains.isEmpty()) {
         throw new IllegalArgumentException("Chunk-detail claim must declare at least one domain");
      }
      this.domains = Set.copyOf(EnumSet.copyOf(domains));
      this.area = Objects.requireNonNull(area, "area");
      if (area.isEmpty()) {
         throw new IllegalArgumentException("Chunk-detail claim area must not be empty");
      }
   }

   public String stableKey() {
      return this.stableKey;
   }

   public int priority() {
      return this.priority;
   }

   public Set<ChunkDetailDomain> domains() {
      return this.domains;
   }

   public ChunkDetailArea area() {
      return this.area;
   }

   private static String requireStableKey(String value) {
      Objects.requireNonNull(value, "stableKey");
      String normalized = value.strip();
      if (normalized.isEmpty() || normalized.length() > 128) {
         throw new IllegalArgumentException("Chunk-detail claim key must contain 1..128 characters");
      }
      return normalized;
   }
}
