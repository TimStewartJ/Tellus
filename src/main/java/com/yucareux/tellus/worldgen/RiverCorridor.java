package com.yucareux.tellus.worldgen;

/**
 * Where a natural waterway runs within a corridor around its mapped line. Map lines and terrain models disagree by
 * tens of metres: a line drawn up a valley side, across a spur or beside the channel the terrain shows. The course is
 * the path through the corridor that keeps to the lowest ground (the mean across its channel), turns gradually, does
 * not cross a rise standing between the line and that ground (a levee, the ridge between two valleys) and keeps a
 * slight preference for the line itself. Dynamic programming over stations along the line finds it. Its only inputs
 * are the line's own geometry, its anchored ends and the terrain, so every area profiling a piece of line draws the
 * same course.
 */
final class RiverCorridor {
   /** Widest corridor either side of the mapped line, blocks. */
   static final int MAX_RADIUS = 64;
   /**
    * Widest corridor of a waterway carrying a narrow river area, blocks: an outline is a firm sign of the river, and
    * where it lies on a valley side the terrain model's floor can be further off than a line's.
    */
   static final int MAX_AREA_RADIUS = 96;
   /** Corridor either side of the mapped line, ground metres: about how far map lines and terrain models disagree. */
   private static final double RADIUS_METERS = 96.0;
   /** Preference for the mapped line: ground a course must gain per block of offset, blocks. */
   private static final double PULL = 0.04;
   /** Cost of turning, per squared block of lateral change between stations. */
   private static final double TURN = 0.1;
   /** Cost per block of a rise crossed between the line and the course, beyond {@link #ROUGHNESS}. */
   private static final double BARRIER = 2.0;
   /** Rises lower than this (blocks) are terrain roughness, not a levee or a ridge. */
   private static final double ROUGHNESS = 0.5;
   /** Cost per block beyond the corridor: only an anchored end lies there. */
   private static final double OUTSIDE = 1.0;
   /** Largest offset on the inner side of a bend, as a fraction of its radius: beyond, the course folds over itself. */
   private static final double FOLD = 0.5;
   private static final double FOLD_COST = 100.0;
   /**
    * Steepest ground (blocks of drop per block across the line) a course descends beside its line. Ground reached down
    * more than {@link #NOTCH} blocks of anything steeper lies below a cliff (the floor below a valley's wall, beyond a
    * plateau's edge): another valley, not where the line was meant to be. A canyon wall the terrain model blurs is
    * still this gentle, and the notch a stream has cut (at a fall's lip, in a gorge) is no deeper than that.
    */
   private static final double DESCENT = 1.0, NOTCH = 8.0;
   /** Cost per block of drop steeper than {@link #DESCENT} beyond the {@link #NOTCH} between the line and the course. */
   private static final double CLIFF = 2.0;
   /**
    * Along-line slope (blocks per block) of the ground under the mapped line beyond which it falls or cascades down a
    * cliff: the course keeps within {@code fallRadius} of the line there (the notch of the fall), so a fall
    * stays where it is mapped.
    */
   private static final double FALL_SLOPE = 1.0;
   /**
    * Distance from a river area, a lake or the sea (beyond the channel's half-width), blocks, within which a course keeps
    * to its mapped line, moved as that water is (a narrow river area moves with its own waterway): the terrain model
    * shows that flat water as the lowest ground around, which would draw the course along the water's edge at the
    * course's own level.
    */
   static final double HOLD = 24.0;
   /** Half-width of the channel the ground is averaged across, at most, blocks. */
   private static final int MAX_WINDOW = 16;

   private RiverCorridor() {
   }

   /**
    * Corridor half-width (blocks) for a channel at a latitude: the distance maps and terrain disagree by, at least
    * twice the channel's half-width, at most {@code widest}, and never so far that the channel and its banks leave the
    * planner's reach around the mapped line ({@link RiverNetwork#PUBLISHED_REACH}).
    */
   static int radius(double channelHalfWidth, double metersPerBlock, int widest) {
      double wanted = Math.max(RADIUS_METERS / metersPerBlock, 2.0 * channelHalfWidth);
      double room = RiverNetwork.PUBLISHED_REACH - channelHalfWidth * 1.15 - RiverChannels.MAX_BLEND - 4.0;
      return (int)Math.max(0.0, Math.min(widest, Math.min(wanted, room)));
   }

