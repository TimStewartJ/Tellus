package com.yucareux.tellus.api.detail;

/**
 * Immutable, thread-safe exclusion query prepared for one coarse-detail tile.
 *
 * <p>Implementations own their geometry and must answer deterministically without world access,
 * blocking IO, or mutation. Tellus queries only exclusion domains advertised by the contributor.</p>
 */
@FunctionalInterface
public interface ChunkDetailLodPlan {
   ChunkDetailLodPlan NONE = (domain, worldX, worldZ, radius) -> false;

   boolean suppresses(
      ChunkDetailDomain domain, int worldX, int worldZ, int radius
   );

   static ChunkDetailLodPlan none() {
      return NONE;
   }
}
