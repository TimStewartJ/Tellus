package com.yucareux.tellus.worldgen.landmark;

import java.util.Arrays;

/**
 * A landmark mesh turned into blocks for one world scale: per column, the solid runs of layers above the landmark's
 * base, each with one material (a palette block for meshes that name their blocks, else a height band). A block is
 * solid when any triangle of the mesh touches it, so beams thinner than a block stay connected; it takes the material
 * of the triangle passing closest to its centre.
 *
 * <p>Columns are kept per 16x16 tile and empty tiles cost nothing, so long thin landmarks (bridges) whose bounding
 * box is mostly air stay small.</p>
 */
public final class LandmarkVolume {
   /** Side of the tiles columns are indexed by. */
   private static final int TILE = 16;
   private static final int TILE_SHIFT = 4;
   /** Side of the tiles the mesh is voxelised in, a multiple of {@link #TILE}. */
   private static final int WORK_TILE = 64;
   /** Trees are kept this many blocks (Chebyshev) away from the landmark's columns. */
   static final int TREE_CLEARANCE = 6;

   private final int minX;
   private final int minZ;
   private final int sizeX;
   private final int sizeZ;
   private final int height;
   private final int tilesX;
   /** Per tile, where its columns start in {@link #columnStart}; -1 for a tile without blocks. */
   private final int[] tileColumnBase;
   /** Per column of every non-empty tile (row by row), its first span; each tile ends with one end marker. */
   private final int[] columnStart;
   private final short[] spanBottom;
   private final short[] spanTop;
   private final byte[] spanMaterial;
   /** Per layer: min x, min z, max x, max z of its blocks, relative to the volume's corner (min > max when empty). */
   private final int[] layerBounds;
   /** Columns within {@link #TREE_CLEARANCE} of a landmark column, over the volume grown by that clearance. */
   private final long[] nearColumns;

   private LandmarkVolume(
      int minX, int minZ, int sizeX, int sizeZ, int height, int[] tileColumnBase, int[] columnStart, short[] spanBottom, short[] spanTop,
      byte[] spanMaterial, int[] layerBounds, long[] nearColumns
   ) {
      this.minX = minX;
      this.minZ = minZ;
      this.sizeX = sizeX;
      this.sizeZ = sizeZ;
      this.height = height;
      this.tilesX = (sizeX + TILE - 1) >> TILE_SHIFT;
      this.tileColumnBase = tileColumnBase;
      this.columnStart = columnStart;
      this.spanBottom = spanBottom;
      this.spanTop = spanTop;
      this.spanMaterial = spanMaterial;
      this.layerBounds = layerBounds;
      this.nearColumns = nearColumns;
   }

