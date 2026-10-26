package com.yucareux.tellus.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Courses through a mapped river area whose water no drawn waterway reaches over a good part of it: a river mapped
 * only as an area near its mouth, a side arm, an estuary. Each course is the line a waterway would take along the
 * middle of the water, from where a waterway runs into the area (or from the far end of water nothing reaches) to
 * where it drains: a waterway leaving it, the waterway already drawn through the rest of it, or the sea. The river
 * network draws them like mapped waterways, so the whole area takes its levels from them.
 *
 * <p>They are computed from the whole water body and the map around it, never from one tile's view of it, so every
 * tile meeting the area draws the same courses.</p>
 */
final class RiverAreaCourses {
   /** Most raster cells of an area. */
   private static final int MAX_CELLS = 1 << 19;
   /** Fewest raster cells across the typical width of the water. */
   private static final double CELLS_ACROSS = 4.0;
   /** How strongly a course keeps to the middle of the water rather than taking the shortest way. */
   private static final double CENTRE = 4.0;
   /** Smallest share of an area beyond the reach of drawn waterways that gets a course. */
   private static final double UNCOVERED_SHARE = 0.05;

   /** Terrain height, for the direction of water no waterway enters or leaves. */
   interface Terrain { double height(double x, double z); }

   private RiverAreaCourses() {
   }

   /**
    * The courses through an area, each {xs, zs} in flow order; none when drawn waterways already reach nearly all
    * of it.
    *
    * @param covering the courses of waterways drawn in the area, each {xs, zs}, how far each reaches, and whether
    *                 the water around it drains into it (false for a tributary ending in the area)
    * @param inflows  the points where waterways end in or at the area (they run into it)
    * @param outflows the points where waterways begin in or at the area (they drain it)
    * @param touch    how far from the area such a point may lie, blocks
    */
   static List<double[][]> courses(List<HydrologyWaterMap.Shape> shapes, List<double[][]> covering, double[] reach, boolean[] drains,
      List<double[]> inflows, List<double[]> outflows, List<HydrologyWaterMap.Shape> oceans, double touch, Terrain terrain) {
      double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX, area = 0.0, perimeter = 0.0;
      for (HydrologyWaterMap.Shape shape : shapes) {
         minX = Math.min(minX, shape.minX); minZ = Math.min(minZ, shape.minZ);
         maxX = Math.max(maxX, shape.maxX); maxZ = Math.max(maxZ, shape.maxZ);
         area += shape.area();
         for (int p = 0; p < shape.xs.length; p++)
            for (int i = 0, j = shape.xs[p].length - 1; i < shape.xs[p].length; j = i++)
               perimeter += Math.hypot(shape.xs[p][i] - shape.xs[p][j], shape.zs[p][i] - shape.zs[p][j]);
      }
      if (!(area > 0.0) || !(perimeter > 0.0)) return List.of();
      // Fine enough to follow the water's middle, coarse enough to stay a bounded raster.
      double cell = Math.max(1.0, Math.sqrt((maxX - minX) * (maxZ - minZ) / MAX_CELLS));
      double width = 2.0 * area / perimeter;
      if (width / cell < CELLS_ACROSS) cell = Math.max(1.0, width / CELLS_ACROSS);
      int columns = (int)Math.ceil((maxX - minX) / cell) + 4, rows = (int)Math.ceil((maxZ - minZ) / cell) + 4;
      if ((long)columns * rows > 4L * MAX_CELLS) return List.of();
      Raster raster = new Raster(minX - 2.0 * cell, minZ - 2.0 * cell, cell, columns, rows);
      int n = columns * rows, insideCount = 0;
      boolean[] inside = new boolean[n];
      for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
         int i = r * columns + c;
         inside[i] = containsAny(shapes, raster.x(c), raster.z(r));
         if (inside[i]) insideCount++;
      }
      if (insideCount == 0) return List.of();
      float[] shore = shoreDistance(inside, columns, rows);

