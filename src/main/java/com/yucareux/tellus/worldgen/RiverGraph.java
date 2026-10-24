package com.yucareux.tellus.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;

/**
 * Rivers as a directed graph of cells on a world-aligned grid. Waterway lines are drawn downstream, so
 * each pair of consecutive cells a line passes through is a downstream link. A fragment is only traversed
 * inside the bounds of the map tile it came from (the tile's clipped buffer belongs to its neighbour), so
 * the same map gives the same cells, links and sample points however tiles clipped it or whichever area
 * asked for it. Graphs built for overlapping areas therefore agree wherever both hold every line nearby.
 */
final class RiverGraph {
   /** Most terrain sample points kept per cell; enough for a few crossing lines. */
   static final int MAX_SAMPLES = 8;
   final int cellSize;
   final int count;
   final int[] cellX, cellZ;
   /** Sample points of the lines in each cell: midpoints of the pieces of line inside it, with the piece's direction. */
   final int[] sampleStart;
   final float[] sampleX, sampleZ, sampleDirX, sampleDirZ;
   /** Lowest feature id among the lines through the cell, and how many distinct ones pass. */
   final long[] feature;
   final int[] featureCount;
   /** Widest half-width (blocks) of the lines through the cell. */
   final float[] halfWidth;
   final int[] downStart, down, upStart, up;
   private final Long2IntOpenHashMap index;

   private RiverGraph(int cellSize, int count, int[] cellX, int[] cellZ, int[] sampleStart, float[] sampleX, float[] sampleZ,
      float[] sampleDirX, float[] sampleDirZ, long[] feature, int[] featureCount, float[] halfWidth,
      int[] downStart, int[] down, int[] upStart, int[] up, Long2IntOpenHashMap index) {
      this.cellSize = cellSize; this.count = count; this.cellX = cellX; this.cellZ = cellZ;
      this.sampleStart = sampleStart; this.sampleX = sampleX; this.sampleZ = sampleZ;
      this.sampleDirX = sampleDirX; this.sampleDirZ = sampleDirZ;
      this.feature = feature; this.featureCount = featureCount; this.halfWidth = halfWidth;
      this.downStart = downStart; this.down = down; this.upStart = upStart; this.up = up; this.index = index;
   }

   static long key(int cx, int cz) {
      return (long)cx << 32 ^ cz & 0xFFFFFFFFL;
   }

   /** The index of the cell at grid coordinates, or -1. */
   int cell(int cx, int cz) {
      return index.getOrDefault(key(cx, cz), -1);
   }

   /** The index of the cell containing a block position, or -1. */
   int cellAt(double x, double z) {
      return cell((int)Math.floor(x / cellSize), (int)Math.floor(z / cellSize));
   }

   double centerX(int cell) { return (cellX[cell] + 0.5) * cellSize; }
   double centerZ(int cell) { return (cellZ[cell] + 0.5) * cellSize; }

   /** Distance between two linked cells' centres. */
   double linkLength(int a, int b) {
      int dx = Math.abs(cellX[a] - cellX[b]), dz = Math.abs(cellZ[a] - cellZ[b]);
      return (dx + dz > 1 ? Math.sqrt(dx * dx + dz * dz) : 1.0) * cellSize;
   }

   static final class Builder {
      private final int cellSize;
      private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
      private final IntArrayList cellX = new IntArrayList(), cellZ = new IntArrayList();
      private final java.util.ArrayList<float[]> samples = new java.util.ArrayList<>();
      private final LongArrayList feature = new LongArrayList();
      private final java.util.ArrayList<LongOpenHashSet> features = new java.util.ArrayList<>();
      private final it.unimi.dsi.fastutil.floats.FloatArrayList halfWidth = new it.unimi.dsi.fastutil.floats.FloatArrayList();
      private final LongOpenHashSet links = new LongOpenHashSet();

      Builder(int cellSize) {
         if (cellSize < 1) throw new IllegalArgumentException("River cells must be at least one block");
         this.cellSize = cellSize;
         this.index.defaultReturnValue(-1);
      }

