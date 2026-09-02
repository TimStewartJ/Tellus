package com.yucareux.tellus.api.detail;

/**
 * A horizontal world-generation resource that a chunk-detail plan may claim.
 */
public enum ChunkDetailDomain {
   /** Permission to change the owner chunk's finished surface before vegetation is placed. */
   SURFACE_WRITE,
   /** Prevents mature tree trunks and roots from intersecting the claimed columns. */
   MATURE_TREE_EXCLUSION,
   /** Prevents ecological understory placements from intersecting the claimed columns. */
   UNDERSTORY_EXCLUSION
}
