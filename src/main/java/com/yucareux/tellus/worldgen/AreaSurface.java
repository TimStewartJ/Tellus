package com.yucareux.tellus.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The water surface of the mapped river areas around one plan, read from the terrain itself.
 *
 * <p>Elevation models flatten water: inside a river area, away from its banks, the model's surface is the
 * water's, sloping with the river and dropping where it falls. The map only says where the water is; its level
 * at every point comes from that surface, smoothed so the model's noise and its flattening facets become gentle
 * slopes, while drops stay drops (the smoothing only averages heights close to each other). Narrow parts, where
 * every point is near a bank and the model shows the banks, take the lowest ground around instead.</p>
 *
 * <p>Where the surface drops between pools (a fall, a cascade), the water follows the terrain down the drop as the
 * model shows it: down the slope its resolution blurs a cliff into, falling vertically only where the model itself
 * has a cliff, and running shallow there. The terrain is not cut into steps or plunge pools.</p>
 *
 * <p>A narrow area moves with the course of the waterway drawn in it ({@link Carrier}): where the map and the terrain
 * model disagree (an outline drawn up a canyon wall beside the floor the model shows), the course runs on the floor and
 * the area's water moves the same way at each point along it, so it keeps its outline's shape and stands on that floor
 * at the floor's level. Where the course stays on its line, so does the area.</p>
 *
 * <p>Every step reads only the map, the terrain and those courses within a fixed distance of a point, on a
 * world-aligned grid, so plans of neighbouring areas compute exactly the same levels where they overlap.</p>
 */
final class AreaSurface {
   /**
    * Where a moved area (moved at least {@link #MOVED} blocks there) still stands this far (blocks) above the floor its
    * waterway's course runs on, its outline disagrees with the terrain beyond what the course could move: that part is
    * bank, not water held up on the valley side.
    */
   private static final double MOVED = 4.0, RISE = 8.0;

   /**
    * The course of a waterway drawn in river areas: its vertices, the mapped points they moved from, and how far from the
    * course the areas its mapped line runs in reach, blocks (they move with it that far beyond where the course moved).
    */
   record Carrier(double[] xs, double[] zs, double[] mappedX, double[] mappedZ, double reach) {}

   /** How far from its waterway's course a river area of that half-width reaches. */
   static double carrierReach(double halfWidth) {
      return halfWidth * 1.25 + 16.0;
   }

   /** The farthest any of these courses moved from its mapped line, blocks: how far the areas they carry move. */
   static double drift(List<Carrier> carriers) {
      double farthest = 0.0;
      for (Carrier carrier : carriers)
         for (int v = 0; v < carrier.xs().length; v++)
            farthest = Math.max(farthest, Math.hypot(carrier.xs()[v] - carrier.mappedX()[v], carrier.zs()[v] - carrier.mappedZ()[v]));
      return farthest;
   }

   /** Grid spacing of block plans, blocks; distant LODs use a coarser grid and scale every distance below with it. */
   static final int GRID = 8;
   /** Heights further apart than about this (blocks) are barely averaged: separate pools stay separate. */
   private static final double RANGE_SIGMA = 1.25;
   private static final int SMOOTH_PASSES = 2;
   /** A slope steeper than this (blocks per block) is a blurred fall or a cascade. */
   private static final double STEEP = 0.2;
   /** Fewest grid points of gentle water that make a pool a fall can drop from or into. */
   private static final int MIN_POOL_POINTS = 6;
   /** Corner levels further apart than this belong to different pools; a steeper stretch dropping more is a fall. */
   static final double POOL_GAP = 2.5;
   /** Farthest a pool's water may stand above the ground it covers, blocks. */
   private static final float FILL = 2.0F;
   /** Farthest shore distance measured, fine grid blocks. */
   private static final double MAX_SHORE = 64.0;
   private static final double SHORE_PIECE = 16.0;
   private static final int SHORE_BIN = 32;

   /** Size of a typical elevation model cell (about 30 m), blocks. */
   private static double demBlocks(double worldScale) {
      return Math.max(1.0, 30.0 / Math.max(1.0E-6, worldScale));
   }

   /** Spatial reach of the smoothing (two standard deviations), fine grid blocks: the model's noise spans a cell or two. */
   private static double smoothReach(double worldScale) {
      return Math.max(16.0, 1.6 * demBlocks(worldScale));
   }

   /** How far the pools and falls around a point reach, fine grid blocks: the blur of a few cells. */
   private static double poolSearch(double worldScale) {
      return Math.max(24.0, 3.2 * demBlocks(worldScale));
   }

   final int minX, minZ, size, grid;
   // Distances scaled with the grid, blocks.
   private final double smoothReach, maxShore, shorePiece, seamProbe;
   private final int shoreBin;
   private final float[] level;
   private final float[] halfWidth;
   /** Terrain at every grid point: between points of pools above and below a fall it places the crest smoothly. */
   private final float[] terrainAt;
   /** Grid points on a fall, and those where the model shows the water's surface (away from the banks). */
   private final boolean[] fall, interior;
   /** How much a fall thins the water: 1 on one, fading to 0 a few grid steps away. */
   private final float[] shallow;
   private final List<HydrologyWaterMap.Shape> areas;
   private final it.unimi.dsi.fastutil.longs.LongOpenHashSet identities = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
   private final int areaBin, areaBinMinX, areaBinMinZ, areaBinColumns, areaBinRows;
   private final HydrologyWaterMap.Shape[][] areaBins;
   private final int binMinX, binMinZ, binColumns, binRows;
   private final double[][] shoreBins;
   // River areas moving with their waterways' courses: per area identity, at every grid point the shift (dx, dz) and how
   // far the ground there stands above the floor of the course (NaN beyond its reach); and the largest shift.
   private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<float[]> shifts = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
   private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap moved = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();

   /** The margin a grid of this spacing needs beyond the area it serves so that every value there is exact, blocks. */
   static int margin(int grid, double worldScale) {
      double k = grid / (double)GRID;
      // Seeds read up to 17 points around (the half-width window and the lowest ground across); then the smoothing, the
      // falls and the fills reach further, each in turn.
      return (int)Math.ceil(grid * 24 + (smoothReach(worldScale) * SMOOTH_PASSES + poolSearch(worldScale) + 48.0) * k);
   }

   /** How far beyond the area it serves a grid of this spacing reads the map, blocks. */
   static int reads(int grid, double worldScale) {
      return margin(grid, worldScale) + (int)Math.ceil(MAX_SHORE * grid / GRID);
   }

