package com.yucareux.tellus.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawn river water and banks of one area, read from a solved {@link RiverNetwork} tile.
 *
 * <p>Waterways run along the courses of their {@link RiverRun}s at their levels, which never rise downstream and
 * every area draws the same way. A mapped river area is the water itself, at the level the terrain gives it
 * ({@link AreaSurface}): one smooth surface from bank to bank, sloping with the river, with falls where the terrain
 * drops, a narrow one moved with its waterway's course. Lines inside river areas only draw narrow channels where they
 * leave them. Where the surface steps down a
 * block, the last blocks before the step flow (a level lower each block, as vanilla water spreads), on channels and
 * areas alike, so the step is a slope in the water rather than a ledge.</p>
 *
 * <p>Banks keep the natural terrain's shape: the terrain beside the water is moved by the difference between the
 * ground at the water's edge and a lip level with the water's surface, fading out over a distance that grows with
 * that difference and with the river. The ground meets the water at its surface and rises from there; a canyon
 * wall keeps its slope instead of being cut back to a ramp, a floodplain stays a floodplain, and ground lower than
 * the water is raised only to hold it. Islands in a river area are shaped the same way.</p>
 */
final class RiverChannels {
   static final int TILE = 512;
   private static final int BIN = 32;
   /** Widest bank blend, blocks. */
   static final double MAX_BLEND = 40.0;
   /** Largest raise of ground beside a channel to hold its water. */
   private static final double MAX_RAISE = 4.0;

   interface Terrain {
      double height(int x, int z);

      /** Size of the elevation model's cells at a position, blocks. */
      default double resolution(int x, int z) {
         return 1.0;
      }
   }

   final int tx, tz, minX, minZ;
   // Side of this plan's area, how far banks reach, and the narrowest drawn channel.
   private final int tileSize;
   private final double blend, minHalfWidth;
   private final Segment[] segments;
   private final int binMinX, binMinZ, binColumns, binRows;
   private final Segment[][] bins;
   private final RiverScale scale;
   /** River area levels from the terrain (for coarse LODs, from the terrain at their resolution). */
   private final AreaSurface surface;

   /** A plan for blocks and near LODs: native terrain, banks. */
   RiverChannels(int tx, int tz, RiverNetwork.Tile tile, RiverScale scale, Terrain terrain) {
      this(tx, tz, TILE, true, RiverScale.MIN_HALF_WIDTH, tile, terrain);
   }

