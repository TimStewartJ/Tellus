package com.yucareux.tellus.api.detail;

/**
 * Signals that a coarse-detail plan is incomplete and should be retried after a short delay.
 */
public final class ChunkDetailLodPendingException extends RuntimeException {
   private final int retryAfterTicks;

   public ChunkDetailLodPendingException(int retryAfterTicks, String message) {
      super(message, null, false, false);
      this.retryAfterTicks = Math.max(1, Math.min(1_200, retryAfterTicks));
   }

   public int retryAfterTicks() {
      return this.retryAfterTicks;
   }
}
