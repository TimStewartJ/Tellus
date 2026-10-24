package com.yucareux.tellus.worldgen;

import java.util.Arrays;

/**
 * Water levels along a {@link RiverGraph}: never rising downstream, never above the observed terrain except
 * where a short dip is filled, and with real drops (falls, weirs, rapids) kept sharp.
 *
 * <ol>
 * <li>A closing along the graph fills dips shorter than about twice {@code closeRadius} (a pit in the
 * terrain model, the far side of a narrow channel) and leaves steps and monotone runs untouched.</li>
 * <li>Each cell then takes the lowest of those values upstream within {@code carveRadius}: bumps where the
 * terrain model stands above the water (a bridge deck, a ridge cut by a map line, a canopy) are carved
 * through, and the level never rises downstream unless a dip outlasts the whole window.</li>
 * <li>Where a level still rises downstream (such a dip, or a fixed level such as the sea or a main river
 * higher than a tributary's own terrain), the cells upstream are raised to it within {@code fillRadius}.</li>
 * </ol>
 *
 * Every step reads only cells within its radius, so a cell's level depends only on the graph within the
 * sum of the radii; areas solved with at least that much margin agree.
 */
final class RiverProfileSolver {
   private RiverProfileSolver() {
   }

   record Parameters(double closeRadius, double carveRadius, double fillRadius) {
      double reach() { return closeRadius * 2.0 + carveRadius + fillRadius; }
   }

   /**
    * @param ground observed terrain per cell (NaN when unknown: such cells follow their neighbours)
    * @param fixed  fixed water levels (NaN when free): the sea, a lake, or a main river's level
    */
   static double[] solve(RiverGraph graph, double[] ground, double[] fixed, Parameters parameters) {
      int count = graph.count;
      double[] values = new double[count];
      for (int c = 0; c < count; c++)
         values[c] = Double.isFinite(fixed[c]) ? fixed[c] : Double.isFinite(ground[c]) ? ground[c] : Double.POSITIVE_INFINITY;
      Ball ball = new Ball(count);
      double[] closed = closing(graph, values, fixed, parameters.closeRadius(), ball);
      double[] carved = carve(graph, closed, fixed, parameters.carveRadius(), ball);
      double[] filled = fill(graph, carved, fixed, parameters.fillRadius(), ball);
      for (int c = 0; c < count; c++) {
         if (Double.isFinite(fixed[c])) filled[c] = fixed[c];
         else if (!Double.isFinite(filled[c])) filled[c] = Double.NaN;
      }
      return filled;
   }

   /** The highest value within a distance in either direction, then the lowest of those; fixed cells keep theirs. */
   static double[] closing(RiverGraph graph, double[] values, double[] fixed, double radius, Ball ball) {
      if (radius <= 0.0) return values.clone();
      int count = graph.count;
      double[] dilated = new double[count], result = new double[count];
      for (int c = 0; c < count; c++) {
         int n = ball.collect(graph, c, radius, true, true);
         double best = Double.NEGATIVE_INFINITY;
         for (int i = 0; i < n; i++) {
            double v = values[ball.cells[i]];
            if (Double.isFinite(v)) best = Math.max(best, v);
         }
         dilated[c] = best == Double.NEGATIVE_INFINITY ? values[c] : best;
      }
      for (int c = 0; c < count; c++) {
         if (Double.isFinite(fixed[c])) { result[c] = fixed[c]; continue; }
         int n = ball.collect(graph, c, radius, true, true);
         double best = dilated[c];
         for (int i = 0; i < n; i++) best = Math.min(best, dilated[ball.cells[i]]);
         // A closing never lowers a known value; an unknown cell takes its surroundings'.
         result[c] = Double.isFinite(values[c]) ? Math.max(values[c], best) : best;
      }
      return result;
   }

   /** The lowest value upstream within a distance (the cell itself included). */
   static double[] carve(RiverGraph graph, double[] values, double[] fixed, double radius, Ball ball) {
      int count = graph.count;
      double[] result = new double[count];
      for (int c = 0; c < count; c++) {
         if (Double.isFinite(fixed[c])) { result[c] = fixed[c]; continue; }
         int n = ball.collect(graph, c, radius, true, false);
         double best = values[c];
         for (int i = 0; i < n; i++) best = Math.min(best, values[ball.cells[i]]);
         result[c] = best;
      }
      return result;
   }

   /** Raises each free cell to the highest level downstream within a distance, which only changes cells below a later rise. */
   static double[] fill(RiverGraph graph, double[] values, double[] fixed, double radius, Ball ball) {
      int count = graph.count;
      double[] result = values.clone();
      if (radius <= 0.0) return result;
      for (int c = 0; c < count; c++) {
         if (Double.isFinite(fixed[c])) continue;
         int n = ball.collect(graph, c, radius, false, true);
         double best = values[c];
         for (int i = 0; i < n; i++) {
            double v = values[ball.cells[i]];
            if (Double.isFinite(v)) best = Math.max(best, v);
         }
         result[c] = best;
      }
      return result;
   }

   /** Cells within a graph distance of a start cell, by distance between cell centres (Dijkstra). */
   static final class Ball {
      int[] cells;
      private double[] distance;
      private int[] heap;
      private int heapSize;
      private int[] stamp;
      private int epoch;

      Ball(int count) {
         cells = new int[Math.max(1, count)];
         distance = new double[Math.max(1, count)];
         heap = new int[Math.max(4, count * 2)];
         stamp = new int[Math.max(1, count)];
      }

      /** Collects the cells reachable within {@code radius}; returns how many, in {@link #cells}. */
      int collect(RiverGraph graph, int start, double radius, boolean upstream, boolean downstream) {
         if (++epoch == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); epoch = 1; }
         int n = 0;
         heapSize = 0;
         distance[start] = 0.0;
         stamp[start] = epoch;
         push(start);
         while (heapSize > 0) {
            int c = pop();
            if (stamp[c] == -epoch) continue;
            stamp[c] = -epoch;
            cells[n++] = c;
            if (upstream) for (int k = graph.upStart[c]; k < graph.upStart[c + 1]; k++) relax(graph, c, graph.up[k], radius);
            if (downstream) for (int k = graph.downStart[c]; k < graph.downStart[c + 1]; k++) relax(graph, c, graph.down[k], radius);
         }
         return n;
      }

      private void relax(RiverGraph graph, int from, int to, double radius) {
         if (stamp[to] == -epoch) return;
         double d = distance[from] + graph.linkLength(from, to);
         if (d > radius) return;
         if (stamp[to] != epoch || d < distance[to]) {
            stamp[to] = epoch;
            distance[to] = d;
            push(to);
         }
      }

      private void push(int cell) {
         if (heapSize == heap.length) heap = Arrays.copyOf(heap, heap.length * 2);
         int i = heapSize++;
         heap[i] = cell;
         while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (distance[heap[parent]] <= distance[heap[i]]) break;
            int t = heap[parent]; heap[parent] = heap[i]; heap[i] = t;
            i = parent;
         }
      }

      private int pop() {
         int top = heap[0];
         heap[0] = heap[--heapSize];
         int i = 0;
         while (true) {
            int l = i * 2 + 1, r = l + 1, m = i;
            if (l < heapSize && distance[heap[l]] < distance[heap[m]]) m = l;
            if (r < heapSize && distance[heap[r]] < distance[heap[m]]) m = r;
            if (m == i) break;
            int t = heap[m]; heap[m] = heap[i]; heap[i] = t;
            i = m;
         }
         return top;
      }
   }
}
