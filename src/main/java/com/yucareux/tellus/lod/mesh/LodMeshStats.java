package com.yucareux.tellus.lod.mesh;

import java.util.Arrays;

/** Counts merged quads by face and pass, for budgets and benchmarks. */
public final class LodMeshStats implements LodQuadSink {
   private final long[] opaqueQuads = new long[LodFace.values().length];
   private final long[] translucentQuads = new long[LodFace.values().length];
   private long skirtQuads;

   @Override
   public void quad(LodFace face, int plane, int u, int v, int width, int height, int material, int light, boolean translucent) {
      (translucent ? this.translucentQuads : this.opaqueQuads)[face.ordinal()]++;
   }

   @Override
   public void skirt(LodFace face, int plane, int u, int v, int width, int height, int material, int light) {
      this.skirtQuads++;
   }

   public long quads(LodFace face, boolean translucent) {
      return (translucent ? this.translucentQuads : this.opaqueQuads)[face.ordinal()];
   }

   public long opaqueQuads() {
      return sum(this.opaqueQuads);
   }

   public long translucentQuads() {
      return sum(this.translucentQuads);
   }

   public long skirtQuads() {
      return this.skirtQuads;
   }

   public long totalQuads() {
      return this.opaqueQuads() + this.translucentQuads() + this.skirtQuads;
   }

   public void add(LodMeshStats other) {
      for (int face = 0; face < this.opaqueQuads.length; face++) {
         this.opaqueQuads[face] += other.opaqueQuads[face];
         this.translucentQuads[face] += other.translucentQuads[face];
      }
      this.skirtQuads += other.skirtQuads;
   }

   public void reset() {
      Arrays.fill(this.opaqueQuads, 0L);
      Arrays.fill(this.translucentQuads, 0L);
      this.skirtQuads = 0L;
   }

   private static long sum(long[] values) {
      long total = 0L;
      for (long value : values) {
         total += value;
      }
      return total;
   }
}