   /**
    * Lateral offsets (blocks along {@code nx, nz}) of the course from the mapped line at each of its samples,
    * {@code step} blocks apart, with stations every {@code stride} samples. {@code startOffset} and
    * {@code endOffset} fix the offset of an anchored end; NaN leaves that end free. Samples with a {@code held} offset
    * (null, or NaN: none) keep to it as closely as the course can turn: the mapped line, or where water the course
    * meets has moved to; where the line goes over a cliff the course stays within {@code fallRadius} blocks of it.
    */
   static double[] offsets(double[] px, double[] pz, double[] nx, double[] nz, double step, int stride, int radius,
      double halfWidth, RiverChannels.Terrain terrain, double startOffset, double endOffset, double[] held, int fallRadius) {
      int samples = px.length;
      int strides = (samples - 1 + stride - 1) / stride + 1;
      int[] at = new int[strides];
      for (int i = 0; i < strides; i++) at[i] = Math.min(samples - 1, i * stride);
      boolean fixedStart = !Double.isNaN(startOffset), fixedEnd = !Double.isNaN(endOffset);
      int width = radius;
      if (fixedStart) width = Math.max(width, (int)Math.ceil(Math.abs(startOffset)));
      if (fixedEnd) width = Math.max(width, (int)Math.ceil(Math.abs(endOffset)));
      if (held != null) for (int i = 0; i < strides; i++) if (!Double.isNaN(held[at[i]])) width = Math.max(width, (int)Math.ceil(Math.abs(held[at[i]])));
      width = Math.min(width, 4 * MAX_RADIUS);
      int states = 2 * width + 1;
      Lattice lattice = new Lattice(width, window(halfWidth), spacing(terrain, px[samples / 2], pz[samples / 2]));
      // Lateral change allowed between stations: half a block per block along the line, more on a short piece whose
      // anchored ends lie further apart than that allows.
      int[] reach = new int[strides];
      long capacity = 0;
      for (int i = 1; i < strides; i++) { reach[i] = Math.max(1, (int)Math.round(0.5 * (at[i] - at[i - 1]) * step)); capacity += reach[i]; }
      if (fixedStart && fixedEnd) {
         double needed = Math.abs(Math.round(endOffset) - Math.round(startOffset));
         if (needed > capacity) {
            int factor = (int)Math.ceil(needed / Math.max(1.0, capacity));
            for (int i = 1; i < strides; i++) reach[i] *= factor;
         }
      }
      // Where the ground under the line falls like a cliff, the course keeps to the line.
      double[] line = new double[strides];
      for (int i = 0; i < strides; i++) line[i] = Lattice.height(terrain, px[at[i]], pz[at[i]], 0.0, 0.0, 0);
      // The corridor of each station: its half-width and its middle (the mapped line, or the offset a held station keeps).
      int[] walls = new int[strides];
      double[] middles = new double[strides];
      for (int i = 0; i < strides; i++) {
         int a = Math.max(0, i - 2), b = Math.min(strides - 1, i + 2);
         double run = (at[b] - at[a]) * step;
         boolean falls = run > 1.0E-6 && Math.abs(line[a] - line[b]) / run > FALL_SLOPE;
         double target = held == null ? Double.NaN : held[at[i]];
         if (!Double.isNaN(target)) middles[i] = target;
         walls[i] = !Double.isNaN(target) ? 0 : falls ? Math.min(radius, fallRadius) : radius;
      }
      double[] cost = new double[states], next = new double[states], node = new double[states];
      short[] back = new short[strides * states];
      lattice.costs(px[at[0]], pz[at[0]], nx[at[0]], nz[at[0]], walls[0], middles[0], curvature(nx, nz, at, 0, step), terrain, node);
      for (int o = 0; o < states; o++) cost[o] = node[o];
      if (fixedStart) restrict(cost, (int)Math.round(startOffset) + width);
      for (int i = 1; i < strides; i++) {
         lattice.costs(px[at[i]], pz[at[i]], nx[at[i]], nz[at[i]], walls[i], middles[i], curvature(nx, nz, at, i, step), terrain, node);
         int r = reach[i];
         for (int o = 0; o < states; o++) {
            double best = Double.POSITIVE_INFINITY;
            int chosen = 0;
            for (int d = -r; d <= r; d++) {
               int from = o - d;
               if (from < 0 || from >= states || cost[from] == Double.POSITIVE_INFINITY) continue;
               double c = cost[from] + TURN * d * d;
               if (c < best) { best = c; chosen = d; }
            }
            next[o] = best + node[o];
            back[i * states + o] = (short)chosen;
         }
         double[] swap = cost; cost = next; next = swap;
      }
      if (fixedEnd) restrict(cost, (int)Math.round(endOffset) + width);
      int state = 0;
      for (int o = 1; o < states; o++) {
         if (cost[o] < cost[state] || cost[o] == cost[state] && Math.abs(o - width) < Math.abs(state - width)) state = o;
      }
      if (cost[state] == Double.POSITIVE_INFINITY) return null;
      int[] path = new int[strides];
      for (int i = strides - 1; i >= 0; i--) {
         path[i] = state - width;
         if (i > 0) state -= back[i * states + state];
      }
      // The path keeps to whole blocks; a short running mean makes it a smooth course.
      double[] smooth = new double[strides];
      for (int i = 0; i < strides; i++) {
         double sum = 0.0;
         int n = 0;
         for (int j = Math.max(0, i - 2); j <= Math.min(strides - 1, i + 2); j++) { sum += path[j]; n++; }
         smooth[i] = sum / n;
      }
      if (fixedStart) smooth[0] = startOffset;
      if (fixedEnd) smooth[strides - 1] = endOffset;
      double[] offset = new double[samples];
      for (int k = 0; k < samples; k++) {
         int i = Math.min(strides - 2, k / stride);
         double t = (k - at[i]) / (double)(at[i + 1] - at[i]);
         offset[k] = smooth[i] + (smooth[i + 1] - smooth[i]) * Math.max(0.0, Math.min(1.0, t));
      }
      return offset;
   }