      // Water a drawn waterway already reaches drains into it (unless it only runs into the area there).
      boolean[] covered = new boolean[n];
      int[] cover = new int[n];
      Arrays.fill(cover, -1);
      for (int w = 0; w < covering.size(); w++) {
         double[] xs = covering.get(w)[0], zs = covering.get(w)[1];
         double r = reach[w];
         for (int k = 1; k < xs.length; k++) {
            int c0 = raster.column(Math.min(xs[k - 1], xs[k]) - r), c1 = raster.column(Math.max(xs[k - 1], xs[k]) + r);
            int r0 = raster.row(Math.min(zs[k - 1], zs[k]) - r), r1 = raster.row(Math.max(zs[k - 1], zs[k]) + r);
            for (int row = Math.max(0, r0); row <= Math.min(rows - 1, r1); row++)
               for (int col = Math.max(0, c0); col <= Math.min(columns - 1, c1); col++) {
                  int i = row * columns + col;
                  if (!inside[i] || covered[i]) continue;
                  if (segmentDistance(raster.x(col), raster.z(row), xs[k - 1], zs[k - 1], xs[k], zs[k]) <= r) { covered[i] = true; cover[i] = w; }
               }
         }
      }
      int uncovered = 0;
      for (int i = 0; i < n; i++) if (inside[i] && !covered[i]) uncovered++;
      if (uncovered < Math.max(16, insideCount * UNCOVERED_SHARE)) return List.of();