   /**
    * Places the mesh's x/z centre at block position ({@code anchorX}, {@code anchorZ}), turned counter-clockwise by
    * the definition's rotation and scaled by {@code blocksPerMeter} on every axis.
    */
   static LandmarkVolume voxelize(LandmarkMesh mesh, LandmarkDefinition definition, double anchorX, double anchorZ, double blocksPerMeter) {
      double cos = Math.cos(Math.toRadians(definition.rotationDegrees()));
      double sin = Math.sin(Math.toRadians(definition.rotationDegrees()));
      double lowX = Double.MAX_VALUE, highX = -Double.MAX_VALUE, lowZ = Double.MAX_VALUE, highZ = -Double.MAX_VALUE;
      for (int corner = 0; corner < 4; corner++) {
         double x = (corner & 1) == 0 ? mesh.min(0) : mesh.max(0);
         double z = (corner & 2) == 0 ? mesh.min(2) : mesh.max(2);
         double worldX = anchorX + (x * cos + z * sin) * blocksPerMeter;
         double worldZ = anchorZ + (z * cos - x * sin) * blocksPerMeter;
         lowX = Math.min(lowX, worldX);
         highX = Math.max(highX, worldX);
         lowZ = Math.min(lowZ, worldZ);
         highZ = Math.max(highZ, worldZ);
      }
      int minX = (int)Math.floor(lowX) - 1;
      int minZ = (int)Math.floor(lowZ) - 1;
      int sizeX = (int)Math.ceil(highX) + 2 - minX;
      int sizeZ = (int)Math.ceil(highZ) + 2 - minZ;
      double layers = mesh.height() * blocksPerMeter;
      int height = Math.max(1, (int)Math.ceil(layers) + 1);
      if (height > Short.MAX_VALUE || (long)sizeX * sizeZ > Integer.MAX_VALUE / 2) {
         throw new IllegalArgumentException("Landmark " + definition.id() + " is too large at this scale");
      }

      // Every triangle in volume space: u along x from minX, v in layers, w along z from minZ.
      int triangles = mesh.triangleCount();
      float[] corners = new float[triangles * 9];
      for (int t = 0; t < triangles; t++) {
         for (int corner = 0; corner < 3; corner++) {
            double x = mesh.coordinate(t, corner, 0), y = mesh.coordinate(t, corner, 1), z = mesh.coordinate(t, corner, 2);
            corners[t * 9 + corner * 3] = (float)(anchorX + (x * cos + z * sin) * blocksPerMeter - minX);
            corners[t * 9 + corner * 3 + 1] = (float)(y * blocksPerMeter);
            corners[t * 9 + corner * 3 + 2] = (float)(anchorZ + (z * cos - x * sin) * blocksPerMeter - minZ);
         }
      }
      // Triangles per work tile, as one list per tile (counted, then filled).
      int workX = (sizeX + WORK_TILE - 1) / WORK_TILE;
      int workZ = (sizeZ + WORK_TILE - 1) / WORK_TILE;
      int[] bucketStart = new int[workX * workZ + 1];
      for (int t = 0; t < triangles; t++) {
         if (!mesh.showsAt(t, blocksPerMeter)) {
            continue;
         }
         int[] range = workRange(corners, t, sizeX, sizeZ);
         for (int k = range[2]; k <= range[3]; k++) {
            for (int i = range[0]; i <= range[1]; i++) {
               bucketStart[k * workX + i + 1]++;
            }
         }
      }
      for (int b = 1; b < bucketStart.length; b++) {
         bucketStart[b] += bucketStart[b - 1];
      }
      int[] cursor = bucketStart.clone();
      int[] bucketList = new int[bucketStart[bucketStart.length - 1]];
      for (int t = 0; t < triangles; t++) {
         if (!mesh.showsAt(t, blocksPerMeter)) {
            continue;
         }
         int[] range = workRange(corners, t, sizeX, sizeZ);
         for (int k = range[2]; k <= range[3]; k++) {
            for (int i = range[0]; i <= range[1]; i++) {
               bucketList[cursor[k * workX + i]++] = t;
            }
         }
      }
      return build(mesh, definition, corners, bucketStart, bucketList, minX, minZ, sizeX, sizeZ, height, layers, workX, workZ);
   }

   private static int[] workRange(float[] corners, int t, int sizeX, int sizeZ) {
      return new int[]{
         clamp((int)Math.floor(min3(corners, t * 9)), sizeX) / WORK_TILE,
         clamp((int)Math.floor(max3(corners, t * 9)), sizeX) / WORK_TILE,
         clamp((int)Math.floor(min3(corners, t * 9 + 2)), sizeZ) / WORK_TILE,
         clamp((int)Math.floor(max3(corners, t * 9 + 2)), sizeZ) / WORK_TILE
      };
   }