   /**
    * The lateral offset (blocks along {@code nx, nz}) of the ground a course through a meeting point of pieces of line
    * would choose there: the lowest mean ground across a channel of {@code halfWidth} within {@code radius}, averaged
    * over a few blocks along the course ({@code tx, tz}), with a course's preference for the mapped position and its
    * refusal to cross a rise.
    */
   static double anchor(double x, double z, double tx, double tz, int radius, double halfWidth, RiverChannels.Terrain terrain) {
      if (radius <= 0) return 0.0;
      double nx = -tz, nz = tx;
      int states = 2 * radius + 1;
      Lattice lattice = new Lattice(radius, window(halfWidth), spacing(terrain, x, z));
      double[] total = new double[states], node = new double[states];
      for (int along = -1; along <= 1; along++) {
         lattice.costs(x + tx * along * 4.0, z + tz * along * 4.0, nx, nz, radius, 0.0, 0.0, terrain, node);
         for (int o = 0; o < states; o++) total[o] += node[o];
      }
      int best = radius;
      for (int o = 0; o < states; o++) {
         if (total[o] < total[best] || total[o] == total[best] && Math.abs(o - radius) < Math.abs(best - radius)) best = o;
      }
      return best - radius;
   }

   /** Every state but one made unreachable. */
   private static void restrict(double[] cost, int keep) {
      int k = Math.max(0, Math.min(cost.length - 1, keep));
      double kept = cost[k];
      java.util.Arrays.fill(cost, Double.POSITIVE_INFINITY);
      cost[k] = kept;
   }

   private static int window(double halfWidth) {
      return (int)Math.round(Math.max(0.0, Math.min(MAX_WINDOW, halfWidth)));
   }

   /** Spacing of the terrain read across the line: about half a cell of the terrain model, one to four blocks. */
   private static int spacing(RiverChannels.Terrain terrain, double x, double z) {
      double resolution = terrain.resolution((int)Math.floor(x), (int)Math.floor(z));
      return (int)Math.max(1.0, Math.min(4.0, Math.floor(resolution * 0.5)));
   }

