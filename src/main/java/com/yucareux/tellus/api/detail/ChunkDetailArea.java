package com.yucareux.tellus.api.detail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable sparse set of absolute block columns.
 */
public final class ChunkDetailArea {
   private static final int MAX_COLUMNS = 4_096;
   private static final ChunkDetailArea EMPTY = new ChunkDetailArea(new long[0], false);
   private final long[] columns;

   private ChunkDetailArea(long[] columns, boolean normalize) {
      long[] copy = columns.clone();
      if (normalize) {
         Arrays.sort(copy);
         int unique = 0;
         for (long column : copy) {
            if (unique == 0 || copy[unique - 1] != column) {
               copy[unique++] = column;
            }
         }
         copy = Arrays.copyOf(copy, unique);
      }
      if (copy.length > MAX_COLUMNS) {
         throw new IllegalArgumentException("Chunk-detail area exceeds " + MAX_COLUMNS + " columns");
      }
      this.columns = copy;
   }

   public static ChunkDetailArea empty() {
      return EMPTY;
   }

   public static ChunkDetailArea of(int worldX, int worldZ) {
      return new ChunkDetailArea(new long[]{pack(worldX, worldZ)}, false);
   }

   public static ChunkDetailArea ofPackedColumns(long... columns) {
      return columns.length == 0 ? EMPTY : new ChunkDetailArea(columns, true);
   }

   public static Builder builder() {
      return new Builder();
   }

   public boolean isEmpty() {
      return this.columns.length == 0;
   }

   public int size() {
      return this.columns.length;
   }

   public boolean contains(int worldX, int worldZ) {
      return Arrays.binarySearch(this.columns, pack(worldX, worldZ)) >= 0;
   }

   public boolean intersectsSquare(int centerX, int centerZ, int radius) {
      if (this.columns.length == 0) {
         return false;
      }
      int checkedRadius = Math.max(0, radius);
      for (int z = centerZ - checkedRadius; z <= centerZ + checkedRadius; z++) {
         for (int x = centerX - checkedRadius; x <= centerX + checkedRadius; x++) {
            if (this.contains(x, z)) {
               return true;
            }
         }
      }
      return false;
   }

   public long[] packedColumns() {
      return this.columns.clone();
   }

   public static long pack(int worldX, int worldZ) {
      return (long)worldX << 32 | worldZ & 0xFFFF_FFFFL;
   }

   public static int unpackX(long column) {
      return (int)(column >> 32);
   }

   public static int unpackZ(long column) {
      return (int)column;
   }

   public static final class Builder {
      private final List<Long> columns = new ArrayList<>();

      private Builder() {
      }

      public Builder add(int worldX, int worldZ) {
         this.columns.add(pack(worldX, worldZ));
         return this;
      }

      public Builder addAll(ChunkDetailArea area) {
         for (long column : area.columns) {
            this.columns.add(column);
         }
         return this;
      }

      public ChunkDetailArea build() {
         if (this.columns.isEmpty()) {
            return EMPTY;
         }
         long[] packed = new long[this.columns.size()];
         for (int index = 0; index < packed.length; index++) {
            packed[index] = this.columns.get(index);
         }
         return new ChunkDetailArea(packed, true);
      }
   }
}
