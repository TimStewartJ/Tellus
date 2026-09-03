package com.yucareux.tellus.api.detail;

import java.util.Set;

/**
 * Optional deterministic participant in Tellus chunk-detail generation.
 *
 * <p>{@link #plan} runs on a terrain-detail worker and must be non-blocking. It receives no mutable
 * world object. {@link #apply} receives an owner-chunk-bounded writer and may only perform deterministic,
 * idempotent writes granted by the resolved claims.</p>
 */
public interface ChunkDetailContributor {
   /** Whether this contributor currently needs generation orchestration. */
   default boolean active() {
      return true;
   }

   /** Revision of the contributor's planning semantics or immutable configuration snapshot. */
   long revision();

   /** Largest distance outside the owner chunk at which this contributor may declare a claim. */
   int haloBlocks();

   /** Domains this contributor may claim. */
   Set<ChunkDetailDomain> domains();

   /**
    * Cheap readiness probe used before expensive terrain refinement.
    *
    * <p>The returned plan is ignored. The default delegates to {@link #plan}; contributors with costly
    * geometry work should override this and only verify that their nonblocking inputs are ready.</p>
    */
   default ChunkDetailPlanResult preflight(ChunkDetailPlanContext context) {
      return this.plan(context);
   }

   ChunkDetailPlanResult plan(ChunkDetailPlanContext context);

   /**
    * Optional coarse-detail exclusion plan.
    *
    * <p>The result must be immutable, thread-safe, and non-blocking. A pending result prevents the
    * incomplete coarse tile from being cached; the distant generator may retry it later.</p>
    */
   default ChunkDetailLodPlanResult planLodExclusions(
      ChunkDetailLodPlanContext context
   ) {
      return ChunkDetailLodPlanResult.skipped("coarse-detail exclusions not provided");
   }

   default void apply(ChunkDetailApplyContext context, ChunkDetailPlan plan) {
   }
}