   /** Signed curvature of the line at station {@code i} (positive where it turns towards {@code +n}), per block. */
   private static double curvature(double[] nx, double[] nz, int[] at, int i, double step) {
      int a = at[Math.max(0, i - 1)], b = at[Math.min(at.length - 1, i + 1)];
      double length = (b - a) * step;
      if (length < 1.0E-6) return 0.0;
      double cross = nx[a] * nz[b] - nz[a] * nx[b], dot = nx[a] * nx[b] + nz[a] * nz[b];
      return Math.atan2(cross, dot) / length;
   }

   /** The ground across the line at a station, and the cost of each offset there. */
   private static final class Lattice {
      private final int width, window, spacing, extent;
      private final double[] ground, prefix, steep;

      Lattice(int width, int window, int spacing) {
         this.width = width; this.window = window; this.spacing = spacing;
         this.extent = width + window;
         this.ground = new double[2 * extent + 1];
         this.prefix = new double[2 * extent + 2];
         this.steep = new double[2 * extent + 1];
      }

      /**
       * Cost of each offset {@code -width..width} (index {@code o + width}) at a station: the mean ground across the
       * channel, the preference for the line, the rise crossed to get there, ground down a cliff from the line, and the
       * walls of the corridor ({@code radius} either side of {@code middle}) and of a bend.
       */
      void costs(double x, double z, double nx, double nz, int radius, double middle, double curvature, RiverChannels.Terrain terrain, double[] out) {
         // Terrain every few blocks across the line, linear in between.
         int last = -extent;
         double previous = height(terrain, x, z, nx, nz, last);
         ground[0] = previous;
         for (int j = -extent + 1; j <= extent; j++) {
            if (j != extent && (j + extent) % spacing != 0) continue;
            double h = height(terrain, x, z, nx, nz, j);
            for (int k = last + 1; k <= j; k++) ground[k + extent] = previous + (h - previous) * (k - last) / (double)(j - last);
            last = j; previous = h;
         }
         for (int k = 0; k < ground.length; k++) prefix[k + 1] = prefix[k] + ground[k];
         // Outwards from the line, the drop steeper than a descent passed to reach each position.
         steep[extent] = 0.0;
         for (int j = 1; j <= extent; j++) {
            steep[extent + j] = steep[extent + j - 1] + Math.max(0.0, ground[extent + j - 1] - ground[extent + j] - DESCENT);
            steep[extent - j] = steep[extent - j + 1] + Math.max(0.0, ground[extent - j + 1] - ground[extent - j] - DESCENT);
         }
         double centre = ground[extent];
         double highest = centre;
         for (int o = 0; o <= width; o++) {
            highest = Math.max(highest, ground[o + extent]);
            out[width + o] = node(o, highest, centre, radius, middle, curvature);
         }
         highest = centre;
         for (int o = 0; o >= -width; o--) {
            highest = Math.max(highest, ground[o + extent]);
            out[width + o] = node(o, highest, centre, radius, middle, curvature);
         }
      }

      private double node(int o, double highest, double centre, int radius, double middle, double curvature) {
         double mean = (prefix[o + window + extent + 1] - prefix[o - window + extent]) / (2 * window + 1);
         double rise = highest - Math.max(centre, ground[o + extent]) - ROUGHNESS;
         // A channel reaching over a cliff at either edge would take the ground below it.
         double cliff = Math.max(steep[o - window + extent], steep[o + window + extent]) - NOTCH;
         double cost = mean + PULL * Math.abs(o) + CLIFF * Math.max(0.0, cliff);
         if (rise > 0.0) cost += BARRIER * rise;
         if (Math.abs(o - middle) > radius) cost += OUTSIDE * (Math.abs(o - middle) - radius);
         double fold = o * curvature - FOLD;
         if (fold > 0.0) cost += FOLD_COST * fold;
         return Double.isFinite(cost) ? cost : 1.0E9;
      }

      private static double height(RiverChannels.Terrain terrain, double x, double z, double nx, double nz, int offset) {
         double h = terrain.height((int)Math.floor(x + nx * offset), (int)Math.floor(z + nz * offset));
         return Double.isFinite(h) ? h : 1.0E6;
      }
   }
}
