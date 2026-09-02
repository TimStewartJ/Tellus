package com.yucareux.tellus.api.detail;

import java.util.List;

/**
 * Immutable contributor-owned plan prepared away from the server thread.
 */
public interface ChunkDetailPlan {
   List<ChunkDetailClaim> claims();
}