   private static LandmarkVolume build(
      LandmarkMesh mesh, LandmarkDefinition definition, float[] corners, int[] bucketStart, int[] bucketList, int minX, int minZ, int sizeX,
      int sizeZ, int height, double layers, int workX, int workZ
   ) {
      boolean palette = mesh.hasBlocks();
      byte[] layerBand = new byte[height];
      for (int j = 0; j < height; j++) {
         layerBand[j] = (byte)definition.bandAt((j + 0.5) / Math.max(1.0, layers));
      }
      int tilesX = (sizeX + TILE - 1) >> TILE_SHIFT;
      int tilesZ = (sizeZ + TILE - 1) >> TILE_SHIFT;
      int[] tileColumnBase = new int[tilesX * tilesZ];
      Arrays.fill(tileColumnBase, -1);
      int[] columnStart = new int[1024];
      int columnEntries = 0;
      short[] bottoms = new short[1024];
      short[] tops = new short[1024];
      byte[] materials = new byte[1024];
      int spans = 0;
      int[] layerBounds = new int[height * 4];
      for (int j = 0; j < height; j++) {
         layerBounds[j * 4] = Integer.MAX_VALUE;
         layerBounds[j * 4 + 1] = Integer.MAX_VALUE;
         layerBounds[j * 4 + 2] = Integer.MIN_VALUE;
         layerBounds[j * 4 + 3] = Integer.MIN_VALUE;
      }
      boolean[] occupied = new boolean[sizeX * sizeZ];
      // One work tile: material + 1 per voxel (0 = air) and the distance of the triangle that set it.
      int cells = WORK_TILE * WORK_TILE * height;
      byte[] grid = new byte[cells];
      float[] distance = new float[cells];
      double[] triangle = new double[9];
      for (int wz = 0; wz < workZ; wz++) {
         for (int wx = 0; wx < workX; wx++) {
            int bucket = wz * workX + wx;
            if (bucketStart[bucket] == bucketStart[bucket + 1]) {
               continue;
            }
            int originX = wx * WORK_TILE;
            int originZ = wz * WORK_TILE;
            int spanX = Math.min(WORK_TILE, sizeX - originX);
            int spanZ = Math.min(WORK_TILE, sizeZ - originZ);
            Arrays.fill(grid, (byte)0);
            for (int b = bucketStart[bucket]; b < bucketStart[bucket + 1]; b++) {
               int t = bucketList[b];
               for (int i = 0; i < 9; i++) {
                  triangle[i] = corners[t * 9 + i];
               }
               int lowI = (int)Math.floor(min3(corners, t * 9));
               int highI = (int)Math.floor(max3(corners, t * 9));
               int lowK = (int)Math.floor(min3(corners, t * 9 + 2));
               int highK = (int)Math.floor(max3(corners, t * 9 + 2));
               int i0 = Math.max(originX, clamp(lowI, sizeX));
               int i1 = Math.min(originX + spanX - 1, clamp(highI, sizeX));
               int j0 = clamp((int)Math.floor(min3(corners, t * 9 + 1)), height);
               int j1 = clamp((int)Math.floor(max3(corners, t * 9 + 1)), height);
               int k0 = Math.max(originZ, clamp(lowK, sizeZ));
               int k1 = Math.min(originZ + spanZ - 1, clamp(highK, sizeZ));
               if (i0 > i1 || k0 > k1) {
                  continue;
               }
               boolean single = lowI == highI && j0 == j1 && lowK == highK;
               // Unit normal, for the distance from a voxel centre to the triangle's plane.
               double ex = triangle[3] - triangle[0], ey = triangle[4] - triangle[1], ez = triangle[5] - triangle[2];
               double fx = triangle[6] - triangle[0], fy = triangle[7] - triangle[1], fz = triangle[8] - triangle[2];
               double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
               double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
               if (length > 0.0) {
                  nx /= length;
                  ny /= length;
                  nz /= length;
               }
               byte material = (byte)((palette ? mesh.triangleBlock(t) : 0) + 1);
               for (int k = k0; k <= k1; k++) {
                  for (int i = i0; i <= i1; i++) {
                     int columnBase = ((k - originZ) * WORK_TILE + (i - originX)) * height;
                     for (int j = j0; j <= j1; j++) {
                        if (single || overlapsVoxel(triangle, i + 0.5, j + 0.5, k + 0.5)) {
                           float d = length > 0.0
                              ? (float)Math.abs((i + 0.5 - triangle[0]) * nx + (j + 0.5 - triangle[1]) * ny + (k + 0.5 - triangle[2]) * nz)
                              : 0.0F;
                           int cell = columnBase + j;
                           if (grid[cell] == 0 || d < distance[cell]) {
                              grid[cell] = material;
                              distance[cell] = d;
                           }
                        }
                     }
                  }
               }
            }
            // Spans, index tile by index tile so each tile's columns stay together.
            int lastTileZ = Math.min(tilesZ, (originZ + spanZ + TILE - 1) >> TILE_SHIFT);
            int lastTileX = Math.min(tilesX, (originX + spanX + TILE - 1) >> TILE_SHIFT);
            for (int tz = originZ >> TILE_SHIFT; tz < lastTileZ; tz++) {
               for (int tx = originX >> TILE_SHIFT; tx < lastTileX; tx++) {
                  int tileBase = columnEntries;
                  int tileSpans = spans;
                  if (columnEntries + TILE * TILE + 1 > columnStart.length) {
                     columnStart = Arrays.copyOf(columnStart, Math.max(columnStart.length * 2, columnEntries + TILE * TILE + 1));
                  }
                  for (int lz = 0; lz < TILE; lz++) {
                     for (int lx = 0; lx < TILE; lx++) {
                        columnStart[columnEntries++] = spans;
                        int i = (tx << TILE_SHIFT) + lx;
                        int k = (tz << TILE_SHIFT) + lz;
                        if (i >= sizeX || k >= sizeZ) {
                           continue;
                        }
                        int columnBase = ((k - originZ) * WORK_TILE + (i - originX)) * height;
                        int runStart = -1;
                        int runMaterial = -1;
                        for (int j = 0; j <= height; j++) {
                           int value = j < height ? grid[columnBase + j] & 0xFF : 0;
                           int cellMaterial = value == 0 ? -1 : palette ? value - 1 : layerBand[j];
                           if (runStart >= 0 && cellMaterial != runMaterial) {
                              if (spans == bottoms.length) {
                                 bottoms = Arrays.copyOf(bottoms, spans * 2);
                                 tops = Arrays.copyOf(tops, spans * 2);
                                 materials = Arrays.copyOf(materials, spans * 2);
                              }
                              bottoms[spans] = (short)runStart;
                              tops[spans] = (short)(j - 1);
                              materials[spans] = (byte)runMaterial;
                              spans++;
                              for (int layer = runStart; layer < j; layer++) {
                                 layerBounds[layer * 4] = Math.min(layerBounds[layer * 4], i);
                                 layerBounds[layer * 4 + 1] = Math.min(layerBounds[layer * 4 + 1], k);
                                 layerBounds[layer * 4 + 2] = Math.max(layerBounds[layer * 4 + 2], i);
                                 layerBounds[layer * 4 + 3] = Math.max(layerBounds[layer * 4 + 3], k);
                              }
                              occupied[k * sizeX + i] = true;
                              runStart = -1;
                           }
                           if (cellMaterial >= 0 && runStart < 0) {
                              runStart = j;
                              runMaterial = cellMaterial;
                           }
                        }
                     }
                  }
                  columnStart[columnEntries++] = spans;
                  if (spans == tileSpans) {
                     columnEntries = tileBase;
                  } else {
                     tileColumnBase[tz * tilesX + tx] = tileBase;
                  }
               }
            }
         }
      }
      return new LandmarkVolume(minX, minZ, sizeX, sizeZ, height, tileColumnBase, Arrays.copyOf(columnStart, columnEntries),
         Arrays.copyOf(bottoms, spans), Arrays.copyOf(tops, spans), Arrays.copyOf(materials, spans), layerBounds,
         dilate(occupied, sizeX, sizeZ, TREE_CLEARANCE));
   }