      /**
       * Adds a line drawn downstream, traversed only inside {@code [minX, maxX] x [minZ, maxZ]} (its map
       * tile; infinite bounds keep all of it).
       */
      void addLine(double[] xs, double[] zs, long featureId, double lineHalfWidth, double minX, double minZ, double maxX, double maxZ) {
         int previous = -1;
         for (int i = 1; i < xs.length; i++) {
            double ax = xs[i - 1], az = zs[i - 1], bx = xs[i], bz = zs[i];
            double dx = bx - ax, dz = bz - az;
            // Liang-Barsky clip of the segment to the tile.
            double t0 = 0.0, t1 = 1.0;
            double[] p = {-dx, dx, -dz, dz}, q = {ax - minX, maxX - ax, az - minZ, maxZ - az};
            boolean inside = true;
            for (int k = 0; k < 4 && inside; k++) {
               if (p[k] == 0.0) { if (q[k] < 0.0) inside = false; }
               else {
                  double r = q[k] / p[k];
                  if (p[k] < 0.0) { if (r > t1) inside = false; else if (r > t0) t0 = r; }
                  else { if (r < t0) inside = false; else if (r < t1) t1 = r; }
               }
            }
            if (!inside || t1 - t0 <= 0.0) { previous = -1; continue; }
            // A piece starting after the segment start begins a new run (it entered the tile there).
            if (t0 > 0.0) previous = -1;
            previous = traverse(ax + dx * t0, az + dz * t0, ax + dx * t1, az + dz * t1, featureId, lineHalfWidth, previous);
            if (t1 < 1.0) previous = -1;
         }
      }

