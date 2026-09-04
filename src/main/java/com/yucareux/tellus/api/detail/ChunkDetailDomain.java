package com.yucareux.tellus.api.detail;

/**
 * A horizontal world-generation resource that a chunk-detail plan may claim.
 */
public enum ChunkDetailDomain {
   /** Permission to change the owner chunk's finished surface before vegetation is placed. */
   SURFACE_WRITE,
   /** Legacy keepout expanded by the mature tree's full root radius. */
   MATURE_TREE_EXCLUSION,
   /** Explicit final keepout area for mature tree anchors; Tellus does not add implicit padding. */
   TREE_ANCHOR_EXCLUSION,
   /** Exact columns that procedural root rays must not enter. */
   TREE_ROOT_EXCLUSION,
   /** Prevents ecological understory placements from intersecting the claimed columns. */
   UNDERSTORY_EXCLUSION
}