   /**
    * Levels for {@code [x0, x1] x [z0, z1]} (blocks) from the river areas near it, on a grid of {@code grid}
    * blocks. {@code terrain} gives terrain heights and the model's resolution; {@code carriers} the courses of the
    * waterways the areas move with (none: every area as mapped).
    */
   AreaSurface(List<HydrologyWaterMap.Shape> nearAreas, int x0, int z0, int x1, int z1, RiverChannels.Terrain terrain, int grid, double worldScale,
      List<Carrier> carriers) {
      this.grid = grid;
      double k = grid / (double)GRID;
      this.smoothReach = smoothReach(worldScale) * k;
      this.maxShore = MAX_SHORE * k; this.shorePiece = SHORE_PIECE * k; this.shoreBin = (int)Math.round(SHORE_BIN * k);
      this.seamProbe = Math.max(2.0, grid * 0.25);
      int pad = margin(grid, worldScale);
      this.minX = Math.floorDiv(x0 - pad, grid) * grid;
      this.minZ = Math.floorDiv(z0 - pad, grid) * grid;
      int maxX = Math.floorDiv(x1 + pad, grid) * grid + grid, maxZ = Math.floorDiv(z1 + pad, grid) * grid + grid;
      this.size = Math.max((maxX - minX) / grid, (maxZ - minZ) / grid) + 1;
      List<HydrologyWaterMap.Shape> relevant = new ArrayList<>();
      double far = minX + (size - 1) * (double)grid, farZ = minZ + (size - 1) * (double)grid;
      // An area a waterway's course moved in from further away counts too.
      double slack = Math.ceil(drift(carriers));
      boolean served = false;
      for (HydrologyWaterMap.Shape area : nearAreas) {
         if (area.maxX + slack < minX - maxShore || area.minX - slack > far + maxShore || area.maxZ + slack < minZ - maxShore || area.minZ - slack > farZ + maxShore) continue;
         relevant.add(area);
         served |= area.maxX + slack >= x0 - grid && area.minX - slack <= x1 + grid && area.maxZ + slack >= z0 - grid && area.minZ - slack <= z1 + grid;
      }
      // Without an area reaching the cells this serves, none of them has a level, whatever lies around.
      this.areas = served ? List.copyOf(relevant) : List.of();
      for (HydrologyWaterMap.Shape area : areas) identities.add(area.identity());
      buildShifts(carriers, terrain);
      // Areas binned by their bounds, so a point is tested only against the outlines that may hold it.
      this.areaBin = Math.max(64, grid * 8);
      this.areaBinMinX = Math.floorDiv(minX - (int)maxShore, areaBin);
      this.areaBinMinZ = Math.floorDiv(minZ - (int)maxShore, areaBin);
      this.areaBinColumns = Math.floorDiv((int)far + (int)maxShore, areaBin) - areaBinMinX + 1;
      this.areaBinRows = Math.floorDiv((int)farZ + (int)maxShore, areaBin) - areaBinMinZ + 1;
      this.areaBins = binAreas();

      // Shores: outline pieces with water on one side only. A map tile clips a polygon with a buffer, so its cut
      // edges lie inside the neighbouring tile's piece of it and are no shore.
      this.binMinX = Math.floorDiv(minX - (int)maxShore, shoreBin);
      this.binMinZ = Math.floorDiv(minZ - (int)maxShore, shoreBin);
      this.binColumns = Math.floorDiv((int)far + (int)maxShore, shoreBin) - binMinX + 1;
      this.binRows = Math.floorDiv((int)farZ + (int)maxShore, shoreBin) - binMinZ + 1;
      this.shoreBins = buildShores(far, farZ);

      int count = size * size;
      boolean[] inside = new boolean[count];
      float[] shore = new float[count], ground = new float[count], resolution = new float[count];
      boolean any = false;
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         int n = j * size + i;
         double x = minX + i * (double)grid, z = minZ + j * (double)grid;
         if (!insideAny(x, z)) continue;
         inside[n] = true;
         any = true;
         shore[n] = (float)shoreDistance(x, z, maxShore);
         resolution[n] = (float)terrain.resolution((int)x, (int)z);
      }
      if (!any) {
         this.level = this.halfWidth = this.terrainAt = this.shallow = new float[0];
         this.fall = this.interior = new boolean[0];
         return;
      }
      this.level = new float[count];
      this.halfWidth = new float[count];
      this.terrainAt = ground;
      this.shallow = new float[count];
      Arrays.fill(level, Float.NaN);
      Arrays.fill(ground, Float.NaN);
      boolean[] around = dilate(inside, 2);
      for (int n = 0; n < count; n++)
         if (around[n]) ground[n] = (float)terrain.height(minX + n % size * grid, minZ + n / size * grid);

