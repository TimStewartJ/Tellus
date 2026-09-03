package com.yucareux.tellus.api.detail;

import java.util.Objects;

/**
 * Non-blocking outcome of one coarse-detail exclusion planning attempt.
 */
public sealed interface ChunkDetailLodPlanResult {
   record Ready(ChunkDetailLodPlan plan) implements ChunkDetailLodPlanResult {
      public Ready {
         Objects.requireNonNull(plan, "plan");
      }
   }

   record Pending(int retryAfterTicks, String reason) implements ChunkDetailLodPlanResult {
      public Pending {
         retryAfterTicks = Math.max(1, Math.min(1_200, retryAfterTicks));
         reason = requireReason(reason);
      }
   }

   record Skipped(String reason) implements ChunkDetailLodPlanResult {
      public Skipped {
         reason = requireReason(reason);
      }
   }

   record Failed(boolean retryable, String reason) implements ChunkDetailLodPlanResult {
      public Failed {
         reason = requireReason(reason);
      }
   }

   static Ready ready(ChunkDetailLodPlan plan) {
      return new Ready(plan);
   }

   static Pending pending(int retryAfterTicks, String reason) {
      return new Pending(retryAfterTicks, reason);
   }

   static Skipped skipped(String reason) {
      return new Skipped(reason);
   }

   static Failed failed(boolean retryable, String reason) {
      return new Failed(retryable, reason);
   }

   private static String requireReason(String value) {
      Objects.requireNonNull(value, "reason");
      String normalized = value.strip();
      if (normalized.isEmpty() || normalized.length() > 256) {
         throw new IllegalArgumentException("LOD plan result reason must contain 1..256 characters");
      }
      return normalized;
   }
}
