package com.yucareux.tellus.lod.mesh;

import com.yucareux.tellus.lod.tile.LodTileColumns;
import java.util.Arrays;
import java.util.Objects;

/**
 * Turns one tile's columns into merged quads.
 *
 * <p>Top and bottom faces merge into rectangles of equal height, material and light; side faces merge
 * into runs along the face plane with an equal vertical extent. A face is hidden by an opaque neighbour,
 * or by the same translucent material (water next to water), so seabeds keep their faces under water.
 * Below a neighbour column's lowest span counts as solid ground.</p>
 *
 * <p>An edge with a neighbour tile of the same width gets exact side faces. An edge without one gets a
 * skirt: a wall of {@code skirtDepth} blocks hanging below each border column, which covers the crack
 * against a coarser, finer or not yet loaded neighbour.</p>
 *
 * <p>Not thread-safe: scratch buffers are reused across tiles, so use one mesher per worker thread.</p>
 */
public final class LodGreedyMesher {
   public static final int MAX_WIDTH = 64;
   private static final int CELL_BITS = 12;
   private static final long CELL_MASK = (1L << CELL_BITS) - 1;
   private static final int ALONG_BITS = 7;
   private static final long ALONG_MASK = (1L << ALONG_BITS) - 1;
   private static final int MAX_LOCAL_MATERIALS = 1 << 16;

   private final LodMaterialTable materials;
   private final long[] rowMask = new long[MAX_WIDTH];
   private long[] faces = new long[4096];
   private int faceCount;
   private int[] slotKeys = new int[1 << 10];
   private int[] slotValues = new int[1 << 10];
   private int[] slotStamps = new int[1 << 10];
   private int stamp = 1;
   private int[] localMaterials = new int[256];
   private int localMaterialCount;
   private long rawTopFaces;
   private long rawBottomFaces;
   private long rawSideFaces;

   public LodGreedyMesher(LodMaterialTable materials) {
      this.materials = Objects.requireNonNull(materials, "materials");
   }

   public void mesh(LodTileColumns tile, int skirtDepth, LodQuadSink sink) {
      this.mesh(tile, null, null, null, null, skirtDepth, sink);
   }

   /** Neighbours are the adjacent tiles at the same level, or null where none is loaded. */
   public void mesh(
      LodTileColumns tile,
      LodTileColumns west,
      LodTileColumns east,
      LodTileColumns north,
      LodTileColumns south,
      int skirtDepth,
      LodQuadSink sink
   ) {
      Objects.requireNonNull(sink, "sink");
      int width = tile.width();
      if (width > MAX_WIDTH) {
         throw new IllegalArgumentException("Tiles wider than " + MAX_WIDTH + " cells are not supported: " + width);
      }
      requireSameWidth(west, width);
      requireSameWidth(east, width);
      requireSameWidth(north, width);
      requireSameWidth(south, width);
      this.resetMaterials();
      this.rawTopFaces = 0L;
      this.rawBottomFaces = 0L;
      this.rawSideFaces = 0L;
      this.meshHorizontal(tile, sink);
      this.meshSides(tile, LodFace.WEST, west, skirtDepth, sink);
      this.meshSides(tile, LodFace.EAST, east, skirtDepth, sink);
      this.meshSides(tile, LodFace.NORTH, north, skirtDepth, sink);
      this.meshSides(tile, LodFace.SOUTH, south, skirtDepth, sink);
   }

   /** Visible top faces of the last tile before merging, one per column span. */
   public long rawTopFaces() {
      return this.rawTopFaces;
   }

   public long rawBottomFaces() {
      return this.rawBottomFaces;
   }

   /** Visible side faces of the last tile before merging, one per exposed interval of a column edge. */
   public long rawSideFaces() {
      return this.rawSideFaces;
   }

   private static void requireSameWidth(LodTileColumns neighbour, int width) {
      if (neighbour != null && neighbour.width() != width) {
         throw new IllegalArgumentException("Neighbour tiles must have the same width: " + neighbour.width() + " != " + width);
      }
   }

   private void meshHorizontal(LodTileColumns tile, LodQuadSink sink) {
      int width = tile.width();
      this.faceCount = 0;
      for (int column = 0; column < tile.columnCount(); column++) {
         int start = tile.spanStart(column);
         int end = tile.spanEnd(column);
         for (int span = start; span < end; span++) {
            int top = tile.top(span);
            int above = span + 1;
            if (above >= end || tile.bottom(above) != top || !this.hides(tile, above, tile, span)) {
               this.addHorizontal(LodFace.UP, top, tile, span, column);
               this.rawTopFaces++;
            }
            // The lowest span's underside faces the unknown ground below the tile; never draw it.
            if (span > start) {
               int below = span - 1;
               int bottom = tile.bottom(span);
               if (tile.top(below) != bottom || !this.hides(tile, below, tile, span)) {
                  this.addHorizontal(LodFace.DOWN, bottom, tile, span, column);
                  this.rawBottomFaces++;
               }
            }
         }
      }

      Arrays.sort(this.faces, 0, this.faceCount);
      int groupStart = 0;
      while (groupStart < this.faceCount) {
         long key = this.faces[groupStart] >>> CELL_BITS;
         int groupEnd = groupStart;
         int firstRow = width;
         int lastRow = -1;
         while (groupEnd < this.faceCount && this.faces[groupEnd] >>> CELL_BITS == key) {
            int cell = (int)(this.faces[groupEnd] & CELL_MASK);
            int row = cell / width;
            this.rowMask[row] |= 1L << (cell - row * width);
            firstRow = Math.min(firstRow, row);
            lastRow = Math.max(lastRow, row);
            groupEnd++;
         }
         this.emitRectangles(key, firstRow, lastRow, sink);
         groupStart = groupEnd;
      }
   }

