package com.yucareux.tellus.worldgen;

/** Signals that a terrain refinement read an elevation tile whose download failed recently, so it is retried later. */
final class ElevationUnavailableException extends RuntimeException {
   ElevationUnavailableException(String chunk) {
      super("Elevation tiles are still unavailable for chunk " + chunk);
   }
}