      // Interior points read the water surface from the model; points far from any interior take the lowest
      // ground around them (a narrow channel, where the model shows its banks).
      boolean[] interior = this.interior = new boolean[count];
      for (int n = 0; n < count; n++)
         interior[n] = inside[n] && Float.isFinite(ground[n]) && shore[n] >= Math.max(3.0, resolution[n]);
      // Half-width of the water around each point: the farthest shore distance near it.
      float[] reach = new float[count];
      for (int n = 0; n < count; n++) reach[n] = inside[n] ? shore[n] : 0.0F;
      float[] widest = maximum(reach, (int)Math.ceil(64.0 / GRID));
      System.arraycopy(widest, 0, halfWidth, 0, count);
      float[] seed = new float[count];
      Arrays.fill(seed, Float.NaN);
      for (int n = 0; n < count; n++) if (interior[n]) seed[n] = median(ground, interior, n % size, n / size, 1);
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         int n = j * size + i;
         if (!inside[n] || interior[n]) continue;
         // Near a bank the model shows the bank (a quay, a levee). The open water just off it holds the level, no
         // higher than the ground right around it, unless that water stands above this ground (beyond a fall). Else
         // the water stands no higher than the lowest ground across the channel (in a gorge, its floor). Across a
         // wide river that reach would also take the water below its next step downstream.
         float open = nearestInterior(seed, interior, i, j, (int)Math.ceil(resolution[n] / grid) + 1);
         if (open <= ground[n] + FILL) {
            float near = lowest(ground, inside, i, j, 2);
            seed[n] = Float.isNaN(near) ? open : Math.min(open, near);
         } else seed[n] = lowest(ground, inside, i, j, Math.max(2, Math.min(8, (int)Math.ceil(halfWidth[n] / grid) + 1)));
      }
      float[] smooth = seed;
      for (int pass = 0; pass < SMOOTH_PASSES; pass++) smooth = bilateral(smooth);
      // Every other point inside, and a ring outside for the banks, takes the nearest smoothed level.
      float[] filled = nearest(smooth, inside);
      this.fall = falls(filled, inside);
      // On a fall near its banks, where the model shows them rather than the water, the lowest ground around reaches
      // down the fall to the pool below. Beside open water the water there takes the level of the open water just off
      // the bank, no higher than its own ground; a narrow channel with no open water keeps its floor.
      for (int n = 0; n < count; n++) {
         if (!fall[n] || interior[n]) continue;
         float open = nearestInterior(filled, interior, n % size, n / size, (int)Math.ceil(resolution[n] / grid) + 1);
         if (Float.isFinite(open) && Float.isFinite(ground[n])) filled[n] = Math.max(filled[n], Math.min(open, ground[n]));
      }
      // The ring outside follows the levels inside.
      for (int n = 0; n < count; n++) if (!inside[n]) filled[n] = Float.NaN;
      filled = nearest(filled, inside);
      System.arraycopy(filled, 0, level, 0, count);
      // Over a fall the water runs shallow, deepening over a few grid steps into the pools above and below it.
      int reachPoints = 3;
      for (int n = 0; n < count; n++) {
         if (!inside[n] || !Float.isFinite(level[n])) continue;
         int i = n % size, j = n / size;
         double nearest = 0.0;
         for (int dj = -reachPoints; dj <= reachPoints; dj++) for (int di = -reachPoints; di <= reachPoints; di++) {
            int a = i + di, b = j + dj;
            if (a < 0 || b < 0 || a >= size || b >= size || !fall[b * size + a]) continue;
            nearest = Math.max(nearest, 1.0 - Math.hypot(di, dj) / (reachPoints + 1.0));
         }
         shallow[n] = (float)nearest;
      }
   }

   boolean empty() {
      return areas.isEmpty();
   }

   List<HydrologyWaterMap.Shape> areas() {
      return areas;
   }

   /** Whether this levels (a fragment of) the water body of this identity. */
   boolean levels(long identity) {
      return identities.contains(identity);
   }

   /** Whether a position lies inside a river area (an island hole excepted). */
   boolean inside(double x, double z) {
      return insideAny(x, z);
   }

   private boolean insideAny(double x, double z) {
      for (HydrologyWaterMap.Shape area : candidates(x, z)) if ((rings(area, x, z) & 1) != 0) return true;
      return false;
   }

   /** The rings of an area's outline holding a position (see {@link HydrologyWaterMap.Shape#ringsContaining}), the outline moved with its waterway. */
   private int rings(HydrologyWaterMap.Shape area, double x, double z) {
      float[] field = shifts.isEmpty() ? null : shifts.get(area.identity());
      if (field == null) return area.ringsContaining(x, z);
      double sx = value(field, x, z, 0), sz = value(field, x, z, 1);
      int rings = area.ringsContaining(x - sx, z - sz);
      // Moved water still standing well above its waterway's floor lies where the outline and the terrain disagree beyond
      // what the course could move: bank, not water held up on the valley side.
      if ((rings & 1) != 0 && sx * sx + sz * sz >= MOVED * MOVED && value(field, x, z, 2) > RISE) return 0;
      return rings;
   }

   /**
    * One value of an area's field at a position, bilinear between grid points: {@code channel} 0 and 1 the shift along x
    * and z (none beyond the grid), 2 the rise of the ground above the floor of the course (NaN where unknown).
    */
   private double value(float[] field, double x, double z, int channel) {
      double fx = (x - minX) / grid, fz = (z - minZ) / grid;
      int gx = (int)Math.floor(fx), gz = (int)Math.floor(fz);
      if (gx < 0 || gz < 0 || gx + 1 >= size || gz + 1 >= size) return channel == 2 ? Double.NaN : 0.0;
      double tx = fx - gx, tz = fz - gz;
      int a = 3 * (gz * size + gx) + channel, b = a + 3 * size;
      return (1 - tx) * (1 - tz) * field[a] + tx * (1 - tz) * field[a + 3] + (1 - tx) * tz * field[b] + tx * tz * field[b + 3];
   }

   /** Where a point of an area's outline is drawn, moved with its waterway: the point plus the shift there. */
   private double[] drawn(float[] field, double x, double z) {
      double px = x + value(field, x, z, 0), pz = z + value(field, x, z, 1);
      return new double[]{x + value(field, px, pz, 0), z + value(field, px, pz, 1)};
   }

   /**
    * Per river area moving with the courses of the waterways mapped in it (each piece of a course carries the areas its
    * mapped line lies in, so an area mapped in several pieces moves as one), at every grid point: the shift of the
    * nearest point of those courses within their reach (linear between vertices; none beyond), and how far the ground
    * there stands above the floor the course runs on. An area none of whose courses moved keeps its outline.
    */
   private void buildShifts(List<Carrier> carriers, RiverChannels.Terrain terrain) {
      if (carriers.isEmpty() || areas.isEmpty() || terrain == null) return;
      it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<List<int[]>> carried = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
      for (int c = 0; c < carriers.size(); c++) {
         Carrier carrier = carriers.get(c);
         for (int v = 1; v < carrier.xs().length; v++) {
            double mx = (carrier.mappedX()[v - 1] + carrier.mappedX()[v]) * 0.5, mz = (carrier.mappedZ()[v - 1] + carrier.mappedZ()[v]) * 0.5;
            for (HydrologyWaterMap.Shape area : areas)
               if (area.near(mx, mz, 0.0) && (area.ringsContaining(mx, mz) & 1) != 0)
                  carried.computeIfAbsent(area.identity(), k -> new ArrayList<>()).add(new int[]{c, v});
         }
      }
      // How far each course moved: its areas move that far, so it reaches the places they moved from as well.
      double[] drift = new double[carriers.size()];
      for (int c = 0; c < carriers.size(); c++) {
         Carrier carrier = carriers.get(c);
         for (int v = 0; v < carrier.xs().length; v++)
            drift[c] = Math.max(drift[c], Math.hypot(carrier.xs()[v] - carrier.mappedX()[v], carrier.zs()[v] - carrier.mappedZ()[v]));
      }
      float[] sampled = null;
      for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<List<int[]>> entry : carried.long2ObjectEntrySet()) {
         double largest = 0.0;
         for (int[] piece : entry.getValue()) {
            Carrier carrier = carriers.get(piece[0]);
            for (int v = piece[1] - 1; v <= piece[1]; v++)
               largest = Math.max(largest, Math.hypot(carrier.xs()[v] - carrier.mappedX()[v], carrier.zs()[v] - carrier.mappedZ()[v]));
         }
         if (largest < 0.5) continue;
         float[] field = new float[3 * size * size], best = new float[size * size], floor = new float[size * size];
         Arrays.fill(best, Float.POSITIVE_INFINITY);
         Arrays.fill(floor, Float.NaN);
         for (int[] piece : entry.getValue()) {
            Carrier carrier = carriers.get(piece[0]);
            int v = piece[1];
            double ax = carrier.xs()[v - 1], az = carrier.zs()[v - 1], bx = carrier.xs()[v], bz = carrier.zs()[v];
            double dx = bx - ax, dz = bz - az, squared = dx * dx + dz * dz, reach = carrier.reach() + drift[piece[0]] + grid;
            double sax = ax - carrier.mappedX()[v - 1], saz = az - carrier.mappedZ()[v - 1];
            double sbx = bx - carrier.mappedX()[v], sbz = bz - carrier.mappedZ()[v];
            double fa = terrain.height((int)Math.floor(ax), (int)Math.floor(az)), fb = terrain.height((int)Math.floor(bx), (int)Math.floor(bz));
            int i0 = Math.max(0, (int)Math.floor((Math.min(ax, bx) - reach - minX) / grid));
            int i1 = Math.min(size - 1, (int)Math.ceil((Math.max(ax, bx) + reach - minX) / grid));
            int j0 = Math.max(0, (int)Math.floor((Math.min(az, bz) - reach - minZ) / grid));
            int j1 = Math.min(size - 1, (int)Math.ceil((Math.max(az, bz) + reach - minZ) / grid));
            for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
               double px = minX + i * (double)grid, pz = minZ + j * (double)grid;
               double t = squared > 1.0E-18 ? Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / squared)) : 0.0;
               double ex = px - ax - dx * t, ez = pz - az - dz * t, d = ex * ex + ez * ez;
               int n = j * size + i;
               if (d > reach * reach || d >= best[n]) continue;
               best[n] = (float)d;
               field[3 * n] = (float)(sax + (sbx - sax) * t);
               field[3 * n + 1] = (float)(saz + (sbz - saz) * t);
               floor[n] = (float)(fa + (fb - fa) * t);
            }
         }
         // The ground where the water is drawn (the median of the points around, so a bridge or a tree does not count).
         if (sampled == null) { sampled = new float[size * size]; Arrays.fill(sampled, Float.NaN); }
         for (int n = 0; n < size * size; n++) {
            field[3 * n + 2] = Float.NaN;
            if (!Float.isFinite(floor[n])) continue;
            int i = n % size, j = n / size;
            float[] around = new float[9];
            int k = 0;
            for (int b = Math.max(0, j - 1); b <= Math.min(size - 1, j + 1); b++) for (int a = Math.max(0, i - 1); a <= Math.min(size - 1, i + 1); a++) {
               int m = b * size + a;
               if (Float.isNaN(sampled[m])) sampled[m] = (float)terrain.height(minX + a * grid, minZ + b * grid);
               if (Float.isFinite(sampled[m])) around[k++] = sampled[m];
            }
            if (k == 0) continue;
            Arrays.sort(around, 0, k);
            field[3 * n + 2] = around[k / 2] - floor[n];
         }
         shifts.put(entry.getLongKey(), field);
         moved.put(entry.getLongKey(), Math.ceil(largest) + 1.0);
      }
   }

   /**
    * Points of the river areas inside a coarse cell centred on {@code (x, z)}, where a footprint should look for water:
    * the cell's point nearest its centre inside an area, and where the outlines cross the cell.
    */
   void footprintPoints(int x, int z, int minX, int minZ, int maxX, int maxZ, List<int[]> points) {
      for (HydrologyWaterMap.Shape area : areasNear(minX, minZ, maxX + 1, maxZ + 1)) {
         // A moved area is looked for where its outline lies mapped; the cell is small enough to move it as a whole.
         float[] field = shifts.isEmpty() ? null : shifts.get(area.identity());
         int dx = field == null ? 0 : (int)Math.round(value(field, x, z, 0)), dz = field == null ? 0 : (int)Math.round(value(field, x, z, 1));
         int ax0 = minX - dx, az0 = minZ - dz, ax1 = maxX - dx, az1 = maxZ - dz;
         if (area.maxX < ax0 || area.minX > ax1 + 1 || area.maxZ < az0 || area.minZ > az1 + 1) continue;
         int cx = Math.max(ax0, Math.min(ax1, x - dx)), cz = Math.max(az0, Math.min(az1, z - dz));
         if (area.contains(cx, cz)) points.add(new int[]{cx + dx, cz + dz});
         area.segments(ax0, az0, ax1, az1, (sx, sz, ex, ez) -> {
            int[] point = InlandHydrologyPlanner.clippedPoint(sx, sz, ex, ez, x - dx, z - dz, ax0, az0, ax1, az1);
            if (point != null) points.add(new int[]{point[0] + dx, point[1] + dz});
         });
      }
   }

   private static final HydrologyWaterMap.Shape[] NONE = new HydrologyWaterMap.Shape[0];

   /** The areas whose bounds may hold a position. */
   private HydrologyWaterMap.Shape[] candidates(double x, double z) {
      if (areas.isEmpty()) return NONE;
      int bx = Math.floorDiv((int)Math.floor(x), areaBin) - areaBinMinX, bz = Math.floorDiv((int)Math.floor(z), areaBin) - areaBinMinZ;
      if (bx < 0 || bz < 0 || bx >= areaBinColumns || bz >= areaBinRows) return areas.toArray(NONE);
      HydrologyWaterMap.Shape[] bin = areaBins[bz * areaBinColumns + bx];
      return bin == null ? NONE : bin;
   }

   /** The areas whose bounds may meet a box, each once. */
   List<HydrologyWaterMap.Shape> areasNear(double minX, double minZ, double maxX, double maxZ) {
      if (areas.isEmpty()) return List.of();
      int x0 = Math.floorDiv((int)Math.floor(minX), areaBin) - areaBinMinX, x1 = Math.floorDiv((int)Math.floor(maxX), areaBin) - areaBinMinX;
      int z0 = Math.floorDiv((int)Math.floor(minZ), areaBin) - areaBinMinZ, z1 = Math.floorDiv((int)Math.floor(maxZ), areaBin) - areaBinMinZ;
      if (x0 < 0 || z0 < 0 || x1 >= areaBinColumns || z1 >= areaBinRows) return areas;
      List<HydrologyWaterMap.Shape> near = new ArrayList<>(4);
      for (int bz = z0; bz <= z1; bz++) for (int bx = x0; bx <= x1; bx++) {
         HydrologyWaterMap.Shape[] bin = areaBins[bz * areaBinColumns + bx];
         if (bin != null) for (HydrologyWaterMap.Shape area : bin) if (!near.contains(area)) near.add(area);
      }
      return near;
   }

   private HydrologyWaterMap.Shape[][] binAreas() {
      HydrologyWaterMap.Shape[][] bins = new HydrologyWaterMap.Shape[areaBinColumns * areaBinRows][];
      if (areas.isEmpty()) return bins;
      List<List<HydrologyWaterMap.Shape>> lists = new ArrayList<>(bins.length);
      for (int i = 0; i < bins.length; i++) lists.add(null);
      for (HydrologyWaterMap.Shape area : areas) {
         // A moved area is found where it is drawn.
         double m = moved.get(area.identity());
         int x0 = Math.max(0, (int)Math.floor((area.minX - m) / areaBin) - areaBinMinX), x1 = Math.min(areaBinColumns - 1, (int)Math.floor((area.maxX + m) / areaBin) - areaBinMinX);
         int z0 = Math.max(0, (int)Math.floor((area.minZ - m) / areaBin) - areaBinMinZ), z1 = Math.min(areaBinRows - 1, (int)Math.floor((area.maxZ + m) / areaBin) - areaBinMinZ);
         for (int bz = z0; bz <= z1; bz++) for (int bx = x0; bx <= x1; bx++) {
            int index = bz * areaBinColumns + bx;
            if (lists.get(index) == null) lists.set(index, new ArrayList<>(2));
            lists.get(index).add(area);
         }
      }
      for (int i = 0; i < bins.length; i++) if (lists.get(i) != null) bins[i] = lists.get(i).toArray(NONE);
      return bins;
   }

   /** Whether a position lies on an island of a river area (inside an outline, in a hole of it). */
   boolean island(double x, double z) {
      boolean island = false;
      for (HydrologyWaterMap.Shape area : candidates(x, z)) {
         int rings = rings(area, x, z);
         if ((rings & 1) != 0) return false;
         if (rings > 0) island = true;
      }
      return island;
   }

   /**
    * The surface level (a ground height, the water's top block one below it) at a position for a column whose
    * ground is {@code ground}: between grid points the levels of one pool blend; where the points around belong
    * to pools above and below a fall, the column's ground chooses the pool, and on a fall the water follows it.
    * NaN where no area level reaches.
    */
   double level(double x, double z, double ground) {
      return choose(x, z, ground, () -> ground)[0];
   }

   /**
    * The level and the surface's fall at the column (blocks per block), and 1 on a fall (else 0), as {level, slope, fall},
    * for a column whose ground is {@code raw}. The robust local ground the supplier gives is read only where a fall runs
    * between the grid points around.
    */
   double[] choose(double x, double z, double raw, java.util.function.DoubleSupplier groundSupplier) {
      if (level.length == 0) return new double[]{Double.NaN, 0.0, 0.0};
      double fx = (x - minX) / grid, fz = (z - minZ) / grid;
      int gx = (int)Math.floor(fx), gz = (int)Math.floor(fz);
      if (gx < 0 || gz < 0 || gx + 1 >= size || gz + 1 >= size) return new double[]{Double.NaN, 0.0, 0.0};
      double tx = fx - gx, tz = fz - gz;
      int first = gz * size + gx;
      int[] at = {first, first + 1, first + size, first + size + 1};
      double[] corner = new double[4];
      boolean[] use = new boolean[4];
      double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
      boolean onFall = false, open = true;
      for (int c = 0; c < 4; c++) {
         corner[c] = level[at[c]];
         use[c] = Double.isFinite(corner[c]);
         if (!use[c]) continue;
         low = Math.min(low, corner[c]); high = Math.max(high, corner[c]);
         onFall |= fall[at[c]];
         open &= interior[at[c]];
      }
      if (low == Double.POSITIVE_INFINITY) return new double[]{Double.NaN, 0.0, 0.0};
      // The crest between grid points of two pools, and the water on a fall, follow the column's ground (the caller's
      // robust local measure; without one, the terrain blended between the points).
      double ground = Double.NaN;
      if (onFall || high - low > POOL_GAP) {
         ground = groundSupplier.getAsDouble();
         if (!Double.isFinite(ground)) {
            float[] t = {terrainAt[at[0]], terrainAt[at[1]], terrainAt[at[2]], terrainAt[at[3]]};
            if (Float.isFinite(t[0]) && Float.isFinite(t[1]) && Float.isFinite(t[2]) && Float.isFinite(t[3]))
               ground = (1 - tx) * (1 - tz) * t[0] + tx * (1 - tz) * t[1] + (1 - tx) * tz * t[2] + tx * tz * t[3];
         }
      }
      if (!onFall && high - low > POOL_GAP && Double.isFinite(ground)) {
         // Pools above and below a fall: split the corners at their widest gap, then the ground decides.
         double[] sorted = new double[4];
         int n = 0;
         for (int c = 0; c < 4; c++) if (use[c]) sorted[n++] = corner[c];
         Arrays.sort(sorted, 0, n);
         double split = sorted[0], gap = -1.0, second = 0.0, upperLevel = sorted[0];
         for (int k = 1; k < n; k++) {
            double g = sorted[k] - sorted[k - 1];
            if (g > gap) { second = Math.max(second, gap); gap = g; split = (sorted[k] + sorted[k - 1]) * 0.5; upperLevel = sorted[k]; }
            else second = Math.max(second, g);
         }
         // Only one gap far wider than the others is a fall between two pools; a steep ramp (a cascade) blends.
         if (gap > POOL_GAP && gap > 2.0 * second) {
            // The upper pool reaches only as far as its water stands at most a little above the ground: the crest is at
            // the top of the cliff, and no water is held up over the slope below it.
            boolean upper = ground + FILL >= upperLevel;
            for (int c = 0; c < 4; c++) if (use[c]) use[c] = upper == corner[c] > split;
         }
      }
      double value = blend(corner, use, tx, tz);
      if (onFall && Double.isFinite(ground)) {
         // On a fall the water follows the terrain down the drop, never above the column's own ground (the local one
         // where a bridge or a rock stands on it): at that ground where the model shows the water's surface there, no
         // higher than the highest level around; else no higher than the levels around (in a narrow gorge the model
         // shows the banks, and the levels around are its floor's).
         double own = Double.isFinite(raw) ? Math.min(ground, raw) : ground;
         value = Math.min(open ? high : value, own);
      }
      double h = 0.5 / grid;
      double x0 = Math.max(0.0, tx - h), x1 = Math.min(1.0, tx + h), z0 = Math.max(0.0, tz - h), z1 = Math.min(1.0, tz + h);
      double slopeX = (blend(corner, use, x1, tz) - blend(corner, use, x0, tz)) / ((x1 - x0) * grid);
      double slopeZ = (blend(corner, use, tx, z1) - blend(corner, use, tx, z0)) / ((z1 - z0) * grid);
      double slope = Math.hypot(slopeX, slopeZ);
      return new double[]{value, Double.isFinite(slope) ? slope : 0.0, onFall ? 1.0 : 0.0};
   }

   private static double blend(double[] levels, boolean[] use, double tx, double tz) {
      double[] weights = {(1 - tx) * (1 - tz), tx * (1 - tz), (1 - tx) * tz, tx * tz};
      double sum = 0.0, weight = 0.0;
      int used = 0;
      double plain = 0.0;
      for (int c = 0; c < 4; c++) if (use[c]) { sum += levels[c] * weights[c]; weight += weights[c]; plain += levels[c]; used++; }
      // On a grid line the corners across it weigh nothing; they still count when the near ones have no level.
      return weight > 0.0 ? sum / weight : used > 0 ? plain / used : Double.NaN;
   }

   /** Half-width of the water around a position, blocks (bilinear between grid points). */
   double halfWidth(double x, double z) {
      return bilinear(halfWidth, x, z);
   }

   /** Typical width of the widest river area holding a position, blocks (0 outside them): how deep a river this is. */
   double areaWidth(double x, double z) {
      double widest = 0.0;
      for (HydrologyWaterMap.Shape area : candidates(x, z))
         if ((rings(area, x, z) & 1) != 0) widest = Math.max(widest, area.width());
      return widest;
   }

   /** How much a fall thins the water at a position: 1 on one, 0 a few grid steps away. */
   double shallow(double x, double z) {
      return bilinear(shallow, x, z);
   }

   private double bilinear(float[] values, double x, double z) {
      if (values.length == 0) return 0.0;
      double fx = (x - minX) / grid, fz = (z - minZ) / grid;
      int gx = (int)Math.floor(fx), gz = (int)Math.floor(fz);
      if (gx < 0 || gz < 0 || gx + 1 >= size || gz + 1 >= size) return 0.0;
      double tx = fx - gx, tz = fz - gz;
      return (1 - tx) * (1 - tz) * values[gz * size + gx] + tx * (1 - tz) * values[gz * size + gx + 1]
         + (1 - tx) * tz * values[(gz + 1) * size + gx] + tx * tz * values[(gz + 1) * size + gx + 1];
   }

   /** Distance to the nearest shore (water on one side only), at most {@code maximum}; {@code maximum} when none is nearer. */
   double shoreDistance(double x, double z, double maximum) {
      double best = maximum;
      int bx0 = Math.floorDiv((int)Math.floor(x - maximum), shoreBin) - binMinX, bx1 = Math.floorDiv((int)Math.floor(x + maximum), shoreBin) - binMinX;
      int bz0 = Math.floorDiv((int)Math.floor(z - maximum), shoreBin) - binMinZ, bz1 = Math.floorDiv((int)Math.floor(z + maximum), shoreBin) - binMinZ;
      for (int bz = Math.max(0, bz0); bz <= Math.min(binRows - 1, bz1); bz++)
         for (int bx = Math.max(0, bx0); bx <= Math.min(binColumns - 1, bx1); bx++) {
            double[] pieces = shoreBins[bz * binColumns + bx];
            if (pieces == null) continue;
            for (int k = 0; k < pieces.length; k += 4) {
               double ax = pieces[k], az = pieces[k + 1], dx = pieces[k + 2] - ax, dz = pieces[k + 3] - az;
               double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
               double ex = x - ax - dx * t, ez = z - az - dz * t, squared = ex * ex + ez * ez;
               if (squared < best * best) best = Math.sqrt(squared);
            }
         }
      return best;
   }

   /** The nearest shore point within {@code maximum}, as {x, z}, or null. */
   double[] nearestShore(double x, double z, double maximum) {
      double best = maximum * maximum;
      double[] point = null;
      int bx0 = Math.floorDiv((int)Math.floor(x - maximum), shoreBin) - binMinX, bx1 = Math.floorDiv((int)Math.floor(x + maximum), shoreBin) - binMinX;
      int bz0 = Math.floorDiv((int)Math.floor(z - maximum), shoreBin) - binMinZ, bz1 = Math.floorDiv((int)Math.floor(z + maximum), shoreBin) - binMinZ;
      for (int bz = Math.max(0, bz0); bz <= Math.min(binRows - 1, bz1); bz++)
         for (int bx = Math.max(0, bx0); bx <= Math.min(binColumns - 1, bx1); bx++) {
            double[] pieces = shoreBins[bz * binColumns + bx];
            if (pieces == null) continue;
            for (int k = 0; k < pieces.length; k += 4) {
               double ax = pieces[k], az = pieces[k + 1], dx = pieces[k + 2] - ax, dz = pieces[k + 3] - az;
               double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / Math.max(1.0E-12, dx * dx + dz * dz)));
               double px = ax + dx * t, pz = az + dz * t, squared = (x - px) * (x - px) + (z - pz) * (z - pz);
               if (squared <= best) { best = squared; point = new double[]{px, pz}; }
            }
         }
      return point;
   }

   private double[][] buildShores(double far, double farZ) {
      List<List<double[]>> lists = new ArrayList<>(binColumns * binRows);
      for (int i = 0; i < binColumns * binRows; i++) lists.add(null);
      double lowX = minX - maxShore, lowZ = minZ - maxShore, highX = far + maxShore, highZ = farZ + maxShore;
      for (HydrologyWaterMap.Shape area : areas) {
         // A moved area's shore is where it is drawn: each piece of its outline moved with its waterway.
         double m = moved.get(area.identity());
         float[] field = shifts.isEmpty() ? null : shifts.get(area.identity());
         area.segments(lowX - m, lowZ - m, highX + m, highZ + m, (ax, az, bx, bz) -> {
            double dx = bx - ax, dz = bz - az, length = Math.hypot(dx, dz);
            if (length < 1.0E-9 || Math.max(ax, bx) < lowX - m || Math.min(ax, bx) > highX + m || Math.max(az, bz) < lowZ - m || Math.min(az, bz) > highZ + m) return;
            int pieces = Math.max(1, (int)Math.ceil(length / shorePiece));
            for (int p = 0; p < pieces; p++) {
               double sx = ax + dx * p / pieces, sz = az + dz * p / pieces, ex = ax + dx * (p + 1) / pieces, ez = az + dz * (p + 1) / pieces;
               if (field != null) {
                  double[] start = drawn(field, sx, sz), end = drawn(field, ex, ez);
                  sx = start[0]; sz = start[1]; ex = end[0]; ez = end[1];
               }
               double span = Math.hypot(ex - sx, ez - sz);
               if (span < 1.0E-9) continue;
               double nx = -(ez - sz) / span * seamProbe, nz = (ex - sx) / span * seamProbe;
               double mx = (sx + ex) * 0.5, mz = (sz + ez) * 0.5;
               if (insideAny(mx + nx, mz + nz) == insideAny(mx - nx, mz - nz)) continue;
               int bx0 = Math.floorDiv((int)Math.floor(Math.min(sx, ex)), shoreBin) - binMinX, bx1 = Math.floorDiv((int)Math.floor(Math.max(sx, ex)), shoreBin) - binMinX;
               int bz0 = Math.floorDiv((int)Math.floor(Math.min(sz, ez)), shoreBin) - binMinZ, bz1 = Math.floorDiv((int)Math.floor(Math.max(sz, ez)), shoreBin) - binMinZ;
               for (int row = Math.max(0, bz0); row <= Math.min(binRows - 1, bz1); row++)
                  for (int column = Math.max(0, bx0); column <= Math.min(binColumns - 1, bx1); column++) {
                     int bin = row * binColumns + column;
                     if (lists.get(bin) == null) lists.set(bin, new ArrayList<>(4));
                     lists.get(bin).add(new double[]{sx, sz, ex, ez});
                  }
            }
         });
      }
      double[][] bins = new double[binColumns * binRows][];
      for (int i = 0; i < bins.length; i++) {
         List<double[]> list = lists.get(i);
         if (list == null) continue;
         // A fixed order: pieces arrive in the order the outline index visits them, which must not matter.
         list.sort((a, b) -> {
            for (int k = 0; k < 4; k++) { int c = Double.compare(a[k], b[k]); if (c != 0) return c; }
            return 0;
         });
         double[] flat = new double[list.size() * 4];
         for (int k = 0; k < list.size(); k++) System.arraycopy(list.get(k), 0, flat, k * 4, 4);
         bins[i] = flat;
      }
      return bins;
   }

   private boolean[] dilate(boolean[] mask, int radius) {
      boolean[] rows = new boolean[mask.length], out = new boolean[mask.length];
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         boolean hit = false;
         for (int d = Math.max(0, i - radius); d <= Math.min(size - 1, i + radius) && !hit; d++) hit = mask[j * size + d];
         rows[j * size + i] = hit;
      }
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         boolean hit = false;
         for (int d = Math.max(0, j - radius); d <= Math.min(size - 1, j + radius) && !hit; d++) hit = rows[d * size + i];
         out[j * size + i] = hit;
      }
      return out;
   }

   private float[] maximum(float[] values, int radius) {
      float[] rows = new float[values.length], out = new float[values.length];
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         float best = 0.0F;
         for (int d = Math.max(0, i - radius); d <= Math.min(size - 1, i + radius); d++) best = Math.max(best, values[j * size + d]);
         rows[j * size + i] = best;
      }
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         float best = 0.0F;
         for (int d = Math.max(0, j - radius); d <= Math.min(size - 1, j + radius); d++) best = Math.max(best, rows[d * size + i]);
         out[j * size + i] = best;
      }
      return out;
   }

   /** Median of the masked values within {@code radius} grid points (a bridge or a boat is narrower than that). */
   private float median(float[] values, boolean[] mask, int i, int j, int radius) {
      float[] window = new float[(2 * radius + 1) * (2 * radius + 1)];
      int n = 0;
      for (int dj = -radius; dj <= radius; dj++) for (int di = -radius; di <= radius; di++) {
         int a = i + di, b = j + dj;
         if (a < 0 || b < 0 || a >= size || b >= size || !mask[b * size + a]) continue;
         window[n++] = values[b * size + a];
      }
      Arrays.sort(window, 0, n);
      return window[n / 2];
   }

   private float lowest(float[] values, boolean[] mask, int i, int j, int radius) {
      float best = Float.POSITIVE_INFINITY;
      for (int dj = -radius; dj <= radius; dj++) for (int di = -radius; di <= radius; di++) {
         int a = i + di, b = j + dj;
         if (a < 0 || b < 0 || a >= size || b >= size || !mask[b * size + a] || !Float.isFinite(values[b * size + a])) continue;
         best = Math.min(best, values[b * size + a]);
      }
      return best == Float.POSITIVE_INFINITY ? Float.NaN : best;
   }

   /** The value at the interior point nearest {@code (i, j)} within {@code radius} points (the lower of equally near ones); NaN without one. */
   private float nearestInterior(float[] values, boolean[] interior, int i, int j, int radius) {
      float best = Float.NaN;
      int bestDistance = radius * radius + 1;
      for (int dj = -radius; dj <= radius; dj++) for (int di = -radius; di <= radius; di++) {
         int a = i + di, b = j + dj, distance = di * di + dj * dj;
         if (a < 0 || b < 0 || a >= size || b >= size || distance > bestDistance || !interior[b * size + a]) continue;
         float value = values[b * size + a];
         if (distance < bestDistance || value < best) { best = value; bestDistance = distance; }
      }
      return best;
   }

   /** Edge-preserving smoothing of the defined values: near heights average, distant ones (another pool) hardly count. */
   private float[] bilateral(float[] values) {
      int radius = (int)Math.ceil(smoothReach / grid);
      double sigma = smoothReach * 0.5;
      double[] spatial = new double[(2 * radius + 1) * (2 * radius + 1)];
      for (int dj = -radius; dj <= radius; dj++) for (int di = -radius; di <= radius; di++) {
         double d2 = (di * di + dj * dj) * (double)grid * grid;
         spatial[(dj + radius) * (2 * radius + 1) + di + radius] = d2 > smoothReach * smoothReach ? 0.0 : Math.exp(-d2 / (2.0 * sigma * sigma));
      }
      double range = 2.0 * RANGE_SIGMA * RANGE_SIGMA;
      float[] out = new float[values.length];
      for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
         int n = j * size + i;
         float center = values[n];
         if (!Float.isFinite(center)) { out[n] = Float.NaN; continue; }
         double sum = 0.0, weight = 0.0;
         for (int dj = -radius; dj <= radius; dj++) {
            int b = j + dj;
            if (b < 0 || b >= size) continue;
            for (int di = -radius; di <= radius; di++) {
               int a = i + di;
               if (a < 0 || a >= size) continue;
               float v = values[b * size + a];
               if (!Float.isFinite(v)) continue;
               double s = spatial[(dj + radius) * (2 * radius + 1) + di + radius];
               if (s == 0.0) continue;
               double d = v - center;
               double w = s * Math.exp(-d * d / range);
               sum += w * v;
               weight += w;
            }
         }
         out[n] = (float)(sum / weight);
      }
      return out;
   }

   /**
    * Each inside point without a value, and points up to two grid steps outside the areas, take the value of the
    * nearest point with one (breadth first, in grid order).
    */
   private float[] nearest(float[] values, boolean[] inside) {
      int count = values.length;
      float[] out = values.clone();
      int[] distance = new int[count];
      Arrays.fill(distance, Integer.MAX_VALUE);
      int[] queue = new int[count];
      int head = 0, tail = 0;
      for (int n = 0; n < count; n++) if (Float.isFinite(out[n])) { distance[n] = 0; queue[tail++] = n; }
      boolean[] wanted = dilate(inside, 2);
      int[] di = {1, -1, 0, 0, 1, 1, -1, -1}, dj = {0, 0, 1, -1, 1, -1, 1, -1};
      while (head < tail) {
         int n = queue[head++];
         int i = n % size, j = n / size;
         for (int d = 0; d < 8; d++) {
            int a = i + di[d], b = j + dj[d];
            if (a < 0 || b < 0 || a >= size || b >= size) continue;
            int m = b * size + a;
            if (!wanted[m] || distance[m] != Integer.MAX_VALUE) continue;
            distance[m] = distance[n] + 1;
            out[m] = out[n];
            queue[tail++] = m;
         }
      }
      return out;
   }

   private static final int[] DI = {1, -1, 0, 0, 1, 1, -1, -1}, DJ = {0, 0, 1, -1, 1, -1, 1, -1};

   /**
    * Grid points on falls and cascades, where the water follows the terrain down the drop. Pools are connected stretches
    * of gentle water (sizeable ones; a few gentle points within a slope, a ledge in a talus, are part of the slope);
    * every other stretch whose levels, with those of the pools around it, span a drop of more than {@link #POOL_GAP} is
    * a fall. A point left above or below all its neighbours (a spike of the terrain) first joins them, so it makes none.
    */
   private boolean[] falls(float[] values, boolean[] inside) {
      int count = values.length;
      float[] before = values.clone();
      float[] neighbours = new float[8];
      for (int n = 0; n < count; n++) {
         if (!inside[n] || !Float.isFinite(before[n])) continue;
         int k = 0;
         boolean alone = true;
         for (int d = 0; d < 8; d++) {
            int m = neighbour(n, d);
            if (m < 0 || !inside[m] || !Float.isFinite(before[m])) continue;
            neighbours[k++] = before[m];
            alone &= Math.abs(before[m] - before[n]) > POOL_GAP;
         }
         if (k < 3 || !alone) continue;
         Arrays.sort(neighbours, 0, k);
         values[n] = neighbours[k / 2];
      }
      boolean[] steep = new boolean[count];
      for (int n = 0; n < count; n++) {
         if (!inside[n] || !Float.isFinite(values[n])) continue;
         int i = n % size, j = n / size;
         for (int d = 0; d < 8 && !steep[n]; d++) {
            int a = i + DI[d], b = j + DJ[d];
            if (a < 0 || b < 0 || a >= size || b >= size || !inside[b * size + a]) continue;
            float v = values[b * size + a];
            if (Float.isFinite(v) && Math.abs(v - values[n]) / (Math.hypot(DI[d], DJ[d]) * grid) > STEEP) steep[n] = true;
         }
      }
      boolean[] pool = new boolean[count], seen = new boolean[count], falls = new boolean[count];
      int[] queue = new int[count];
      for (int start = 0; start < count; start++) {
         if (seen[start] || !inside[start] || steep[start] || !Float.isFinite(values[start])) continue;
         int tail = component(start, seen, queue, m -> inside[m] && !steep[m] && Float.isFinite(values[m]));
         if (tail < MIN_POOL_POINTS) continue;
         for (int k = 0; k < tail; k++) pool[queue[k]] = true;
      }
      Arrays.fill(seen, false);
      for (int start = 0; start < count; start++) {
         if (seen[start] || !inside[start] || pool[start] || !Float.isFinite(values[start])) continue;
         int points = component(start, seen, queue, m -> inside[m] && !pool[m] && Float.isFinite(values[m]));
         float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
         for (int k = 0; k < points; k++) {
            int n = queue[k];
            low = Math.min(low, values[n]); high = Math.max(high, values[n]);
            for (int d = 0; d < 8; d++) {
               int m = neighbour(n, d);
               if (m >= 0 && pool[m]) { low = Math.min(low, values[m]); high = Math.max(high, values[m]); }
            }
         }
         if (high - low > POOL_GAP) for (int k = 0; k < points; k++) falls[queue[k]] = true;
      }
      return falls;
   }

   /** The grid point beside {@code n} in direction {@code d} (eight directions), or -1 beyond the grid. */
   private int neighbour(int n, int d) {
      int a = n % size + DI[d], b = n / size + DJ[d];
      return a < 0 || b < 0 || a >= size || b >= size ? -1 : b * size + a;
   }

   /** The connected points (eight directions) from {@code start} accepted by {@code accept}, breadth first into {@code queue}; their count. */
   private int component(int start, boolean[] seen, int[] queue, java.util.function.IntPredicate accept) {
      int head = 0, tail = 0;
      queue[tail++] = start;
      seen[start] = true;
      while (head < tail) {
         int n = queue[head++];
         for (int d = 0; d < 8; d++) {
            int m = neighbour(n, d);
            if (m < 0 || seen[m] || !accept.test(m)) continue;
            seen[m] = true;
            queue[tail++] = m;
         }
      }
      return tail;
   }
}