   /**
    * A plan of {@code tileSize} blocks. Coarse LODs pass no terrain (levels come from the network alone), no banks,
    * and a narrowest channel of half their cell, so a waterway they keep stays one unbroken cell wide.
    */
   RiverChannels(int tx, int tz, int tileSize, boolean banks, double minHalfWidth, RiverNetwork.Tile tile, Terrain terrain) {
      this.tx = tx; this.tz = tz;
      this.tileSize = tileSize; this.minHalfWidth = minHalfWidth;
      this.blend = banks ? MAX_BLEND : 0.0;
      this.minX = tx * tileSize; this.minZ = tz * tileSize;
      this.scale = tile.scale;
      List<Segment> segments = new ArrayList<>();
      double maxGrow = 0.0;
      for (RiverNetwork.Line line : tile.lines) {
         HydrologyWaterMap.Shape shape = line.shape();
         double grow = reach(line);
         // A course may run a corridor away from its mapped line (a narrow river area's with the area).
         double shapeGrow = grow + RiverNetwork.PieceEnds.shift(line);
         if (shape.maxX < minX - shapeGrow || shape.minX > minX + tileSize + shapeGrow || shape.maxZ < minZ - shapeGrow
            || shape.minZ > minZ + tileSize + shapeGrow) continue;
         for (RiverRun run : tile.runs(line, terrain)) {
            if (run.maxX < minX - grow || run.minX > minX + tileSize + grow || run.maxZ < minZ - grow || run.minZ > minZ + tileSize + grow) continue;
            for (int i = 0; i + 1 < run.xs.length; i++) {
               double sx0 = Math.min(run.xs[i], run.xs[i + 1]) - grow, sx1 = Math.max(run.xs[i], run.xs[i + 1]) + grow;
               double sz0 = Math.min(run.zs[i], run.zs[i + 1]) - grow, sz1 = Math.max(run.zs[i], run.zs[i + 1]) + grow;
               if (sx1 < minX || sx0 > minX + tileSize || sz1 < minZ || sz0 > minZ + tileSize) continue;
               segments.add(new Segment(run, i, segments.size()));
               maxGrow = Math.max(maxGrow, grow);
            }
         }
      }
      this.segments = segments.toArray(new Segment[0]);
      double areaMinX = minX - maxGrow, areaMinZ = minZ - maxGrow, areaMaxX = minX + tileSize + maxGrow, areaMaxZ = minZ + tileSize + maxGrow;
      int bin = binSize();
      this.binMinX = Math.floorDiv((int)Math.floor(areaMinX), bin); this.binMinZ = Math.floorDiv((int)Math.floor(areaMinZ), bin);
      this.binColumns = Math.floorDiv((int)Math.ceil(areaMaxX), bin) - binMinX + 1;
      this.binRows = Math.floorDiv((int)Math.ceil(areaMaxZ), bin) - binMinZ + 1;
      List<List<Segment>> lists = new ArrayList<>(binColumns * binRows);
      for (int i = 0; i < binColumns * binRows; i++) lists.add(null);
      for (Segment segment : this.segments) {
         double grow = reach(segment.run.line);
         int x0 = Math.max(binMinX, (int)Math.floor((Math.min(segment.ax, segment.bx) - grow) / bin));
         int x1 = Math.min(binMinX + binColumns - 1, (int)Math.floor((Math.max(segment.ax, segment.bx) + grow) / bin));
         int z0 = Math.max(binMinZ, (int)Math.floor((Math.min(segment.az, segment.bz) - grow) / bin));
         int z1 = Math.min(binMinZ + binRows - 1, (int)Math.floor((Math.max(segment.az, segment.bz) + grow) / bin));
         for (int bz = z0; bz <= z1; bz++) for (int bx = x0; bx <= x1; bx++) {
            int index = (bz - binMinZ) * binColumns + bx - binMinX;
            List<Segment> list = lists.get(index);
            if (list == null) lists.set(index, list = new ArrayList<>(4));
            list.add(segment);
         }
      }
      this.bins = new Segment[binColumns * binRows][];
      for (int i = 0; i < bins.length; i++) if (lists.get(i) != null) bins[i] = lists.get(i).toArray(new Segment[0]);
      this.surface = tile.areaSurface(tx, tz, tileSize, terrain);
   }

   private int binSize() {
      return Math.max(BIN, Integer.highestOneBit(Math.max(1, tileSize / 16)));
   }

   /** How far beside a line its channel or banks reach. */
   private double reach(RiverNetwork.Line line) {
      return Math.max(minHalfWidth, line.channelHalfWidth()) * 1.15 + blend + 2.0;
   }

   /** How far beyond a plan its river area levels are read: the banks of the water just outside it. */
   static int areaReach() {
      return (int)Math.ceil(MAX_BLEND) + 2 + AreaSurface.GRID;
   }

