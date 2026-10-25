package com.yucareux.tellus.lod.tile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The terrain of one square LOD tile as vertical spans per column.
 *
 * <p>Each column lists its solid and fluid spans from the bottom up; gaps between spans are air.
 * Heights are block offsets above the world's minimum Y, so the extended Tellus range (-640..10447)
 * fits a short. Spans live in flat arrays: a tile costs the same handful of allocations however
 * rugged it is, and it serializes as-is.</p>
 *
 * <p>Light packs sky light in the high nibble and block light in the low nibble.</p>
 */
public final class LodTileColumns {
   public static final int MAX_HEIGHT = Short.MAX_VALUE;
   private static final int FORMAT_VERSION = 1;

   private final int width;
   private final int[] columnStart;
   private final short[] bottoms;
   private final short[] tops;
   private final int[] materials;
   private final byte[] lights;

   private LodTileColumns(int width, int[] columnStart, short[] bottoms, short[] tops, int[] materials, byte[] lights) {
      this.width = width;
      this.columnStart = columnStart;
      this.bottoms = bottoms;
      this.tops = tops;
      this.materials = materials;
      this.lights = lights;
   }

   public static Builder builder(int width) {
      return new Builder(width);
   }

   public int width() {
      return this.width;
   }

   public int columnCount() {
      return this.width * this.width;
   }

   public int spanCount() {
      return this.columnStart[this.columnStart.length - 1];
   }

   public int columnIndex(int x, int z) {
      return z * this.width + x;
   }

   public int spanStart(int column) {
      return this.columnStart[column];
   }

   public int spanEnd(int column) {
      return this.columnStart[column + 1];
   }

   public int bottom(int span) {
      return this.bottoms[span];
   }

   public int top(int span) {
      return this.tops[span];
   }

   public int material(int span) {
      return this.materials[span];
   }

   public int light(int span) {
      return this.lights[span] & 0xFF;
   }

   /** Bytes held by the arrays, ignoring object headers. */
   public long sizeBytes() {
      int spans = this.spanCount();
      return 4L * this.columnStart.length + spans * (2L + 2L + 4L + 1L);
   }

   public void writeTo(DataOutput output) throws IOException {
      int spans = this.spanCount();
      output.writeByte(FORMAT_VERSION);
      output.writeShort(this.width);
      output.writeInt(spans);
      for (int column = 0; column < this.columnCount(); column++) {
         output.writeShort(this.spanEnd(column) - this.spanStart(column));
      }
      for (int span = 0; span < spans; span++) {
         output.writeShort(this.bottoms[span]);
         output.writeShort(this.tops[span]);
         output.writeInt(this.materials[span]);
         output.writeByte(this.lights[span]);
      }
   }

   public static LodTileColumns readFrom(DataInput input) throws IOException {
      int version = input.readUnsignedByte();
      if (version != FORMAT_VERSION) {
         throw new IOException("Unsupported LOD tile format " + version);
      }
      int width = input.readUnsignedShort();
      int spans = input.readInt();
      if (width <= 0 || spans < 0) {
         throw new IOException("Corrupt LOD tile header: width=" + width + " spans=" + spans);
      }
      int[] columnStart = new int[width * width + 1];
      for (int column = 0; column < width * width; column++) {
         columnStart[column + 1] = columnStart[column] + input.readUnsignedShort();
      }
      if (columnStart[width * width] != spans) {
         throw new IOException("Corrupt LOD tile: column lengths sum to " + columnStart[width * width] + ", expected " + spans);
      }
      short[] bottoms = new short[spans];
      short[] tops = new short[spans];
      int[] materials = new int[spans];
      byte[] lights = new byte[spans];
      for (int span = 0; span < spans; span++) {
         bottoms[span] = input.readShort();
         tops[span] = input.readShort();
         materials[span] = input.readInt();
         lights[span] = input.readByte();
      }
      return new LodTileColumns(width, columnStart, bottoms, tops, materials, lights);
   }

   /** Fills columns in row order (x fastest). Columns never started stay empty. */
   public static final class Builder {
      private final int width;
      private final int[] columnStart;
      private short[] bottoms = new short[256];
      private short[] tops = new short[256];
      private int[] materials = new int[256];
      private byte[] lights = new byte[256];
      private int spanCount;
      private int column = -1;
      private int columnFirstSpan;

      private Builder(int width) {
         if (width <= 0 || width > 1024) {
            throw new IllegalArgumentException("Tile width must be 1..1024, got " + width);
         }
         this.width = width;
         this.columnStart = new int[width * width + 1];
      }

      public Builder column(int x, int z) {
         if (x < 0 || z < 0 || x >= this.width || z >= this.width) {
            throw new IndexOutOfBoundsException("Column " + x + "," + z + " is outside a " + this.width + " tile");
         }
         int index = z * this.width + x;
         if (index <= this.column) {
            throw new IllegalStateException("Columns must be added in row order; " + index + " follows " + this.column);
         }
         for (int skipped = this.column + 1; skipped <= index; skipped++) {
            this.columnStart[skipped] = this.spanCount;
         }
         this.column = index;
         this.columnFirstSpan = this.spanCount;
         return this;
      }

      /** Adds the next span of the current column; spans must ascend and may touch but not overlap. */
      public Builder span(int bottom, int top, int material, int light) {
         if (this.column < 0) {
            throw new IllegalStateException("Start a column before adding spans");
         }
         if (bottom < 0 || top > MAX_HEIGHT || bottom >= top) {
            throw new IllegalArgumentException("Invalid span " + bottom + ".." + top);
         }
         if (this.spanCount > this.columnFirstSpan) {
            int previous = this.spanCount - 1;
            int previousTop = this.tops[previous];
            if (bottom < previousTop) {
               throw new IllegalArgumentException("Span " + bottom + ".." + top + " overlaps the span ending at " + previousTop);
            }
            if (bottom == previousTop && this.materials[previous] == material && (this.lights[previous] & 0xFF) == light) {
               this.tops[previous] = (short)top;
               return this;
            }
         }
         if (this.spanCount == this.bottoms.length) {
            int capacity = this.bottoms.length * 2;
            this.bottoms = Arrays.copyOf(this.bottoms, capacity);
            this.tops = Arrays.copyOf(this.tops, capacity);
            this.materials = Arrays.copyOf(this.materials, capacity);
            this.lights = Arrays.copyOf(this.lights, capacity);
         }
         this.bottoms[this.spanCount] = (short)bottom;
         this.tops[this.spanCount] = (short)top;
         this.materials[this.spanCount] = material;
         this.lights[this.spanCount] = (byte)light;
         this.spanCount++;
         return this;
      }

      public LodTileColumns build() {
         for (int remaining = this.column + 1; remaining <= this.width * this.width; remaining++) {
            this.columnStart[remaining] = this.spanCount;
         }
         return new LodTileColumns(
            this.width,
            this.columnStart.clone(),
            Arrays.copyOf(this.bottoms, this.spanCount),
            Arrays.copyOf(this.tops, this.spanCount),
            Arrays.copyOf(this.materials, this.spanCount),
            Arrays.copyOf(this.lights, this.spanCount)
         );
      }
   }
}