   private void addHorizontal(LodFace face, int y, LodTileColumns tile, int span, int column) {
      long key = (face == LodFace.UP ? 1L : 0L) << 40
         | (long)y << 24
         | (long)tile.light(span) << 16
         | this.localMaterial(tile.material(span));
      this.push(key << CELL_BITS | column);
   }

   /** Binary greedy meshing of one key's cells: grow each row run downwards while the rows below contain it. */
   private void emitRectangles(long key, int firstRow, int lastRow, LodQuadSink sink) {
      LodFace face = (key >>> 40 & 1L) == 1L ? LodFace.UP : LodFace.DOWN;
      int y = (int)(key >>> 24 & 0xFFFFL);
      int light = (int)(key >>> 16 & 0xFFL);
      int material = this.localMaterials[(int)(key & 0xFFFFL)];
      boolean translucent = this.materials.isTranslucent(material);
      for (int row = firstRow; row <= lastRow; row++) {
         long bits = this.rowMask[row];
         while (bits != 0L) {
            int start = Long.numberOfTrailingZeros(bits);
            int length = Long.numberOfTrailingZeros(~(bits >>> start));
            long run = (length == 64 ? -1L : (1L << length) - 1L) << start;
            int depth = 1;
            while (row + depth <= lastRow && (this.rowMask[row + depth] & run) == run) {
               this.rowMask[row + depth] &= ~run;
               depth++;
            }
            bits &= ~run;
            sink.quad(face, y, start, row, length, depth, material, light, translucent);
         }
         this.rowMask[row] = 0L;
      }
   }

   private void meshSides(LodTileColumns tile, LodFace face, LodTileColumns neighbourTile, int skirtDepth, LodQuadSink sink) {
      int width = tile.width();
      boolean planeAlongZ = face == LodFace.NORTH || face == LodFace.SOUTH;
      boolean positive = face == LodFace.EAST || face == LodFace.SOUTH;
      int step = positive ? 1 : -1;
      for (int line = 0; line < width; line++) {
         int plane = positive ? line + 1 : line;
         boolean edge = positive ? line == width - 1 : line == 0;
         this.faceCount = 0;
         if (edge && neighbourTile == null) {
            if (skirtDepth > 0) {
               for (int along = 0; along < width; along++) {
                  int column = planeAlongZ ? tile.columnIndex(along, line) : tile.columnIndex(line, along);
                  this.addSkirt(tile, column, along, skirtDepth);
               }
               this.emitRuns(face, plane, true, sink);
            }
            continue;
         }

         for (int along = 0; along < width; along++) {
            int x = planeAlongZ ? along : line;
            int z = planeAlongZ ? line : along;
            int column = tile.columnIndex(x, z);
            LodTileColumns other;
            int otherColumn;
            if (edge) {
               other = neighbourTile;
               otherColumn = planeAlongZ
                  ? other.columnIndex(x, positive ? 0 : width - 1)
                  : other.columnIndex(positive ? 0 : width - 1, z);
            } else {
               other = tile;
               otherColumn = planeAlongZ ? tile.columnIndex(x, z + step) : tile.columnIndex(x + step, z);
            }
            this.addExposedIntervals(tile, column, other, otherColumn, along);
         }
         this.emitRuns(face, plane, false, sink);
      }
   }

   private void addExposedIntervals(LodTileColumns tile, int column, LodTileColumns other, int otherColumn, int along) {
      int otherStart = other.spanStart(otherColumn);
      int otherEnd = other.spanEnd(otherColumn);
      if (otherStart == otherEnd) {
         // No data for the neighbour column: assume the terrain continues rather than drawing walls into a hole.
         return;
      }
      int floor = other.bottom(otherStart);
      for (int span = tile.spanStart(column); span < tile.spanEnd(column); span++) {
         int top = tile.top(span);
         int cursor = Math.max(tile.bottom(span), floor);
         for (int occupant = otherStart; occupant < otherEnd && cursor < top; occupant++) {
            int occupantBottom = other.bottom(occupant);
            if (occupantBottom >= top) {
               break;
            }
            int occupantTop = other.top(occupant);
            if (occupantTop <= cursor || !this.hides(other, occupant, tile, span)) {
               continue;
            }
            if (occupantBottom > cursor) {
               this.addSide(cursor, occupantBottom, tile, span, along);
            }
            cursor = occupantTop;
         }
         if (cursor < top) {
            this.addSide(cursor, top, tile, span, along);
         }
      }
   }