   /** The occupied columns grown by {@code radius} (a square), over the volume grown by {@code radius} on every side. */
   private static long[] dilate(boolean[] occupied, int sizeX, int sizeZ, int radius) {
      int width = sizeX + radius * 2;
      int depth = sizeZ + radius * 2;
      // Along x: every cell within radius of an occupied column of its row.
      boolean[] rows = new boolean[width * sizeZ];
      for (int k = 0; k < sizeZ; k++) {
         int last = Integer.MIN_VALUE / 2;
         for (int x = 0; x < width; x++) {
            int i = x - radius;
            if (i >= 0 && i < sizeX && occupied[k * sizeX + i]) {
               last = x;
            }
            // Columns up to radius ahead are found by the backward sweep below.
            rows[k * width + x] = x - last <= radius;
         }
         last = Integer.MAX_VALUE / 2;
         for (int x = width - 1; x >= 0; x--) {
            int i = x - radius;
            if (i >= 0 && i < sizeX && occupied[k * sizeX + i]) {
               last = x;
            }
            rows[k * width + x] |= last - x <= radius;
         }
      }
      long[] bits = new long[(int)(((long)width * depth + 63) >>> 6)];
      for (int x = 0; x < width; x++) {
         int last = Integer.MIN_VALUE / 2;
         boolean[] down = new boolean[depth];
         for (int z = 0; z < depth; z++) {
            int k = z - radius;
            if (k >= 0 && k < sizeZ && rows[k * width + x]) {
               last = z;
            }
            down[z] = z - last <= radius;
         }
         last = Integer.MAX_VALUE / 2;
         for (int z = depth - 1; z >= 0; z--) {
            int k = z - radius;
            if (k >= 0 && k < sizeZ && rows[k * width + x]) {
               last = z;
            }
            if (down[z] || last - z <= radius) {
               int bit = z * width + x;
               bits[bit >>> 6] |= 1L << (bit & 63);
            }
         }
      }
      return bits;
   }