   /** Median terrain of nine points around a position, so a bridge, a bar or a tree on the water does not count. */
   static double localSurface(Terrain terrain, double x, double z) {
      double[] heights = new double[9];
      int n = 0;
      for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
         double h = terrain.height((int)Math.floor(x) + dx * 6, (int)Math.floor(z) + dz * 6);
         if (Double.isFinite(h)) heights[n++] = h;
      }
      if (n == 0) return Double.NEGATIVE_INFINITY;
      java.util.Arrays.sort(heights, 0, n);
      return heights[n / 2];
   }

   /** The river area level at a position for a column with natural ground {@code raw}, as {level, slope}; level NaN when none. */
   private double[] areaLevel(double x, double z, int raw, Terrain terrain) {
      // Where the grid points around stand in pools above and below a fall, a pit or a spike in the ground (a DEM
      // void, a bridge) must not pick the other pool: the choice reads the median ground.
      return surface.choose(x, z, raw, () -> {
         if (terrain == null) return Double.NaN;
         double median = localSurface(terrain, x, z);
         return Double.isFinite(median) ? Math.ceil(median) : raw;
      });
   }

   boolean empty() {
      return segments.length == 0 && (surface == null || surface.empty());
   }

   /** Whether this plan levels a mapped water body (any fragment of it) as a river area. */
   boolean drawsArea(HydrologyWaterMap.Shape body) {
      return surface != null && surface.levels(body.identity());
   }

   /** Points of the channels and river areas inside a coarse cell, where a footprint should look for water. */
   void footprintPoints(int x, int z, int minX, int minZ, int maxX, int maxZ, List<int[]> points) {
      int bin = binSize();
      int bx0 = Math.max(binMinX, Math.floorDiv(minX, bin)), bx1 = Math.min(binMinX + binColumns - 1, Math.floorDiv(maxX, bin));
      int bz0 = Math.max(binMinZ, Math.floorDiv(minZ, bin)), bz1 = Math.min(binMinZ + binRows - 1, Math.floorDiv(maxZ, bin));
      it.unimi.dsi.fastutil.ints.IntOpenHashSet taken = null;
      for (int bz = bz0; bz <= bz1; bz++) for (int bx = bx0; bx <= bx1; bx++) {
         Segment[] list = bins[(bz - binMinZ) * binColumns + bx - binMinX];
         if (list == null) continue;
         if (taken == null) taken = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
         for (Segment segment : list) {
            if (!taken.add(segment.id)) continue;
            int[] point = InlandHydrologyPlanner.clippedPoint(segment.ax, segment.az, segment.bx, segment.bz, x, z, minX, minZ, maxX, maxZ);
            if (point != null) points.add(point);
            RiverRun run = segment.run;
            // A thin curtain can lie between coarse sample centres; its lip represents the cell (the curtain stands at
            // the lip or a block below it, where the terrain drops).
            for (int f = 0; f < run.fallIndex.length; f++) {
               for (int offset = 0; offset <= 1; offset++) {
                  double[] lip = run.position(run.lipS(f) + offset);
                  if (lip[0] >= minX && lip[0] <= maxX + 1 && lip[1] >= minZ && lip[1] <= maxZ + 1)
                     points.add(new int[]{(int)Math.floor(lip[0]), (int)Math.floor(lip[1])});
               }
            }
         }
      }
      if (surface != null) surface.footprintPoints(x, z, minX, minZ, maxX, maxZ, points);
   }

   /**
    * Diagnostics: the waterway nearest the midpoint of two columns and the positions of both along it, as
    * {feature id, arc of the first, arc of the second, distance from the course}; null when none is near.
    */
   double[] trace(int ax, int az, int bx, int bz) {
      double mx = (ax + bx) * 0.5, mz = (az + bz) * 0.5;
      Segment[] nearby = bin((int)Math.floor(mx), (int)Math.floor(mz));
      if (nearby == null) return null;
      Segment best = null;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (Segment segment : nearby) {
         if (segment.length < 1.0E-9) continue;
         double t = Math.max(0.0, Math.min(1.0, ((mx - segment.ax) * segment.dx + (mz - segment.az) * segment.dz) / (segment.length * segment.length)));
         double distance = Math.hypot(mx - segment.ax - segment.dx * t, mz - segment.az - segment.dz * t);
         if (distance < bestDistance) { bestDistance = distance; best = segment; }
      }
      if (best == null) return null;
      RiverRun run = best.run;
      double span = run.arc[best.i + 1] - run.arc[best.i], squared = best.length * best.length;
      double ta = ((ax - best.ax) * best.dx + (az - best.az) * best.dz) / squared;
      double tb = ((bx - best.ax) * best.dx + (bz - best.az) * best.dz) / squared;
      return new double[]{run.line.feature().featureId(), run.arc[best.i] + span * ta, run.arc[best.i] + span * tb, bestDistance};
   }

   /**
    * Diagnostics: the waterway nearest a column and its water block every two blocks along it, {@code span} blocks
    * either side, as "arc:waterY[d]" (d where it runs dry).
    */
   String profile(int x, int z, double span) {
      Segment[] nearby = bin(x, z);
      if (nearby == null) return "none";
      Segment best = null;
      double bestDistance = Double.POSITIVE_INFINITY, bestS = 0.0;
      for (Segment segment : nearby) {
         if (segment.length < 1.0E-9) continue;
         double t = Math.max(0.0, Math.min(1.0, ((x - segment.ax) * segment.dx + (z - segment.az) * segment.dz) / (segment.length * segment.length)));
         double distance = Math.hypot(x - segment.ax - segment.dx * t, z - segment.az - segment.dz * t);
         if (distance < bestDistance) {
            bestDistance = distance; best = segment;
            bestS = segment.run.arc[segment.i] + (segment.run.arc[segment.i + 1] - segment.run.arc[segment.i]) * t;
         }
      }
      if (best == null) return "none";
      StringBuilder out = new StringBuilder("feature=" + best.run.line.feature().featureId() + " inArea=" + best.run.line.inRiverArea()
         + " distance=" + Math.round(bestDistance) + " s=" + Math.round(bestS) + " length=" + Math.round(best.run.length())
         + (surface == null ? "" : " areaInside=" + surface.inside(x, z) + " areaIsland=" + surface.island(x, z)
            + " areaLevel=" + surface.level(x, z, Double.NaN)) + ":");
      for (double s = Math.max(0.0, bestS - span); s <= Math.min(best.run.length(), bestS + span); s += 2.0)
         out.append(' ').append(Math.round(s)).append(':').append(best.run.waterY(s)).append(best.run.dry(s) ? "d" : "");
      return out.toString();
   }

   /**
    * Diagnostics: every drawn course segment, as {ax, az, bx, bz, feature id, water block at a, water block at b,
    * channel half-width, arc at a, arc at b, distance of a from the mapped line (at most 256)}.
    */
   List<double[]> courses() {
      List<double[]> out = new ArrayList<>(segments.length);
      for (Segment segment : segments) {
         RiverRun run = segment.run;
         out.add(new double[]{segment.ax, segment.az, segment.bx, segment.bz, run.line.feature().featureId(),
            run.waterY(run.arc[segment.i]), run.waterY(run.arc[segment.i + 1]), Math.max(minHalfWidth, run.line.channelHalfWidth()),
            run.arc[segment.i], run.arc[segment.i + 1], Math.min(256.0, run.line.shape().distance(segment.ax, segment.az, 256.0))});
      }
      return out;
   }

   private Segment[] bin(int x, int z) {
      int bin = binSize();
      int bx = Math.floorDiv(x, bin) - binMinX, bz = Math.floorDiv(z, bin) - binMinZ;
      if (bx < 0 || bz < 0 || bx >= binColumns || bz >= binRows) return null;
      return bins[bz * binColumns + bx];
   }

   /**
    * The river column at a block, or null when no channel, river area or bank reaches it. {@code raw} is the
    * natural terrain surface of the column (the top solid block). Without banks only wet columns are returned.
    */
   InlandHydrologyPlanner.Column sample(int x, int z, int raw, Terrain terrain) {
      // River areas: wet inside (islands excepted), at the area's level.
      boolean inArea = false, island = false;
      if (surface != null && !surface.empty()) {
         inArea = surface.inside(x, z);
         if (!inArea) island = surface.island(x, z);
      }
      if (inArea) {
         double[] choice = areaLevel(x, z, raw, terrain);
         if (Double.isFinite(choice[0])) return wetArea(choice[0], choice[1], choice[2] > 0.0, x, z, raw, terrain);
      }
      Segment[] nearby = bin(x, z);
      Hit best = null;
      Hit[] hits = null;
      int hitCount = 0;
      if (nearby != null) {
         hits = new Hit[nearby.length];
         for (Segment segment : nearby) {
            Hit hit = segment.hit(x, z, minHalfWidth, blend);
            if (hit == null) continue;
            hits[hitCount++] = hit;
            if (hit.distance <= hit.halfWidth) {
               double score = hit.distance / hit.halfWidth;
               if (best == null || score < best.distance / best.halfWidth - 1.0E-9
                  || Math.abs(score - best.distance / best.halfWidth) <= 1.0E-9 && hit.waterY < best.waterY) best = hit;
            }
         }
      }
      if (best != null && !island) return wetLine(best, x, z, raw);
      if (blend <= 0.0 || terrain == null) return null;
      double lowest = Double.POSITIVE_INFINITY, highest = Double.NEGATIVE_INFINITY;
      // One waterway's segments blend, the nearest by far the strongest: on a steep course a lower segment's bank,
      // reaching up beside the water above it, would otherwise cut the ground there below that water.
      boolean[] blended = new boolean[hitCount];
      for (int i = 0; i < hitCount; i++) {
         if (blended[i]) continue;
         double sum = 0.0, weight = 0.0;
         for (int j = i; j < hitCount; j++) {
            if (hits[j].run != hits[i].run) continue;
            blended[j] = true;
            double w = 1.0 / Math.pow(hits[j].distance + 1.0, 4.0);
            sum += bank(hits[j], x, z, raw, terrain) * w;
            weight += w;
         }
         double target = sum / weight;
         if (target < raw) lowest = Math.min(lowest, target);
         else if (target > raw) highest = Math.max(highest, target);
      }
      if (surface != null && !surface.empty()) {
         double[] shore = surface.nearestShore(x, z, blend);
         if (shore != null) {
            double distance = Math.hypot(x - shore[0], z - shore[1]);
            double edge = Math.ceil(edgeHeight(terrain, shore[0], shore[1]));
            double level = areaLevel(shore[0], shore[1], (int)edge, terrain)[0];
            if (Double.isFinite(level)) {
               double target = displaced(raw, edge, (int)Math.ceil(level - 1.0E-6) - 1, distance, Math.min(surface.halfWidth(shore[0], shore[1]), 16.0), x, z);
               if (target < raw) lowest = Math.min(lowest, target);
               else if (target > raw) highest = Math.max(highest, target);
            }
         }
      }
      // Lowering towards any water wins: a raise for one channel must not dam another.
      double target = lowest != Double.POSITIVE_INFINITY ? lowest : highest != Double.NEGATIVE_INFINITY ? highest : raw;
      int terrainY = (int)Math.round(target);
      if (terrainY != raw) return new InlandHydrologyPlanner.Column(true, false, true, terrainY, terrainY, 0);
      return hitCount > 0 || surface != null && !surface.empty() ? InlandHydrologyPlanner.Column.dry(raw) : null;
   }

   private InlandHydrologyPlanner.Column wetLine(Hit hit, int x, int z, int raw) {
      int water = hit.waterY;
      if (hit.falling) {
         // The curtain reaches the water below it, standing on the column's own ground: no notch is cut into the lip.
         int bed = Math.min(raw - 1, Math.max(hit.lowerY - 1, raw - 2));
         return new InlandHydrologyPlanner.Column(true, true, false, bed, water, 8, true);
      }
      double cross = Math.max(0.0, 1.0 - hit.distance / hit.halfWidth);
      double noise = InlandWaterProfile.noise(x, z, 37, 0x5D2A7F11L);
      double shape = Math.pow(cross, 0.55) * (0.85 + 0.25 * noise);
      int depth = (int)Math.max(1, Math.round(1.0 + (hit.depth - 1) * shape));
      int bed = water - depth;
      // Ground falling away far below the bed (the channel's edge over a cliff, at the lip of a fall) takes the water
      // down to it as a curtain rather than on a bed raised over the drop.
      if (raw < bed - 4) return new InlandHydrologyPlanner.Column(true, true, false, raw - 1, water, 8, true);
      // A natural hollow in the terrain below the shaped bed stays, within reason.
      if (raw < bed) bed = Math.max(raw, bed - 4);
      return new InlandHydrologyPlanner.Column(true, true, false, Math.min(bed, water - 1), water, hit.flowLevel);
   }

   private InlandHydrologyPlanner.Column wetArea(double level, double slope, boolean fall, int x, int z, int raw, Terrain terrain) {
      int water = (int)Math.ceil(level - 1.0E-6) - 1;
      int lower = Integer.MAX_VALUE;
      if (terrain != null) {
         // Where the water steps down two or more blocks (a cliff on a fall) the lower column is a curtain up to the
         // water above it, standing on its own bed.
         int upper = Integer.MIN_VALUE;
         for (int d = 0; d < 8; d += 2) {
            int nx = x + InlandHydrologyPlanner.DX[d], nz = z + InlandHydrologyPlanner.DZ[d];
            if (!surface.inside(nx, nz)) continue;
            double neighbor = areaLevel(nx, nz, (int)Math.ceil(terrain.height(nx, nz)), terrain)[0];
            if (!Double.isFinite(neighbor)) continue;
            int top = (int)Math.ceil(neighbor - 1.0E-6) - 1;
            upper = Math.max(upper, top);
            lower = Math.min(lower, top);
         }
         if (upper >= water + 2) return new InlandHydrologyPlanner.Column(true, true, false, Math.min(water - 1, raw - 1), upper, 8, true);
      }
      double shore = surface.shoreDistance(x, z, 64.0);
      double halfWidth = Math.max(1.0, surface.halfWidth(x, z));
      double noise = InlandWaterProfile.noise(x, z, 53, 0x2B91E3A5L);
      // A wide river is as deep as its whole width makes it, beyond the shores measured nearby.
      double width = Math.max(halfWidth * 2.0, surface.areaWidth(x, z));
      int maxDepth = scale.depth(width * scale.metersPerBlock(z), width * 0.5, z);
      double ramp = Math.min(1.0, shore / Math.max(3.0, Math.min(halfWidth, 64.0) * 0.6));
      int depth = (int)Math.max(1, Math.round(1.0 + (maxDepth - 1) * Math.pow(ramp, 0.7) * (0.85 + 0.25 * noise)));
      // Over a fall the water runs shallow, deepening into the pools above and below it.
      depth = (int)Math.max(1, Math.round(depth - (depth - 1) * surface.shallow(x, z)));
      int bed = water - depth;
      if (raw < bed) bed = Math.max(raw, bed - 4);
      // As on a channel, the last blocks before the surface steps down flow there, a level lower each block
      // (vanilla's spread), so the step is a slope rather than a ledge across the river.
      int flow = 0;
      if (slope > 1.0E-4) {
         double ahead = (level - water) / slope;
         if (ahead <= 7.0) flow = 8 - Math.max(1, (int)Math.ceil(ahead - 1.0E-6));
      }
      // On a fall, whose water follows the terrain rather than the levels around, the last block before each step flows.
      if (fall && lower < water) flow = 7;
      return new InlandHydrologyPlanner.Column(true, true, false, Math.min(bed, water - 1), water, flow);
   }

   /** The bank height a channel wants at a dry column (the column's own height when out of reach). */
   private static double bank(Hit hit, int x, int z, int raw, Terrain terrain) {
      // A column within the channel (on an island it crosses) counts as at its edge.
      double edgeDistance = Math.max(0.0, hit.distance - hit.halfWidth);
      if (edgeDistance > MAX_BLEND) return raw;
      // The ground at the water's edge on this column's side.
      double ex = hit.px + hit.sideX * hit.halfWidth, ez = hit.pz + hit.sideZ * hit.halfWidth;
      double edge = Math.ceil(edgeHeight(terrain, ex, ez));
      // The smoothed level beside a fall shapes the cut banks; ground lower than the water is raised only as far
      // as the water right here needs (at the foot of a fall the smoothed level would build walls).
      int lip = (int)Math.ceil(hit.run.bankLevel(hit.s) - 1.0E-6) - 1;
      if (edge < lip) lip = Math.min(lip, hit.falling ? hit.lowerY : hit.waterY);
      return displaced(raw, edge, lip, edgeDistance, hit.halfWidth, x, z);
   }

   /**
    * The ground at a water's edge, averaged over a few blocks: rough native terrain (rock, lidar detail) would
    * otherwise speckle every displaced bank with its own block-scale noise.
    */
   static double edgeHeight(Terrain terrain, double x, double z) {
      int cx = (int)Math.floor(x), cz = (int)Math.floor(z);
      return (terrain.height(cx, cz) + terrain.height(cx + 2, cz) + terrain.height(cx - 2, cz)
         + terrain.height(cx, cz + 2) + terrain.height(cx, cz - 2)) / 5.0;
   }

   /**
    * Natural terrain {@code raw} at {@code distance} from the water's edge, moved by the excess of the edge's
    * ground over the lip, fading smoothly to zero. The fade grows with the excess, so no slope steepens much.
    */
   static double displaced(int raw, double edge, int lip, double distance, double halfWidth, int x, int z) {
      double excess = edge - lip;
      if (excess > -0.5 && excess < 0.5) return raw;
      if (excess < 0.0) excess = Math.max(-MAX_RAISE, excess);
      double noise = InlandWaterProfile.noise(x, z, 41, 0x3C6EF372L);
      double blend = Math.min(MAX_BLEND, 2.0 + Math.abs(excess) * (0.9 + 0.2 * noise) + halfWidth * 0.5);
      if (excess < 0.0) blend = Math.min(blend, 2.0 + Math.abs(excess) * 1.5);
      if (distance >= blend) return raw;
      double t = 1.0 - distance / blend;
      double fade = t * t * (3.0 - 2.0 * t);
      double target = raw - excess * fade;
      // Never cut below the lip at the water, never raise above it; ground already beyond the lip (a hollow
      // beside a lowered bank, a rise beside a raised one) stays as it is.
      return excess > 0.0 ? Math.max(Math.min(raw, lip), target) : Math.min(Math.max(raw, lip), target);
   }

   /** The result of projecting a column on a segment. */
   private static final class Hit {
      double distance, halfWidth, px, pz, sideX, sideZ, depth;
      int waterY, lowerY, flowLevel;
      double s;
      boolean falling;
      RiverRun run;
   }

   /** One segment of a run. */
   private static final class Segment {
      final RiverRun run;
      final int i, id;
      final double ax, az, bx, bz, dx, dz, length;

      Segment(RiverRun run, int i, int id) {
         this.run = run; this.i = i; this.id = id;
         this.ax = run.xs[i]; this.az = run.zs[i]; this.bx = run.xs[i + 1]; this.bz = run.zs[i + 1];
         this.dx = bx - ax; this.dz = bz - az; this.length = Math.sqrt(dx * dx + dz * dz);
      }

      Hit hit(int x, int z, double minHalfWidth, double blend) {
         if (length < 1.0E-9) return null;
         RiverNetwork.Line line = run.line;
         double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / (length * length)));
         double px = ax + dx * t, pz = az + dz * t;
         double ex = x - px, ez = z - pz;
         double distance = Math.sqrt(ex * ex + ez * ez);
         double halfWidth = line.channelHalfWidth();
         if (!line.engineered()) {
            // Natural channels vary a little in width along their course.
            halfWidth *= 0.92 + 0.16 * (0.5 + 0.5 * InlandWaterProfile.noise((long)Math.floor(px), (long)Math.floor(pz), 97, 0x6A09E667L));
         }
         halfWidth = Math.max(minHalfWidth, halfWidth);
         if (distance > halfWidth + blend + 1.0) return null;
         Hit hit = new Hit();
         hit.run = run;
         hit.distance = distance;
         hit.halfWidth = halfWidth;
         hit.px = px; hit.pz = pz;
         if (distance > 1.0E-9) { hit.sideX = ex / distance; hit.sideZ = ez / distance; }
         else { hit.sideX = -dz / length; hit.sideZ = dx / length; }
         double s = run.arc[i] + (run.arc[i + 1] - run.arc[i]) * t;
         // Where the waterway runs dry (a mapped line over a ridge) it has neither water nor banks.
         if (run.dry(s)) return null;
         hit.waterY = run.waterY(s);
         hit.depth = line.depth();
         hit.s = s;
         // Only a column in the channel needs what fills it (the bank lip is read when a bank is shaped).
         boolean wet = distance <= halfWidth;
         int fall = run.fallNear(s, 0.75);
         int next = run.waterY(s + 1.0);
         if (fall >= 0) {
            hit.falling = true;
            hit.waterY = (int)Math.ceil(run.fallUpper[fall] - 1.0E-6) - 1;
            hit.lowerY = (int)Math.ceil(run.fallLower[fall] - 1.0E-6) - 1;
         } else if (hit.waterY - next >= 2 && blend > 0.0) {
            // A cascade steeper than a block per block falls as a curtain to the next level.
            hit.falling = true;
            hit.lowerY = next;
         } else if (wet) {
            // Vanilla flowing water loses a level per block: only the approach to a descent flows.
            for (int blocks = 1; blocks <= 7; blocks++) {
               if (run.waterY(s + blocks) < hit.waterY) { hit.flowLevel = 8 - blocks; break; }
            }
         }
         return hit;
      }
   }
}
