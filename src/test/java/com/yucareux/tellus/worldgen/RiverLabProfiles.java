package com.yucareux.tellus.worldgen;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/** Candidate longitudinal profile operators compared by {@link RiverProfileLabTest}. */
final class RiverLabProfiles {
   private RiverLabProfiles() {
   }

   static double[] solve(String variant, RiverGraph graph, double[] ground, double[] fixed, DoubleUnaryOperator meters) {
      double[] g = ground.clone();
      for (int c = 0; c < g.length; c++) if (!Double.isFinite(g[c])) g[c] = 1.0E9;
      for (int c = 0; c < g.length; c++) if (Double.isFinite(fixed[c])) g[c] = fixed[c];
      double[] result;
      switch (variant) {
         case "raw" -> result = g.clone();
         case "carve" -> result = carve(graph, g, meters.applyAsDouble(8000), 0.0);
         case "median-carve" -> result = carve(graph, median(graph, g, meters.applyAsDouble(300)), meters.applyAsDouble(8000), 0.0);
         case "median-fill-carve" -> result = carve(graph, fill(graph, median(graph, g, meters.applyAsDouble(300)), meters.applyAsDouble(1000), 0.0),
            meters.applyAsDouble(8000), 0.0);
         case "leaky" -> result = carve(graph, median(graph, g, meters.applyAsDouble(300)), meters.applyAsDouble(16000), 0.0003);
         case "median-fill-leaky" -> result = carve(graph, fill(graph, median(graph, g, meters.applyAsDouble(300)), meters.applyAsDouble(1000), 0.0003),
            meters.applyAsDouble(16000), 0.0003);
         case "close-carve" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(8000), 0.0);
         case "close400-carve" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(400)), meters.applyAsDouble(8000), 0.0);
         case "close-carve16" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(16000), 0.0);
         case "close-carve1" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(1000), 0.0);
         case "close-carve2" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(2000), 0.0);
         case "close-carve4" -> result = carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(4000), 0.0);
         case "close-carve2-fill" -> result = fill(graph, carve(graph, closing(graph, g, meters.applyAsDouble(200)), meters.applyAsDouble(2000), 0.0),
            meters.applyAsDouble(2000), 0.0);
         default -> throw new IllegalArgumentException("Unknown profile variant " + variant);
      }
      for (int c = 0; c < result.length; c++) if (Double.isFinite(fixed[c])) result[c] = fixed[c];
      return result;
   }

   /** Median of the values within a graph distance, in both directions. */
   static double[] median(RiverGraph graph, double[] values, double radius) {
      double[] result = new double[graph.count];
      Ball ball = new Ball(graph.count);
      double[] window = new double[64];
      for (int c = 0; c < graph.count; c++) {
         int n = ball.collect(graph, c, radius, true, true);
         if (window.length < n) window = new double[n * 2];
         for (int i = 0; i < n; i++) window[i] = values[ball.cells[i]];
         Arrays.sort(window, 0, n);
         result[c] = window[n / 2];
      }
      return result;
   }

   /**
    * Morphological closing along the graph (the highest within a distance, then the lowest of those):
    * fills dips narrower than about twice the distance and keeps steps and monotone runs exactly.
    */
   static double[] closing(RiverGraph graph, double[] values, double radius) {
      double[] dilated = new double[graph.count], result = new double[graph.count];
      Ball ball = new Ball(graph.count);
      for (int c = 0; c < graph.count; c++) {
         int n = ball.collect(graph, c, radius, true, true);
         double best = values[c];
         for (int i = 0; i < n; i++) best = Math.max(best, values[ball.cells[i]]);
         dilated[c] = best;
      }
      for (int c = 0; c < graph.count; c++) {
         int n = ball.collect(graph, c, radius, true, true);
         double best = dilated[c];
         for (int i = 0; i < n; i++) best = Math.min(best, dilated[ball.cells[i]]);
         result[c] = best;
      }
      return result;
   }

   /** The lowest value upstream within a distance, each raised by {@code leak} per block of distance. */
   static double[] carve(RiverGraph graph, double[] values, double radius, double leak) {
      double[] result = new double[graph.count];
      Ball ball = new Ball(graph.count);
      for (int c = 0; c < graph.count; c++) {
         int n = ball.collect(graph, c, radius, true, false);
         double best = values[c];
         for (int i = 0; i < n; i++) best = Math.min(best, values[ball.cells[i]] + leak * ball.distance[ball.cells[i]]);
         result[c] = best;
      }
      return result;
   }

   /** The highest value downstream within a distance, each lowered by {@code leak} per block of distance. */
   static double[] fill(RiverGraph graph, double[] values, double radius, double leak) {
      double[] result = new double[graph.count];
      Ball ball = new Ball(graph.count);
      for (int c = 0; c < graph.count; c++) {
         int n = ball.collect(graph, c, radius, false, true);
         double best = values[c];
         for (int i = 0; i < n; i++) best = Math.max(best, values[ball.cells[i]] - leak * ball.distance[ball.cells[i]]);
         result[c] = best;
      }
      return result;
   }

   /** Cells within a graph distance of a start cell (Dijkstra over cell centres). */
   static final class Ball {
      final int[] cells;
      final double[] distance;
      private final int[] heap;
      private int heapSize;
      private final int[] stamp;
      private int epoch;

      Ball(int count) {
         cells = new int[count];
         distance = new double[count];
         heap = new int[count * 4 + 4];
         stamp = new int[count];
      }

      int collect(RiverGraph graph, int start, double radius, boolean upstream, boolean downstream) {
         epoch++;
         int n = 0;
         heapSize = 0;
         distance[start] = 0.0;
         stamp[start] = epoch;
         push(start);
         boolean[] done = null;
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
         if (heapSize == heap.length) return;
         int i = heapSize++;
         heap[i] = cell;
         while (i > 0) {
            int parent = (i - 1) / 2;
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