      int search = (int)Math.ceil(touch / raster.cell) + 1;
      // Where the water drains: covered water, waterways leaving the area, the sea beside it.
      boolean[] sink = new boolean[n];
      double[][] drainTo = new double[n][];
      for (int i = 0; i < n; i++) if (covered[i] && drains[cover[i]]) sink[i] = true;
      for (double[] point : outflows) {
         int i = raster.nearestInside(inside, point[0], point[1], search);
         if (i >= 0) { sink[i] = true; drainTo[i] = point; }
      }
      if (!oceans.isEmpty()) for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
         int i = r * columns + c;
         if (!inside[i] || shore[i] > 1.5F) continue;
         for (int d = 0; d < 8 && drainTo[i] == null; d++) {
            int nc = c + DC[d], nr = r + DR[d];
            if (nc < 0 || nr < 0 || nc >= columns || nr >= rows || inside[nr * columns + nc]) continue;
            double x = raster.x(nc), z = raster.z(nr);
            if (containsAny(oceans, x, z)) { sink[i] = true; drainTo[i] = new double[]{x, z}; }
         }
      }
      // Where waterways run in, in water nothing reaches yet.
      List<int[]> sources = new ArrayList<>();
      List<double[]> sourcePoints = new ArrayList<>();
      for (double[] point : inflows) {
         int i = raster.nearestInside(inside, point[0], point[1], search);
         if (i >= 0 && !sink[i]) { sources.add(new int[]{i}); sourcePoints.add(point); }
      }
      boolean anySink = false;
      for (boolean s : sink) anySink |= s;
      if (!anySink) {
         // Nothing drains the area: it drains at its far end from the waterways running in, or (none either) at
         // the lower end of its longest way through.
         int[] ends;
         if (!sources.isEmpty()) {
            int[] start = new int[sources.size()];
            for (int s = 0; s < start.length; s++) start[s] = sources.get(s)[0];
            ends = new int[]{farthest(inside, columns, rows, start)};
         } else {
            int any = 0;
            while (!inside[any]) any++;
            int a = farthest(inside, columns, rows, new int[]{any}), b = farthest(inside, columns, rows, new int[]{a});
            boolean aHigher = terrain.height(raster.x(a % columns), raster.z(a / columns)) >= terrain.height(raster.x(b % columns), raster.z(b / columns));
            ends = new int[]{aHigher ? b : a};
            sources.add(new int[]{aHigher ? a : b});
            sourcePoints.add(null);
         }
         for (int e : ends) sink[e] = true;
      }

      // The cheapest way to drain from every cell, keeping to the middle of the water.
      double[] cost = new double[n];
      int[] next = new int[n];
      drain(inside, shore, sink, columns, rows, cost, next);

      // Water nothing reaches and no waterway enters still drains from its far end.
      boolean[] seen = new boolean[n];
      for (int[] source : sources) patch(source[0], inside, sink, seen, cost, columns, rows);
      for (int i = 0; i < n; i++) {
         if (!inside[i] || covered[i] || seen[i] || sink[i] || !Double.isFinite(cost[i])) continue;
         int[] found = patch(i, inside, sink, seen, cost, columns, rows);
         if (found[1] >= 16) { sources.add(new int[]{found[0]}); sourcePoints.add(null); }
      }
      if (sources.isEmpty()) return List.of();

      // Courses from the farthest sources first; a later one ends where it meets an earlier course.
      Integer[] order = new Integer[sources.size()];
      for (int s = 0; s < order.length; s++) order[s] = s;
      Arrays.sort(order, (a, b) -> {
         int ca = Double.compare(cost[sources.get(b)[0]], cost[sources.get(a)[0]]);
         return ca != 0 ? ca : Integer.compare(sources.get(a)[0], sources.get(b)[0]);
      });
      boolean[] used = new boolean[n];
      List<double[][]> courses = new ArrayList<>();
      for (int s : order) {
         int at = sources.get(s)[0];
         if (!Double.isFinite(cost[at]) || used[at]) continue;
         List<double[]> points = new ArrayList<>();
         double[] start = sourcePoints.get(s);
         if (start != null) points.add(start);
         int guard = n;
         while (guard-- > 0) {
            points.add(new double[]{raster.x(at % columns), raster.z(at / columns)});
            if (used[at]) break;
            used[at] = true;
            if (sink[at]) {
               double[] end = drainTo[at] != null ? drainTo[at] : cover[at] >= 0 ? nearestOn(covering.get(cover[at]), points.get(points.size() - 1)) : null;
               if (end != null) points.add(end);
               break;
            }
            if (next[at] < 0) break;
            at = next[at];
         }
         double[][] course = smooth(points, raster.cell);
         if (course[0].length >= 2) courses.add(course);
      }
      return courses;
   }

   private static final int[] DC = {1, 1, 0, -1, -1, -1, 0, 1};
   private static final int[] DR = {0, 1, 1, 1, 0, -1, -1, -1};

   /** A grid of square cells over an area. */
   private record Raster(double originX, double originZ, double cell, int columns, int rows) {
      double x(int column) { return originX + (column + 0.5) * cell; }
      double z(int row) { return originZ + (row + 0.5) * cell; }
      int column(double x) { return (int)Math.floor((x - originX) / cell); }
      int row(double z) { return (int)Math.floor((z - originZ) / cell); }

      /** The inside cell nearest a point within a few cells of it, or -1. */
      int nearestInside(boolean[] inside, double x, double z, int search) {
         int c = column(x), r = row(z), best = -1;
         double bestDistance = Double.POSITIVE_INFINITY;
         for (int dr = -search; dr <= search; dr++) for (int dc = -search; dc <= search; dc++) {
            int nc = c + dc, nr = r + dr;
            if (nc < 0 || nr < 0 || nc >= columns || nr >= rows || !inside[nr * columns + nc]) continue;
            double d = Math.hypot(x(nc) - x, z(nr) - z);
            if (d < bestDistance) { bestDistance = d; best = nr * columns + nc; }
         }
         return best;
      }
   }

   /** Distance of each inside cell to the nearest outside one, cells (two-pass chamfer). */
   private static float[] shoreDistance(boolean[] inside, int columns, int rows) {
      float[] d = new float[inside.length];
      float diagonal = (float)Math.sqrt(2.0);
      for (int i = 0; i < d.length; i++) d[i] = inside[i] ? Float.POSITIVE_INFINITY : 0.0F;
      for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) {
         int i = r * columns + c;
         if (d[i] == 0.0F) continue;
         if (c > 0) d[i] = Math.min(d[i], d[i - 1] + 1.0F);
         if (r > 0) {
            d[i] = Math.min(d[i], d[i - columns] + 1.0F);
            if (c > 0) d[i] = Math.min(d[i], d[i - columns - 1] + diagonal);
            if (c + 1 < columns) d[i] = Math.min(d[i], d[i - columns + 1] + diagonal);
         }
      }
      for (int r = rows - 1; r >= 0; r--) for (int c = columns - 1; c >= 0; c--) {
         int i = r * columns + c;
         if (d[i] == 0.0F) continue;
         if (c + 1 < columns) d[i] = Math.min(d[i], d[i + 1] + 1.0F);
         if (r + 1 < rows) {
            d[i] = Math.min(d[i], d[i + columns] + 1.0F);
            if (c + 1 < columns) d[i] = Math.min(d[i], d[i + columns + 1] + diagonal);
            if (c > 0) d[i] = Math.min(d[i], d[i + columns - 1] + diagonal);
         }
      }
      return d;
   }

   /** Dijkstra from the sinks over inside cells; {@code next} points one step towards the sink. */
   private static void drain(boolean[] inside, float[] shore, boolean[] sink, int columns, int rows, double[] cost, int[] next) {
      int n = inside.length;
      Arrays.fill(cost, Double.POSITIVE_INFINITY);
      Arrays.fill(next, -1);
      java.util.PriorityQueue<long[]> queue = new java.util.PriorityQueue<>((a, b) -> {
         int c = Double.compare(Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0]));
         return c != 0 ? c : Long.compare(a[1], b[1]);
      });
      for (int i = 0; i < n; i++) if (sink[i] && inside[i]) { cost[i] = 0.0; queue.add(new long[]{Double.doubleToLongBits(0.0), i}); }
      boolean[] done = new boolean[n];
      while (!queue.isEmpty()) {
         long[] top = queue.poll();
         int at = (int)top[1];
         if (done[at]) continue;
         done[at] = true;
         int c = at % columns, r = at / columns;
         for (int d = 0; d < 8; d++) {
            int nc = c + DC[d], nr = r + DR[d];
            if (nc < 0 || nr < 0 || nc >= columns || nr >= rows) continue;
            int m = nr * columns + nc;
            if (!inside[m] || done[m]) continue;
            double step = (d % 2 == 0 ? 1.0 : Math.sqrt(2.0)) * (1.0 + CENTRE / (1.0 + Math.min(shore[m], shore[at])));
            double candidate = cost[at] + step;
            if (candidate < cost[m]) {
               cost[m] = candidate;
               next[m] = at;
               queue.add(new long[]{Double.doubleToLongBits(candidate), m});
            }
         }
      }
   }

   /**
    * Floods the patch of water around a cell up to where it drains, marking it seen: {the cell of it farthest from
    * where it drains, its size}.
    */
   private static int[] patch(int start, boolean[] inside, boolean[] sink, boolean[] seen, double[] cost,
      int columns, int rows) {
      if (seen[start]) return new int[]{start, 0};
      int far = start, size = 0;
      java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
      queue.add(start);
      seen[start] = true;
      while (!queue.isEmpty()) {
         int at = queue.removeFirst();
         size++;
         if (Double.isFinite(cost[at]) && (!Double.isFinite(cost[far]) || cost[at] > cost[far] || cost[at] == cost[far] && at < far)) far = at;
         int c = at % columns, r = at / columns;
         for (int d = 0; d < 8; d++) {
            int nc = c + DC[d], nr = r + DR[d];
            if (nc < 0 || nr < 0 || nc >= columns || nr >= rows) continue;
            int m = nr * columns + nc;
            if (!inside[m] || sink[m] || seen[m]) continue;
            seen[m] = true;
            queue.add(m);
         }
      }
      return new int[]{far, size};
   }

   /** The inside cell farthest (in steps through the water) from the given cells. */
   private static int farthest(boolean[] inside, int columns, int rows, int[] from) {
      int[] distance = new int[inside.length];
      Arrays.fill(distance, -1);
      java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
      for (int f : from) { distance[f] = 0; queue.add(f); }
      int far = from[0];
      while (!queue.isEmpty()) {
         int at = queue.removeFirst();
         if (distance[at] > distance[far] || distance[at] == distance[far] && at < far) far = at;
         int c = at % columns, r = at / columns;
         for (int d = 0; d < 8; d++) {
            int nc = c + DC[d], nr = r + DR[d];
            if (nc < 0 || nr < 0 || nc >= columns || nr >= rows) continue;
            int m = nr * columns + nc;
            if (!inside[m] || distance[m] >= 0) continue;
            distance[m] = distance[at] + 1;
            queue.add(m);
         }
      }
      return far;
   }

   /** The point of a course nearest a point. */
   private static double[] nearestOn(double[][] course, double[] point) {
      double[] xs = course[0], zs = course[1], best = {xs[0], zs[0]};
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int k = 1; k < xs.length; k++) {
         double dx = xs[k] - xs[k - 1], dz = zs[k] - zs[k - 1], length = dx * dx + dz * dz;
         double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, ((point[0] - xs[k - 1]) * dx + (point[1] - zs[k - 1]) * dz) / length)) : 0.0;
         double x = xs[k - 1] + dx * t, z = zs[k - 1] + dz * t, d = Math.hypot(point[0] - x, point[1] - z);
         if (d < bestDistance) { bestDistance = d; best = new double[]{x, z}; }
      }
      return best;
   }

   /** A raster path as a smooth polyline: a running mean of the cell centres, thinned, the ends kept exactly. */
   private static double[][] smooth(List<double[]> points, double cell) {
      int n = points.size();
      double[] xs = new double[n], zs = new double[n];
      for (int i = 0; i < n; i++) {
         if (i == 0 || i == n - 1) { xs[i] = points.get(i)[0]; zs[i] = points.get(i)[1]; continue; }
         double sx = 0.0, sz = 0.0;
         int count = 0;
         for (int j = Math.max(1, i - 2); j <= Math.min(n - 2, i + 2); j++) { sx += points.get(j)[0]; sz += points.get(j)[1]; count++; }
         xs[i] = sx / count; zs[i] = sz / count;
      }
      boolean[] keep = new boolean[n];
      keep[0] = true;
      keep[n - 1] = true;
      simplify(xs, zs, 0, n - 1, cell * 0.5, keep);
      int kept = 0;
      for (boolean k : keep) if (k) kept++;
      double[] outX = new double[kept], outZ = new double[kept];
      for (int i = 0, j = 0; i < n; i++) if (keep[i]) { outX[j] = xs[i]; outZ[j] = zs[i]; j++; }
      return new double[][]{outX, outZ};
   }

   /** Douglas-Peucker thinning of the points between two kept ones. */
   private static void simplify(double[] xs, double[] zs, int from, int to, double tolerance, boolean[] keep) {
      if (to - from < 2) return;
      int worst = -1;
      double worstDistance = tolerance;
      for (int i = from + 1; i < to; i++) {
         double d = segmentDistance(xs[i], zs[i], xs[from], zs[from], xs[to], zs[to]);
         if (d > worstDistance) { worstDistance = d; worst = i; }
      }
      if (worst < 0) return;
      keep[worst] = true;
      simplify(xs, zs, from, worst, tolerance, keep);
      simplify(xs, zs, worst, to, tolerance, keep);
   }

   private static double segmentDistance(double x, double z, double ax, double az, double bx, double bz) {
      double dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
      double t = length > 0.0 ? Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / length)) : 0.0;
      return Math.hypot(x - ax - dx * t, z - az - dz * t);
   }

   private static boolean containsAny(List<HydrologyWaterMap.Shape> shapes, double x, double z) {
      for (HydrologyWaterMap.Shape shape : shapes) if (shape.near(x, z, 0.0) && shape.contains(x, z)) return true;
      return false;
   }
}
