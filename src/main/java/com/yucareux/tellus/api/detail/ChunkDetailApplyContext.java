package com.yucareux.tellus.api.detail;

import java.util.Objects;

/**
 * Resolved server-side application context for one contributor plan.
 */
public final class ChunkDetailApplyContext {
   private final ChunkDetailPlanContext planContext;
   private final ChunkDetailWriter writer;
   private final ChunkDetailArea grantedSurfaceColumns;

   public ChunkDetailApplyContext(
      ChunkDetailPlanContext planContext,
      ChunkDetailWriter writer,
      ChunkDetailArea grantedSurfaceColumns
   ) {
      this.planContext = Objects.requireNonNull(planContext, "planContext");
      this.writer = Objects.requireNonNull(writer, "writer");
      this.grantedSurfaceColumns = Objects.requireNonNull(grantedSurfaceColumns, "grantedSurfaceColumns");
   }

   public ChunkDetailPlanContext planContext() {
      return this.planContext;
   }

   public ChunkDetailWriter writer() {
      return this.writer;
   }

   public boolean canWriteSurface(int worldX, int worldZ) {
      return this.grantedSurfaceColumns.contains(worldX, worldZ);
   }
}