      /** Walks the cells of one segment (Amanatides-Woo), recording links and piece midpoints; returns the last cell. */
      private int traverse(double ax, double az, double bx, double bz, long featureId, double lineHalfWidth, int previous) {
         double dx = bx - ax, dz = bz - az, length = Math.sqrt(dx * dx + dz * dz);
         if (length < 1.0E-9) return previous;
         float ux = (float)(dx / length), uz = (float)(dz / length);
         int cx = (int)Math.floor(ax / cellSize), cz = (int)Math.floor(az / cellSize);
         int endX = (int)Math.floor(bx / cellSize), endZ = (int)Math.floor(bz / cellSize);
         int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0, stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
         double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(dx);
         double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : cellSize / Math.abs(dz);
         double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? (cx + 1) * (double)cellSize : cx * (double)cellSize) - ax) / dx;
         double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? (cz + 1) * (double)cellSize : cz * (double)cellSize) - az) / dz;
         double tIn = 0.0;
         int guard = Math.abs(endX - cx) + Math.abs(endZ - cz) + 2;
         while (true) {
            double tOut = Math.min(1.0, Math.min(tMaxX, tMaxZ));
            int cell = cell(cx, cz);
            double mid = (tIn + tOut) * 0.5;
            if (tOut > tIn) addSample(cell, (float)(ax + dx * mid), (float)(az + dz * mid), ux, uz);
            addFeature(cell, featureId, lineHalfWidth);
            if (previous >= 0 && previous != cell) links.add((long)previous << 32 | cell & 0xFFFFFFFFL);
            previous = cell;
            if (cx == endX && cz == endZ || --guard < 0 || tOut >= 1.0) break;
            // At an exact corner both steps happen; x first keeps the order deterministic.
            if (tMaxX <= tMaxZ) { cx += stepX; tIn = tMaxX; tMaxX += tDeltaX; }
            else { cz += stepZ; tIn = tMaxZ; tMaxZ += tDeltaZ; }
         }
         return previous;
      }

      private int cell(int cx, int cz) {
         long key = key(cx, cz);
         int cell = index.get(key);
         if (cell >= 0) return cell;
         cell = cellX.size();
         index.put(key, cell);
         cellX.add(cx); cellZ.add(cz);
         samples.add(new float[0]);
         feature.add(Long.MAX_VALUE);
         features.add(null);
         halfWidth.add(0.0F);
         return cell;
      }

      private void addSample(int cell, float x, float z, float ux, float uz) {
         float[] current = samples.get(cell);
         int n = current.length / 4;
         // Keep a deterministic subset: the samples with the smallest (x, z), whatever order lines arrive in.
         float[] next = Arrays.copyOf(current, current.length + 4);
         next[n * 4] = x; next[n * 4 + 1] = z; next[n * 4 + 2] = ux; next[n * 4 + 3] = uz;
         samples.set(cell, next);
      }

      private void addFeature(int cell, long featureId, double lineHalfWidth) {
         if (featureId < feature.getLong(cell)) feature.set(cell, featureId);
         LongOpenHashSet set = features.get(cell);
         if (set == null) features.set(cell, set = new LongOpenHashSet(2));
         set.add(featureId);
         if (lineHalfWidth > halfWidth.getFloat(cell)) halfWidth.set(cell, (float)lineHalfWidth);
      }

      RiverGraph build() {
         int count = cellX.size();
         int[] sampleStart = new int[count + 1];
         float[][] kept = new float[count][];
         for (int c = 0; c < count; c++) {
            kept[c] = canonicalSamples(samples.get(c));
            sampleStart[c + 1] = sampleStart[c] + kept[c].length / 4;
         }
         int total = sampleStart[count];
         float[] sx = new float[total], sz = new float[total], ux = new float[total], uz = new float[total];
         for (int c = 0; c < count; c++) {
            float[] s = kept[c];
            for (int k = 0; k < s.length / 4; k++) {
               int at = sampleStart[c] + k;
               sx[at] = s[k * 4]; sz[at] = s[k * 4 + 1]; ux[at] = s[k * 4 + 2]; uz[at] = s[k * 4 + 3];
            }
         }
         int[] featureCount = new int[count];
         for (int c = 0; c < count; c++) featureCount[c] = features.get(c) == null ? 0 : features.get(c).size();
         long[] sorted = links.toLongArray();
         Arrays.sort(sorted);
         int[] downStart = new int[count + 1], upStart = new int[count + 1];
         for (long link : sorted) { downStart[(int)(link >>> 32) + 1]++; upStart[(int)link + 1]++; }
         for (int c = 0; c < count; c++) { downStart[c + 1] += downStart[c]; upStart[c + 1] += upStart[c]; }
         int[] down = new int[sorted.length], up = new int[sorted.length];
         int[] downFill = Arrays.copyOf(downStart, count), upFill = Arrays.copyOf(upStart, count);
         for (long link : sorted) {
            int from = (int)(link >>> 32), to = (int)link;
            down[downFill[from]++] = to;
            up[upFill[to]++] = from;
         }
         Long2IntOpenHashMap frozen = new Long2IntOpenHashMap(index);
         frozen.defaultReturnValue(-1);
         return new RiverGraph(cellSize, count, cellX.toIntArray(), cellZ.toIntArray(), sampleStart, sx, sz, ux, uz,
            feature.toLongArray(), featureCount, halfWidth.toFloatArray(), downStart, down, upStart, up, frozen);
      }

      /** Distinct samples ordered by position, thinned evenly to {@link #MAX_SAMPLES}. */
      private static float[] canonicalSamples(float[] raw) {
         int n = raw.length / 4;
         Integer[] order = new Integer[n];
         for (int i = 0; i < n; i++) order[i] = i;
         Arrays.sort(order, (a, b) -> {
            int c = Float.compare(raw[a * 4], raw[b * 4]);
            if (c != 0) return c;
            c = Float.compare(raw[a * 4 + 1], raw[b * 4 + 1]);
            if (c != 0) return c;
            c = Float.compare(raw[a * 4 + 2], raw[b * 4 + 2]);
            return c != 0 ? c : Float.compare(raw[a * 4 + 3], raw[b * 4 + 3]);
         });
         float[] distinct = new float[raw.length];
         int kept = 0;
         for (int i = 0; i < n; i++) {
            int s = order[i] * 4;
            if (kept > 0 && distinct[(kept - 1) * 4] == raw[s] && distinct[(kept - 1) * 4 + 1] == raw[s + 1]) continue;
            System.arraycopy(raw, s, distinct, kept * 4, 4);
            kept++;
         }
         if (kept <= MAX_SAMPLES) return Arrays.copyOf(distinct, kept * 4);
         float[] thinned = new float[MAX_SAMPLES * 4];
         for (int k = 0; k < MAX_SAMPLES; k++) {
            int pick = (int)((long)k * (kept - 1) / (MAX_SAMPLES - 1));
            System.arraycopy(distinct, pick * 4, thinned, k * 4, 4);
         }
         return thinned;
      }
   }
}