   private static float min3(float[] corners, int offset) {
      return Math.min(corners[offset], Math.min(corners[offset + 3], corners[offset + 6]));
   }

   private static float max3(float[] corners, int offset) {
      return Math.max(corners[offset], Math.max(corners[offset + 3], corners[offset + 6]));
   }

   private static int clamp(int value, int size) {
      return Math.max(0, Math.min(size - 1, value));
   }

   /** Separating-axis test of a triangle against the unit voxel centred at (cx, cy, cz) (Akenine-Möller). */
   static boolean overlapsVoxel(double[] triangle, double cx, double cy, double cz) {
      double h = 0.5;
      double v0x = triangle[0] - cx, v0y = triangle[1] - cy, v0z = triangle[2] - cz;
      double v1x = triangle[3] - cx, v1y = triangle[4] - cy, v1z = triangle[5] - cz;
      double v2x = triangle[6] - cx, v2y = triangle[7] - cy, v2z = triangle[8] - cz;
      if (Math.min(v0x, Math.min(v1x, v2x)) > h || Math.max(v0x, Math.max(v1x, v2x)) < -h
         || Math.min(v0y, Math.min(v1y, v2y)) > h || Math.max(v0y, Math.max(v1y, v2y)) < -h
         || Math.min(v0z, Math.min(v1z, v2z)) > h || Math.max(v0z, Math.max(v1z, v2z)) < -h) {
         return false;
      }
      double[] edges = {v1x - v0x, v1y - v0y, v1z - v0z, v2x - v1x, v2y - v1y, v2z - v1z, v0x - v2x, v0y - v2y, v0z - v2z};
      for (int e = 0; e < 3; e++) {
         double ex = edges[e * 3], ey = edges[e * 3 + 1], ez = edges[e * 3 + 2];
         // Axes x × e, y × e and z × e.
         if (separated(0.0, -ez, ey, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
            || separated(ez, 0.0, -ex, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
            || separated(-ey, ex, 0.0, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)) {
            return false;
         }
      }
      double nx = edges[1] * edges[5] - edges[2] * edges[4];
      double ny = edges[2] * edges[3] - edges[0] * edges[5];
      double nz = edges[0] * edges[4] - edges[1] * edges[3];
      double d = -(nx * v0x + ny * v0y + nz * v0z);
      double reach = h * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
      return Math.abs(d) <= reach;
   }

   private static boolean separated(
      double ax, double ay, double az, double v0x, double v0y, double v0z, double v1x, double v1y, double v1z, double v2x, double v2y, double v2z, double h
   ) {
      double p0 = ax * v0x + ay * v0y + az * v0z;
      double p1 = ax * v1x + ay * v1y + az * v1z;
      double p2 = ax * v2x + ay * v2y + az * v2z;
      double r = h * (Math.abs(ax) + Math.abs(ay) + Math.abs(az));
      return Math.min(p0, Math.min(p1, p2)) > r || Math.max(p0, Math.max(p1, p2)) < -r;
   }

   public int minX() {
      return this.minX;
   }

   public int minZ() {
      return this.minZ;
   }

   public int maxX() {
      return this.minX + this.sizeX - 1;
   }

   public int maxZ() {
      return this.minZ + this.sizeZ - 1;
   }

   /** Layers above the base the landmark can reach. */
   public int height() {
      return this.height;
   }

   public int spanCount() {
      return this.spanBottom.length;
   }

   /** Index of the column at this block position, or -1 outside the volume and in tiles without blocks. */
   public int column(int worldX, int worldZ) {
      int i = worldX - this.minX;
      int k = worldZ - this.minZ;
      if (i < 0 || k < 0 || i >= this.sizeX || k >= this.sizeZ) {
         return -1;
      }
      int base = this.tileColumnBase[(k >> TILE_SHIFT) * this.tilesX + (i >> TILE_SHIFT)];
      return base < 0 ? -1 : base + ((k & TILE - 1) << TILE_SHIFT) + (i & TILE - 1);
   }

   public int firstSpan(int column) {
      return this.columnStart[column];
   }

   public int endSpan(int column) {
      return this.columnStart[column + 1];
   }

   /** Lowest layer of a span, counted from the landmark's base. */
   public int spanBottom(int span) {
      return this.spanBottom[span];
   }

   /** Highest layer (inclusive) of a span. */
   public int spanTop(int span) {
      return this.spanTop[span];
   }

   /** The span's material: a palette index for meshes with blocks, else a height band. */
   public int spanMaterial(int span) {
      return this.spanMaterial[span] & 0xFF;
   }

   /** Whether this block column lies within {@link #TREE_CLEARANCE} blocks of one of the landmark's columns. */
   public boolean nearColumn(int worldX, int worldZ) {
      int x = worldX - this.minX + TREE_CLEARANCE;
      int z = worldZ - this.minZ + TREE_CLEARANCE;
      int width = this.sizeX + TREE_CLEARANCE * 2;
      if (x < 0 || z < 0 || x >= width || z >= this.sizeZ + TREE_CLEARANCE * 2) {
         return false;
      }
      int bit = z * width + x;
      return (this.nearColumns[bit >>> 6] & 1L << (bit & 63)) != 0L;
   }

   /** Width in blocks of the wider side of this layer's bounding rectangle; 0 for an empty layer. */
   public int layerExtent(int layer) {
      int base = layer * 4;
      if (this.layerBounds[base] > this.layerBounds[base + 2]) {
         return 0;
      }
      return Math.max(this.layerBounds[base + 2] - this.layerBounds[base], this.layerBounds[base + 3] - this.layerBounds[base + 1]) + 1;
   }

   /** World x of the centre of this layer's bounding rectangle. */
   public int layerCenterX(int layer) {
      return this.minX + (this.layerBounds[layer * 4] + this.layerBounds[layer * 4 + 2]) / 2;
   }

   /** World z of the centre of this layer's bounding rectangle. */
   public int layerCenterZ(int layer) {
      return this.minZ + (this.layerBounds[layer * 4 + 1] + this.layerBounds[layer * 4 + 3]) / 2;
   }
}