   private void addSkirt(LodTileColumns tile, int column, int along, int skirtDepth) {
      for (int span = tile.spanEnd(column) - 1; span >= tile.spanStart(column); span--) {
         if (!this.materials.isTranslucent(tile.material(span))) {
            int top = tile.top(span);
            this.addSideKey(Math.max(0, top - skirtDepth), top, tile, span, along);
            return;
         }
      }
   }

   private void addSide(int bottom, int top, LodTileColumns tile, int span, int along) {
      this.addSideKey(bottom, top, tile, span, along);
      this.rawSideFaces++;
   }

   private void addSideKey(int bottom, int top, LodTileColumns tile, int span, int along) {
      long key = (long)bottom << 47
         | (long)(top - bottom) << 31
         | (long)tile.light(span) << 23
         | (long)this.localMaterial(tile.material(span)) << ALONG_BITS
         | along;
      this.push(key);
   }

   /** Merges sorted side faces of one plane into runs of consecutive cells with an identical extent. */
   private void emitRuns(LodFace face, int plane, boolean skirt, LodQuadSink sink) {
      if (this.faceCount == 0) {
         return;
      }
      Arrays.sort(this.faces, 0, this.faceCount);
      long runKey = this.faces[0] >>> ALONG_BITS;
      int runStart = (int)(this.faces[0] & ALONG_MASK);
      int runEnd = runStart + 1;
      for (int index = 1; index <= this.faceCount; index++) {
         if (index < this.faceCount) {
            long key = this.faces[index] >>> ALONG_BITS;
            int along = (int)(this.faces[index] & ALONG_MASK);
            if (key == runKey && along == runEnd) {
               runEnd++;
               continue;
            }
            this.emitRun(face, plane, skirt, runKey, runStart, runEnd, sink);
            runKey = key;
            runStart = along;
            runEnd = along + 1;
         } else {
            this.emitRun(face, plane, skirt, runKey, runStart, runEnd, sink);
         }
      }
   }

   private void emitRun(LodFace face, int plane, boolean skirt, long key, int start, int end, LodQuadSink sink) {
      int material = this.localMaterials[(int)(key & 0xFFFFL)];
      int light = (int)(key >>> 16 & 0xFFL);
      int height = (int)(key >>> 24 & 0xFFFFL);
      int bottom = (int)(key >>> 40 & 0xFFFFL);
      if (skirt) {
         sink.skirt(face, plane, start, bottom, end - start, height, material, light);
      } else {
         sink.quad(face, plane, start, bottom, end - start, height, material, light, this.materials.isTranslucent(material));
      }
   }

   private boolean hides(LodTileColumns occupantTile, int occupant, LodTileColumns faceTile, int face) {
      int occupantMaterial = occupantTile.material(occupant);
      return !this.materials.isTranslucent(occupantMaterial) || occupantMaterial == faceTile.material(face);
   }

   private void push(long value) {
      if (this.faceCount == this.faces.length) {
         this.faces = Arrays.copyOf(this.faces, this.faces.length * 2);
      }
      this.faces[this.faceCount++] = value;
   }

   private void resetMaterials() {
      this.localMaterialCount = 0;
      this.stamp++;
      if (this.stamp == 0) {
         Arrays.fill(this.slotStamps, 0);
         this.stamp = 1;
      }
   }

   private int localMaterial(int material) {
      int mask = this.slotKeys.length - 1;
      int slot = mix(material) & mask;
      while (this.slotStamps[slot] == this.stamp) {
         if (this.slotKeys[slot] == material) {
            return this.slotValues[slot];
         }
         slot = slot + 1 & mask;
      }
      if (this.localMaterialCount >= MAX_LOCAL_MATERIALS) {
         throw new IllegalStateException("A tile may use at most " + MAX_LOCAL_MATERIALS + " materials");
      }
      if ((this.localMaterialCount + 1) * 2 > this.slotKeys.length) {
         this.growMaterialSlots();
         return this.localMaterial(material);
      }
      int local = this.localMaterialCount++;
      if (local == this.localMaterials.length) {
         this.localMaterials = Arrays.copyOf(this.localMaterials, local * 2);
      }
      this.localMaterials[local] = material;
      this.slotStamps[slot] = this.stamp;
      this.slotKeys[slot] = material;
      this.slotValues[slot] = local;
      return local;
   }

   private void growMaterialSlots() {
      int capacity = this.slotKeys.length * 2;
      this.slotKeys = new int[capacity];
      this.slotValues = new int[capacity];
      this.slotStamps = new int[capacity];
      this.stamp = 1;
      int mask = capacity - 1;
      for (int local = 0; local < this.localMaterialCount; local++) {
         int material = this.localMaterials[local];
         int slot = mix(material) & mask;
         while (this.slotStamps[slot] == this.stamp) {
            slot = slot + 1 & mask;
         }
         this.slotStamps[slot] = this.stamp;
         this.slotKeys[slot] = material;
         this.slotValues[slot] = local;
      }
   }

   private static int mix(int value) {
      int hash = value * 0x9E3779B9;
      return hash ^ hash >>> 16;
   }
}
