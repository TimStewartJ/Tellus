package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The drawn waterways of the world as one directed network with solved water levels, in large world-aligned
 * tiles. A tile reads the map well beyond its edges (further than any level depends on), so the levels it
 * publishes for its core, and for a reach around it, are exactly those any neighbouring tile computes for
 * the same cells: there are no seams and no chunk-order effects.
 *
 * <p>Flow direction is the drawn direction of the map lines (waterways are mapped downstream), except where the
 * terrain rises clearly along a narrow waterway's line, which is then reversed (see {@link #downstream}). Levels come
 * from {@link RiverProfileSolver} on terrain observed along the lines at {@link RiverScale#NETWORK_TERRAIN_METERS}.</p>
 */
final class RiverNetwork {
   /** How far beyond a tile's core its levels are still authoritative: the channel and bank reach of a plan. */
   static final int PUBLISHED_REACH = 160;
   private final InlandWaterInput source;
   private final RiverScale scale;
   private final InlandHydrologyPlanner.TerrainSampler sampler;
   private final InlandHydrologyPlanner.OutletSampler outlets;
   /** Planned levels of the lakes waterways run into (null for coarse levels, which do not profile runs). */
   private final LakeLevels lakeLevels;
   private final int cellSize;
   private final int tileSize;
   private final int margin;
   private final RiverProfileSolver.Parameters parameters;
   // Detail level: 0 for blocks and near LODs, each further level doubles the cell and keeps only waterways
   // wide enough to show at that cell size.
   private final int level;
   private final double terrainMeters;
   private final int minTileWidthBlocks;
   private final double minDrawnBlocks;
   private final Cache<Long, Tile> tiles = CacheBuilder.newBuilder().maximumSize(48).build();
   // Courses drawn through river areas no waterway reaches, per water body: shared by every detail level.
   private final Cache<Long, List<AreaCourse>> areaCourses;
   private final AtomicReferenceArray<Tile> recent = new AtomicReferenceArray<>(16);

   RiverNetwork(InlandWaterInput source, double worldScale, InlandHydrologyPlanner.TerrainSampler sampler,
      InlandHydrologyPlanner.OutletSampler outlets) {
      this(source, worldScale, sampler, outlets, null);
   }

   RiverNetwork(InlandWaterInput source, double worldScale, InlandHydrologyPlanner.TerrainSampler sampler,
      InlandHydrologyPlanner.OutletSampler outlets, LakeLevels lakeLevels) {
      this(source, worldScale, sampler, outlets, 0, lakeLevels);
   }

   RiverNetwork(InlandWaterInput source, double worldScale, InlandHydrologyPlanner.TerrainSampler sampler,
      InlandHydrologyPlanner.OutletSampler outlets, int level) {
      this(source, worldScale, sampler, outlets, level, null, CacheBuilder.newBuilder().maximumSize(1024).build());
   }

   private RiverNetwork(InlandWaterInput source, double worldScale, InlandHydrologyPlanner.TerrainSampler sampler,
      InlandHydrologyPlanner.OutletSampler outlets, int level, LakeLevels lakeLevels) {
      this(source, worldScale, sampler, outlets, level, lakeLevels, CacheBuilder.newBuilder().maximumSize(1024).build());
   }

   private RiverNetwork(InlandWaterInput source, double worldScale, InlandHydrologyPlanner.TerrainSampler sampler,
      InlandHydrologyPlanner.OutletSampler outlets, int level, LakeLevels lakeLevels, Cache<Long, List<AreaCourse>> areaCourses) {
      this.source = source;
      this.areaCourses = areaCourses;
      this.scale = new RiverScale(worldScale);
      this.sampler = sampler;
      this.outlets = outlets;
      this.lakeLevels = lakeLevels;
      this.level = level;
      double cellMeters = RiverScale.NETWORK_CELL_METERS * (1L << level);
      this.cellSize = level == 0 ? scale.networkCellSize() : Math.max(1, (int)Math.round(cellMeters / scale.scale));
      double carve = scale.blocks(RiverScale.CARVE_METERS), fill = scale.blocks(RiverScale.FILL_METERS);
      this.parameters = new RiverProfileSolver.Parameters(0.0, Math.max(cellSize * (level == 0 ? 4.0 : 8.0), carve),
         Math.max(cellSize * 2.0, fill));
      int tileCells = level == 0 ? Math.max(16, Integer.highestOneBit((int)Math.max(1.0, scale.blocks(8192.0) / cellSize))) : 128;
      this.tileSize = Math.max(512, tileCells * cellSize);
      int reach = level == 0 ? PUBLISHED_REACH : Math.max(PUBLISHED_REACH, cellSize * 4);
      this.margin = (int)Math.ceil(parameters.reach()) + reach + cellSize * 4;
      // A coarse level reads the terrain model at about the resolution of the LODs it serves, which have its tiles
      // loaded already; a finer read would decode several times as many tiles for levels no LOD shows in detail.
      this.terrainMeters = Math.max(scale.scale, Math.max(RiverScale.NETWORK_TERRAIN_METERS, level == 0 ? 0.0 : cellMeters * 2.0));
      this.minTileWidthBlocks = level == 0 ? 0 : cellSize * 64;
      this.minDrawnBlocks = level == 0 ? 0.0 : cellSize;
   }

   // River area levels per plan (two by two for block plans). Each reads the map around itself; block plans also move narrow
   // areas with the courses of the tile asking, which every tile holding the same waterways draws the same way.
   private final Cache<Long, AreaSurface> areaSurfaces = CacheBuilder.newBuilder().maximumSize(32).build();
   // Neighbouring plans read the same large outlines; project each once.
   private final Cache<OsmWaterFeature, HydrologyWaterMap.Shape> projectedShapes = CacheBuilder.newBuilder().maximumSize(4096).build();

   /** Whether a coarse level draws a river area: at least half its cells wide, as it draws only waterways that wide. */
   boolean drawnCoarse(HydrologyWaterMap.Shape area) {
      return area.width() >= cellSize * 0.5;
   }

   private HydrologyWaterMap.Shape projected(OsmWaterFeature feature) {
      try {
         return projectedShapes.get(feature, () -> new HydrologyWaterMap.Shape(feature, scale.scale));
      } catch (ExecutionException error) {
         throw new IllegalStateException("Unable to project a river area", error.getCause());
      }
   }

   /** Terrain for coarse plans: the model at this level's resolution, as the coarse network reads it. */
   private final RiverChannels.Terrain coarseTerrain = new RiverChannels.Terrain() {
      @Override
      public double height(int x, int z) {
         return sampler.sampleAtResolution(x, z, terrainMeters).height();
      }

      @Override
      public double resolution(int x, int z) {
         return Math.max(1.0, terrainMeters / scale.scale);
      }
   };

   /**
    * The river area levels of plan {@code (ptx, ptz)} of {@code planSize} blocks. Block plans pass the native terrain;
    * coarse plans pass none and are levelled on a grid of this level's cell size from the terrain at its resolution.
    * Block plans draw narrow river areas moved with the courses {@code tile} draws for their waterways, so each tile
    * has its own; coarse plans draw every area as mapped, the same for every tile.
    */
   AreaSurface areaSurface(Tile tile, int ptx, int ptz, int planSize, RiverChannels.Terrain terrain) {
      // Block plans are levelled two by two: each alone would level the wide margin it shares with its neighbours again.
      // The margin makes the levels the same either way.
      int share = terrain != null ? 2 : 1;
      int sx = Math.floorDiv(ptx, share), sz = Math.floorDiv(ptz, share), size = planSize * share;
      long key = HydrologyBlockCache.mix(HydrologyWaterMap.key(sx, sz)) ^ size * 0x9E3779B97F4A7C15L ^ share;
      if (terrain != null) key ^= HydrologyBlockCache.mix(HydrologyWaterMap.key(tile.tx, tile.tz) ^ 0x5851F42D4C957F2DL);
      try {
         return areaSurfaces.get(key, () -> buildAreaSurface(tile, sx, sz, size, terrain));
      } catch (ExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw new IllegalStateException("Unable to level river areas", error.getCause());
      } catch (com.google.common.util.concurrent.UncheckedExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw error;
      }
   }

   private AreaSurface buildAreaSurface(Tile tile, int ptx, int ptz, int planSize, RiverChannels.Terrain terrain) {
      long started = HydrologyMetrics.now();
      boolean blocks = terrain != null;
      int grid = blocks ? AreaSurface.GRID : Math.max(AreaSurface.GRID, cellSize);
      // Block plans also shape the banks of the water just beyond them; coarse plans draw only their own cells.
      int reach = blocks ? RiverChannels.areaReach() : grid;
      int x0 = ptx * planSize - reach, z0 = ptz * planSize - reach;
      int x1 = (ptx + 1) * planSize - 1 + reach, z1 = (ptz + 1) * planSize - 1 + reach;
      int pad = AreaSurface.reads(grid, scale.scale);
      // Block plans move narrow river areas with their waterways' courses, and read the map as much further as those moved.
      List<AreaSurface.Carrier> carriers = blocks ? tile.carriers(x0 - pad, z0 - pad, x1 + pad, z1 + pad, terrain) : List.of();
      pad += (int)Math.ceil(AreaSurface.drift(carriers));
      TellusOsmWaterSource.WaterQueryResult query = minTileWidthBlocks == 0
         ? source.waterForAreaWithStatus(x0 - pad, z0 - pad, x1 + pad, z1 + pad, scale.scale, 0, OsmQueryMode.BLOCKING)
         : source.waterForAreaAtTileWidth(x0 - pad, z0 - pad, x1 + pad, z1 + pad, scale.scale, minTileWidthBlocks);
      if (!query.complete()) throw new OceanCoverageUnavailableException(query.coverageStatus(), x0, z0);
      List<HydrologyWaterMap.Shape> areas = new ArrayList<>(), untyped = new ArrayList<>();
      List<double[]> flowing = new ArrayList<>();
      for (OsmWaterFeature feature : query.features()) {
         if (feature.oceanHint() || feature.pointGeometry()) continue;
         // Only flowing water, falls and water of no particular kind can be a river area.
         if (!feature.flowingWater() && !feature.waterfall() && feature.kind() != OsmWaterKind.UNKNOWN) continue;
         HydrologyWaterMap.Shape shape = projected(feature);
         if (feature.lineGeometry()) {
            if (feature.flowingWater()) flowing.add(new double[]{shape.minX, shape.minZ, shape.maxX, shape.maxZ});
            continue;
         }
         if (!blocks && !drawnCoarse(shape)) continue;
         if (shape.river) {
            areas.add(shape);
            flowing.add(new double[]{shape.minX, shape.minZ, shape.maxX, shape.maxZ});
         } else if (lakeLevels != null && feature.kind() == OsmWaterKind.UNKNOWN) untyped.add(shape);
      }
      // Water of no particular kind is a river reach only where river water reaches it; a lake elsewhere is not
      // surveyed for that.
      for (HydrologyWaterMap.Shape shape : untyped) {
         boolean touches = false;
         for (double[] box : flowing)
            if (box[2] >= shape.minX - 2.0 && box[0] <= shape.maxX + 2.0 && box[3] >= shape.minZ - 2.0 && box[1] <= shape.maxZ + 2.0) { touches = true; break; }
         if (touches && lakeLevels.flowing(shape)) areas.add(shape);
      }
      AreaSurface surface = new AreaSurface(areas, x0, z0, x1, z1, blocks ? terrain : coarseTerrain, grid, scale.scale, carriers);
      HydrologyMetrics.plan(started);
      return surface;
   }

   /** A coarse network (detail {@code level}) over the same map, sharing the courses drawn through river areas. */
   RiverNetwork coarse(int level) {
      return new RiverNetwork(source, scale.scale, sampler, outlets, level, null, areaCourses);
   }

   RiverScale scale() { return scale; }
   int level() { return level; }
   int cellSize() { return cellSize; }
   int tileSize() { return tileSize; }

   void clear() {
      tiles.invalidateAll();
      for (int i = 0; i < recent.length(); i++) recent.set(i, null);
   }

   /** The solved tile whose core holds a block position. */
   Tile tileAt(int x, int z) {
      return tile(Math.floorDiv(x, tileSize), Math.floorDiv(z, tileSize));
   }

   Tile tile(int tx, int tz) {
      long key = HydrologyBlockCache.mix(HydrologyWaterMap.key(tx, tz));
      int slot = (int)(key >>> 60);
      Tile cached = recent.get(slot);
      if (cached != null && cached.tx == tx && cached.tz == tz) return cached;
      try {
         cached = tiles.get(key, () -> build(tx, tz));
      } catch (ExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw new IllegalStateException("Unable to build the river network", error.getCause());
      } catch (com.google.common.util.concurrent.UncheckedExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw error;
      }
      recent.set(slot, cached);
      return cached;
   }

   private Tile build(int tx, int tz) {
      long started = HydrologyMetrics.now();
      int coreMinX = tx * tileSize, coreMinZ = tz * tileSize;
      int minX = coreMinX - margin, minZ = coreMinZ - margin, maxX = coreMinX + tileSize - 1 + margin, maxZ = coreMinZ + tileSize - 1 + margin;
      TellusOsmWaterSource.WaterQueryResult query = minTileWidthBlocks == 0
         ? source.waterForAreaWithStatus(minX, minZ, maxX, maxZ, scale.scale, 0, OsmQueryMode.BLOCKING)
         : source.waterForAreaAtTileWidth(minX, minZ, maxX, maxZ, scale.scale, minTileWidthBlocks);
      if (!query.complete()) throw new OceanCoverageUnavailableException(query.coverageStatus(), coreMinX, coreMinZ);
      List<HydrologyWaterMap.Shape> riverAreas = new ArrayList<>(), oceans = new ArrayList<>(), lakes = new ArrayList<>();
      List<OsmWaterFeature> candidates = new ArrayList<>();
      for (OsmWaterFeature feature : query.features()) {
         if (feature.oceanHint() && !feature.lineGeometry() && !feature.pointGeometry()) {
            oceans.add(new HydrologyWaterMap.Shape(feature, scale.scale));
            continue;
         }
         if (feature.oceanHint() || feature.pointGeometry()) continue;
         // A mapped waterfall area is water over the drop (some maps omit the river area there); a waterfall line
         // or point only annotates the drop, which the levels find in the terrain.
         if (feature.waterfall() && feature.lineGeometry()) continue;
         if (feature.lineGeometry()) {
            if (feature.flowingWater()) candidates.add(feature);
         } else {
            // Coarse levels only need river areas; lakes are outlined only where waterways run into them.
            if (lakeLevels == null && !feature.flowingWater() && !feature.waterfall()) continue;
            HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, scale.scale);
            if (shape.river) riverAreas.add(shape);
            else lakes.add(shape);
         }
      }
      // Fixed order: the cells, samples and widths must not depend on the order tiles were read in.
      candidates.sort(Comparator.comparingLong(OsmWaterFeature::featureId).thenComparingDouble(OsmWaterFeature::minLon)
         .thenComparingDouble(OsmWaterFeature::minLat));
      RiverGraph.Builder builder = new RiverGraph.Builder(cellSize);
      List<Line> lines = new ArrayList<>();
      double bpd = EarthProjection.blocksPerDegree(scale.scale);
      for (OsmWaterFeature feature : candidates) {
         HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, scale.scale);
         double widthMeters = widthMeters(feature, shape, riverAreas);
         double z = (shape.minZ + shape.maxZ) * 0.5;
         if (!scale.visible(feature.kind(), widthMeters, z)) continue;
         // A coarse level draws only waterways that cover a good part of its cells.
         if (minDrawnBlocks > 0.0 && widthMeters / scale.metersPerBlock(z) * (RiverScale.minor(feature.kind())
            ? RiverScale.STREAM_EXAGGERATION : RiverScale.RIVER_EXAGGERATION) < minDrawnBlocks) continue;
         OsmWaterFeature drawn = downstream(feature, shape, widthMeters, z);
         if (drawn != feature) { feature = drawn; shape = new HydrologyWaterMap.Shape(feature, scale.scale); }
         double halfWidth = scale.halfWidth(widthMeters, z);
         double clipMinX = Double.NEGATIVE_INFINITY, clipMinZ = Double.NEGATIVE_INFINITY;
         double clipMaxX = Double.POSITIVE_INFINITY, clipMaxZ = Double.POSITIVE_INFINITY;
         if (feature.hasTileBounds()) {
            clipMinX = feature.tileWest() * bpd; clipMaxX = feature.tileEast() * bpd;
            clipMinZ = EarthProjection.latToBlockZ(feature.tileNorth(), scale.scale);
            clipMaxZ = EarthProjection.latToBlockZ(feature.tileSouth(), scale.scale);
         }
         boolean inArea = insideRiverArea(shape, riverAreas);
         // Inside a mapped river area the area itself is the water; the line only draws a channel where it
         // leaves the area, about as wide as such a waterway usually is. A width read from river areas holds only
         // inside them: drawn outside, a river kilometres wide ended in a disc of water as wide over the land.
         double channel = inArea || !Double.isFinite(feature.widthMeters())
            ? Math.min(halfWidth, scale.halfWidth(RiverScale.defaultWidthMeters(feature.kind()), z)) : halfWidth;
         for (int p = 0; p < shape.xs.length; p++)
            builder.addLine(shape.xs[p], shape.zs[p], feature.featureId(), channel, clipMinX, clipMinZ, clipMaxX, clipMaxZ);
         double channelMeters = Math.min(widthMeters, channel * 2.0 * scale.metersPerBlock(z));
         lines.add(new Line(feature, shape, widthMeters, halfWidth, channel, scale.depth(channelMeters, channel, z),
            clipMinX, clipMinZ, clipMaxX, clipMaxZ, inArea, false));
      }
      // A river area much of whose water no waterway reaches (a river mapped only as an area near its mouth, a side
      // arm) gets courses through it, drawn from the whole water body so every tile draws the same ones. Blocks and
      // near LODs only: a coarse level spans far more map than the detail these courses are drawn from.
      it.unimi.dsi.fastutil.longs.LongOpenHashSet drawnAreas = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      if (level == 0) for (HydrologyWaterMap.Shape area : riverAreas) {
         long id = area.feature.featureId();
         if (id == -1L || !area.feature.flowingWater() || area.feature.waterfall() || drawnAreas.contains(id) || reached(area, lines)) continue;
         drawnAreas.add(id);
         List<AreaCourse> courses = cachedCourses(area.feature);
         for (int k = 0; k < courses.size(); k++) {
            AreaCourse course = courses.get(k);
            double[] lons = new double[course.xs().length], lats = new double[lons.length];
            for (int i = 0; i < lons.length; i++) { lons[i] = course.xs()[i] / bpd; lats[i] = EarthProjection.blockZToLat(course.zs()[i], scale.scale); }
            OsmWaterFeature feature = new OsmWaterFeature(HydrologyBlockCache.mix(id * 31L + k) | Long.MIN_VALUE >>> 1, true, false,
               OsmWaterKind.RIVER, new double[][]{lons}, new double[][]{lats});
            HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, scale.scale);
            double z = (shape.minZ + shape.maxZ) * 0.5;
            if (!scale.visible(OsmWaterKind.RIVER, course.widthMeters(), z)) continue;
            if (minDrawnBlocks > 0.0 && course.widthMeters() / scale.metersPerBlock(z) * RiverScale.RIVER_EXAGGERATION < minDrawnBlocks) continue;
            double halfWidth = scale.halfWidth(course.widthMeters(), z);
            double channel = Math.min(halfWidth, scale.halfWidth(RiverScale.defaultWidthMeters(OsmWaterKind.RIVER), z));
            builder.addLine(shape.xs[0], shape.zs[0], feature.featureId(), channel, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
               Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            lines.add(new Line(feature, shape, course.widthMeters(), halfWidth, channel, scale.depth(course.widthMeters(), halfWidth, z),
               Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, true, true));
         }
      }
      RiverGraph graph = builder.build();
      double[] ground = new double[graph.count], fixed = new double[graph.count];
      Arrays.fill(fixed, Double.NaN);
      observe(graph, ground, fixed, oceans);
      double[] level = RiverProfileSolver.solve(graph, ground, fixed, parameters);
      // Water bodies a waterway may run into and take the level of: lakes and the sea. A river area is never one:
      // its levels come from the terrain (AreaSurface), however much of it a drawn waterway reaches.
      List<BaseWater> bases = new ArrayList<>();
      if (lakeLevels != null) {
         for (HydrologyWaterMap.Shape lake : lakes) bases.add(new BaseWater(lake, false));
         for (HydrologyWaterMap.Shape ocean : oceans) bases.add(new BaseWater(ocean, true));
      }
      Tile tile = new Tile(tx, tz, coreMinX, coreMinZ, tileSize, scale, graph, level, ground, List.copyOf(lines), List.copyOf(riverAreas),
         bases, this, minX, minZ, maxX, maxZ);
      HydrologyMetrics.network(started);
      return tile;
   }

   /**
    * Terrain under the lines of each cell: per piece of line, the lowest of its midpoint and points across it
    * (the map line is not always on the valley floor), then the median (coarse levels: lower quartile) of the cell's
    * pieces. Cells near the edge of the read area have incomplete pieces; they are far beyond what any published
    * level reads.
    */
   private void observe(RiverGraph graph, double[] ground, double[] fixed, List<HydrologyWaterMap.Shape> oceans) {
      int[] xs = new int[graph.sampleX.length], zs = new int[xs.length];
      double[] values = new double[RiverGraph.MAX_SAMPLES];
      for (int s = 0; s < graph.sampleX.length; s++) { xs[s] = Math.round(graph.sampleX[s]); zs[s] = Math.round(graph.sampleZ[s]); }
      sampler.preloadAtResolution(xs, zs, xs.length, terrainMeters);
      // Only cells near the sea can reach the coastal band, whose water levels are fixed outlets.
      double coast = COAST_REACH + cellSize;
      for (int c = 0; c < graph.count; c++) {
         int from = graph.sampleStart[c], to = graph.sampleStart[c + 1];
         // Across the channel only: a wider search reaches the gorge beside a fall's crest and drops the level
         // before the lip. A line drawn beside its valley floor is lowered later, along its own course; the
         // network may err high, never low.
         double across = Math.min(graph.halfWidth[c], cellSize);
         int n = 0;
         for (int s = from; s < to; s++) {
            double x = graph.sampleX[s], z = graph.sampleZ[s];
            double best = sampler.sampleAtResolution((int)Math.round(x), (int)Math.round(z), terrainMeters).height();
            for (int side = -1; side <= 1; side += 2) {
               double px = x - graph.sampleDirZ[s] * across * side, pz = z + graph.sampleDirX[s] * across * side;
               double h = sampler.sampleAtResolution((int)Math.round(px), (int)Math.round(pz), terrainMeters).height();
               if (Double.isFinite(h) && (!Double.isFinite(best) || h < best)) best = h;
            }
            if (Double.isFinite(best)) values[n++] = best;
         }
         if (n == 0) { ground[c] = Double.NaN; continue; }
         Arrays.sort(values, 0, n);
         // The level stands for the middle of the cell. On a steep course the lowest pieces lie at the cell's lower
         // end, a good part of a cell's drop below its middle, and a profile on native terrain can only lower the
         // network's level: blocks then take the (upper) median, coarse levels the lower quartile they draw as is.
         ground[c] = level == 0 ? values[n / 2] : values[(n - 1) / 4];
         double cx = graph.centerX(c), cz = graph.centerZ(c);
         boolean nearSea = false;
         for (HydrologyWaterMap.Shape ocean : oceans) {
            if (ocean.near(cx, cz, coast) && (ocean.contains(cx, cz) || ocean.within(cx, cz, coast))) { nearSea = true; break; }
         }
         if (!nearSea) continue;
         int outlet = outlets.waterLevel((int)Math.floor(cx), (int)Math.floor(cz));
         // A level is a ground height with the water a block below it; the sea's surface is its water block. The level
         // is the middle of that block, so levels settling on it never flip to the block above or below.
         if (outlet != Integer.MIN_VALUE) fixed[c] = outlet + WATER_BODY_LEVEL;
      }
   }

   /**
    * The level of a lake or the sea above its top water block: the middle of the block above it, where waterways
    * running into it settle without rounding to a neighbouring block.
    */
   static final double WATER_BODY_LEVEL = 0.5;

   /** Distance from the sea within which the established coastal band may hold a river's mouth. */
   private static final double COAST_REACH = 96.0;

   /** A course drawn through a river area, in flow order, and the real width of the water along it. */
   record AreaCourse(double[] xs, double[] zs, double widthMeters) {}

   /** How far beyond its water's reach an area takes the level of a waterway (the area grid's nearest search). */
   private static final double AREA_REACH_SLACK = 64.0;
   /** Largest gap between a waterway's end and the river area it runs into or out of, ground metres. */
   private static final double TOUCH_METERS = 64.0;
   /** Most map tiles a river area may span for courses to be drawn through it. */
   private static final int COURSE_BODY_TILES = 4;

   /**
    * Whether drawn waterways inside a river area reach nearly all of this piece of it: points on a grid over it
    * lie within the reach the area's surface takes levels from.
    */
   private static boolean reached(HydrologyWaterMap.Shape area, List<Line> lines) {
      List<Line> inside = new ArrayList<>();
      for (Line line : lines) {
         HydrologyWaterMap.Shape shape = line.shape();
         if (line.inRiverArea() && shape.maxX >= area.minX && shape.minX <= area.maxX && shape.maxZ >= area.minZ && shape.minZ <= area.maxZ) inside.add(line);
      }
      double spacing = Math.max(16.0, Math.min(64.0, Math.sqrt(Math.max(1.0, area.area()) / 64.0)));
      int points = 0, unreached = 0;
      for (double z = area.minZ + spacing * 0.5; z <= area.maxZ; z += spacing)
         for (double x = area.minX + spacing * 0.5; x <= area.maxX; x += spacing) {
            if (!area.contains(x, z)) continue;
            points++;
            boolean near = false;
            for (Line line : inside) {
               double reach = line.halfWidth() * 1.25 + 16.0 + AREA_REACH_SLACK;
               if (line.shape().near(x, z, reach) && line.shape().distance(x, z, reach) <= reach) { near = true; break; }
            }
            if (!near) unreached++;
         }
      return unreached <= Math.max(1, points / 20);
   }

   private List<AreaCourse> cachedCourses(OsmWaterFeature seed) {
      try {
         return areaCourses.get(seed.featureId(), () -> computeCourses(seed));
      } catch (ExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw new IllegalStateException("Unable to draw courses through a river area", error.getCause());
      } catch (com.google.common.util.concurrent.UncheckedExecutionException error) {
         if (error.getCause() instanceof RuntimeException runtime) throw runtime;
         throw error;
      }
   }

   /**
    * The courses through a whole river area: its water body assembled from every map tile, and the waterways and sea
    * around it, read for the body alone (never for a tile), so every tile meeting it gets the same courses.
    */
   private List<AreaCourse> computeCourses(OsmWaterFeature seed) {
      // Only a body of a few map tiles: assembling a long river's whole area would read (and download) far more of
      // the map than any LOD around it needs.
      TellusOsmWaterSource.WaterQueryResult body = source.connectedWaterBodyWithin(seed, scale.scale, COURSE_BODY_TILES);
      if (!body.complete()) throw new OceanCoverageUnavailableException(body.coverageStatus(), 0, 0);
      if (body.features().isEmpty()) return List.of();
      List<HydrologyWaterMap.Shape> shapes = new ArrayList<>();
      double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
      for (OsmWaterFeature feature : body.features()) {
         HydrologyWaterMap.Shape shape = new HydrologyWaterMap.Shape(feature, scale.scale);
         shapes.add(shape);
         minX = Math.min(minX, shape.minX); minZ = Math.min(minZ, shape.minZ); maxX = Math.max(maxX, shape.maxX); maxZ = Math.max(maxZ, shape.maxZ);
      }
      int margin = 64;
      TellusOsmWaterSource.WaterQueryResult around = source.waterForAreaWithStatus((int)Math.floor(minX) - margin, (int)Math.floor(minZ) - margin,
         (int)Math.ceil(maxX) + margin, (int)Math.ceil(maxZ) + margin, scale.scale, 0, OsmQueryMode.BLOCKING);
      if (!around.complete()) throw new OceanCoverageUnavailableException(around.coverageStatus(), (int)minX, (int)minZ);
      List<HydrologyWaterMap.Shape> oceans = new ArrayList<>();
      List<double[][]> covering = new ArrayList<>();
      it.unimi.dsi.fastutil.doubles.DoubleArrayList reach = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
      it.unimi.dsi.fastutil.booleans.BooleanArrayList drains = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
      List<double[]> inflows = new ArrayList<>(), outflows = new ArrayList<>();
      List<OsmWaterFeature> features = new ArrayList<>(around.features());
      features.sort(Comparator.comparingLong(OsmWaterFeature::featureId).thenComparingDouble(OsmWaterFeature::minLon)
         .thenComparingDouble(OsmWaterFeature::minLat));
      double bpd = EarthProjection.blocksPerDegree(scale.scale);
      // Maps often leave a gap of some tens of metres between a waterway's end and the river area it runs into.
      double touch = Math.max(16.0, TOUCH_METERS / scale.metersPerBlock((minZ + maxZ) * 0.5));
      for (OsmWaterFeature feature : features) {
         if (feature.oceanHint() && !feature.lineGeometry() && !feature.pointGeometry()) { oceans.add(new HydrologyWaterMap.Shape(feature, scale.scale)); continue; }
         if (!feature.lineGeometry() || !feature.flowingWater() || feature.waterfall() || feature.oceanHint()) continue;
         HydrologyWaterMap.Shape line = new HydrologyWaterMap.Shape(feature, scale.scale);
         double widthMeters = widthMeters(feature, line, shapes);
         double z = (line.minZ + line.maxZ) * 0.5;
         if (!scale.visible(feature.kind(), widthMeters, z)) continue;
         // Which way its water runs in or out, as the network draws it.
         OsmWaterFeature drawn = downstream(feature, line, widthMeters, z);
         if (drawn != feature) { feature = drawn; line = new HydrologyWaterMap.Shape(feature, scale.scale); }
         // Only the part inside the line's own map tile: the buffer beyond it repeats a neighbour's piece, and its
         // cut ends would read as ends of the waterway.
         for (double[][] piece : ownPieces(feature, line, bpd)) {
            double[] xs = piece[0], zs = piece[1];
            int last = xs.length - 1;
            boolean starts = trueEnd(feature, xs[0], zs[0], bpd) && touches(shapes, xs[0], zs[0], touch);
            boolean ends = trueEnd(feature, xs[last], zs[last], bpd) && touches(shapes, xs[last], zs[last], touch);
            if (ends) inflows.add(new double[]{xs[last], zs[last]});
            if (insidePiece(xs, zs, shapes)) {
               // A waterway drawn in the area reaches the water around it; the water drains into it unless it ends
               // in the area (a tributary drawn some way in), where it only adds water. Where its pieces begin
               // inside says nothing about where the water leaves.
               covering.add(piece);
               reach.add(scale.halfWidth(widthMeters, z) * 1.25 + 16.0 + AREA_REACH_SLACK);
               drains.add(!ends);
            } else if (starts) outflows.add(new double[]{xs[0], zs[0]});
         }
      }
      List<double[][]> courses = RiverAreaCourses.courses(shapes, covering, reach.toDoubleArray(), drains.toBooleanArray(), inflows, outflows,
         oceans, touch, (x, z) -> sampler.sampleAtResolution((int)Math.round(x), (int)Math.round(z), terrainMeters).height());
      List<AreaCourse> result = new ArrayList<>(courses.size());
      for (double[][] course : courses) {
         // The water's real width along the course: the median of a few transects of the whole area.
         double[] widths = new double[16];
         int n = 0;
         double[] xs = course[0], zs = course[1];
         int step = Math.max(1, (xs.length - 1) / 8);
         for (int i = 0; i + 1 < xs.length && n < widths.length; i += step) {
            double dx = xs[i + 1] - xs[i], dz = zs[i + 1] - zs[i], length = Math.hypot(dx, dz);
            if (length < 1.0E-6) continue;
            double blocks = transect(shapes, (xs[i] + xs[i + 1]) * 0.5, (zs[i] + zs[i + 1]) * 0.5, -dz / length, dx / length);
            if (blocks > 0.0) widths[n++] = blocks * scale.metersPerBlock((zs[i] + zs[i + 1]) * 0.5);
         }
         Arrays.sort(widths, 0, n);
         double width = n == 0 ? RiverScale.defaultWidthMeters(OsmWaterKind.RIVER) : Math.max(RiverScale.defaultWidthMeters(OsmWaterKind.RIVER), widths[n / 2]);
         result.add(new AreaCourse(xs, zs, width));
      }
      return List.copyOf(result);
   }

   /** The pieces of a line inside its own map tile (all of it without tile bounds), as {xs, zs}. */
   private List<double[][]> ownPieces(OsmWaterFeature feature, HydrologyWaterMap.Shape shape, double bpd) {
      double minX = Double.NEGATIVE_INFINITY, minZ = Double.NEGATIVE_INFINITY, maxX = Double.POSITIVE_INFINITY, maxZ = Double.POSITIVE_INFINITY;
      if (feature.hasTileBounds()) {
         minX = feature.tileWest() * bpd; maxX = feature.tileEast() * bpd;
         minZ = EarthProjection.latToBlockZ(feature.tileNorth(), scale.scale); maxZ = EarthProjection.latToBlockZ(feature.tileSouth(), scale.scale);
      }
      List<double[][]> pieces = new ArrayList<>();
      List<double[]> points = new ArrayList<>();
      for (int p = 0; p < shape.xs.length; p++) {
         double[] xs = shape.xs[p], zs = shape.zs[p];
         for (int i = 1; i < xs.length; i++) {
            double[] t = RiverRun.clip(xs[i - 1], zs[i - 1], xs[i], zs[i], minX, minZ, maxX, maxZ);
            if (t == null) { addPiece(points, pieces); continue; }
            double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1];
            if (t[0] > 0.0) addPiece(points, pieces);
            if (points.isEmpty()) points.add(new double[]{xs[i - 1] + dx * t[0], zs[i - 1] + dz * t[0]});
            points.add(new double[]{xs[i - 1] + dx * t[1], zs[i - 1] + dz * t[1]});
            if (t[1] < 1.0) addPiece(points, pieces);
         }
         addPiece(points, pieces);
      }
      return pieces;
   }

   private static void addPiece(List<double[]> points, List<double[][]> pieces) {
      if (points.size() >= 2) {
         double[] xs = new double[points.size()], zs = new double[points.size()];
         for (int i = 0; i < xs.length; i++) { xs[i] = points.get(i)[0]; zs[i] = points.get(i)[1]; }
         pieces.add(new double[][]{xs, zs});
      }
      points.clear();
   }

   /** Whether most of a piece of line lies inside the areas. */
   private static boolean insidePiece(double[] xs, double[] zs, List<HydrologyWaterMap.Shape> areas) {
      int inside = 0, probes = 0;
      for (int i = 0; i < xs.length; i += Math.max(1, xs.length / 6)) {
         probes++;
         if (containsAny(areas, xs[i], zs[i])) inside++;
      }
      return inside * 2 > probes;
   }

   /** Whether a line's end point is its own end, not where its map tile clipped it. */
   private boolean trueEnd(OsmWaterFeature feature, double x, double z, double bpd) {
      if (!feature.hasTileBounds()) return true;
      double north = EarthProjection.latToBlockZ(feature.tileNorth(), scale.scale), south = EarthProjection.latToBlockZ(feature.tileSouth(), scale.scale);
      return x > feature.tileWest() * bpd + 0.5 && x < feature.tileEast() * bpd - 0.5 && z > north + 0.5 && z < south - 0.5;
   }

   /** Whether a point lies in an area or within {@code distance} of it. */
   private static boolean touches(List<HydrologyWaterMap.Shape> shapes, double x, double z, double distance) {
      for (HydrologyWaterMap.Shape shape : shapes) if (shape.near(x, z, distance) && (shape.contains(x, z) || shape.within(x, z, distance))) return true;
      return false;
   }

   /**
    * Elevation model cells a line must span for its drawn direction to be judged from the terrain, and below which it
    * is short: a short line must rise more steadily and more steeply, as a few cells of forest canopy or a bank can
    * make one look as if it rose.
    */
   private static final int FLOW_CELLS = 4, FLOW_LONG_CELLS = 16;
   /** Most terrain samples a line's direction is judged from. */
   private static final int FLOW_SAMPLES = 48;
   /** How steadily the ground must rise along a line drawn upstream (long, short): Kendall's tau of its samples in drawn order. */
   private static final double FLOW_TREND = 0.6, FLOW_SHORT_TREND = 0.8;
   /** How far it must rise from its first quarter to its last (long, short): blocks per block of line, and at least {@link #FLOW_RISE}. */
   private static final double FLOW_GRADE = 0.01, FLOW_SHORT_GRADE = 0.1;
   private static final double FLOW_RISE = 4.0;

   /**
    * A waterway as the network draws it: each part of its line whose ground rises clearly and steadily (a line mapped
    * upstream; waterways are mapped downstream) reversed, so its levels are not solved uphill. Only waterways no coarse
    * level draws are judged, from the terrain blocks read: every tile then draws them the same way, while the wider
    * waterways the coarse levels draw from coarser terrain keep their mapped direction everywhere.
    */
   private OsmWaterFeature downstream(OsmWaterFeature feature, HydrologyWaterMap.Shape shape, double widthMeters, double z) {
      if (level != 0 || shape.engineered) return feature;
      double exaggeration = RiverScale.minor(feature.kind()) ? RiverScale.STREAM_EXAGGERATION : RiverScale.RIVER_EXAGGERATION;
      // The cell of the first coarse level, which draws waterways at least this wide (see the constructor and build).
      int coarseCell = Math.max(1, (int)Math.round(RiverScale.NETWORK_CELL_METERS * 2.0 / scale.scale));
      if (widthMeters / scale.metersPerBlock(z) * exaggeration >= coarseCell) return feature;
      boolean[] flip = null;
      for (int p = 0; p < shape.xs.length; p++) {
         if (!uphill(shape.xs[p], shape.zs[p], z)) continue;
         if (flip == null) flip = new boolean[shape.xs.length];
         flip[p] = true;
      }
      return flip == null ? feature : feature.reversed(flip);
   }

   /** Whether the ground under a polyline rises clearly and steadily from its first point to its last. */
   private boolean uphill(double[] xs, double[] zs, double z) {
      double cell = Math.max(1.0, terrainMeters / scale.metersPerBlock(z));
      double length = PieceEnds.length(xs, zs);
      if (length < FLOW_CELLS * cell) return false;
      boolean brief = length < FLOW_LONG_CELLS * cell;
      int n = (int)Math.min(FLOW_SAMPLES, Math.floor(length / cell) + 1);
      double[] ground = new double[n];
      int i = 1;
      double before = 0.0, span = Math.hypot(xs[1] - xs[0], zs[1] - zs[0]);
      for (int k = 0; k < n; k++) {
         double s = length * k / (n - 1);
         while (i < xs.length - 1 && before + span < s) { before += span; i++; span = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]); }
         double t = span > 1.0E-9 ? Math.max(0.0, Math.min(1.0, (s - before) / span)) : 0.0;
         double dx = span > 1.0E-9 ? (xs[i] - xs[i - 1]) / span : 0.0, dz = span > 1.0E-9 ? (zs[i] - zs[i - 1]) / span : 0.0;
         double px = xs[i - 1] + (xs[i] - xs[i - 1]) * t, pz = zs[i - 1] + (zs[i] - zs[i - 1]) * t;
         // The lowest of the line and the ground a cell to either side: a line drawn beside its valley floor.
         double low = Double.POSITIVE_INFINITY;
         for (int side = -1; side <= 1; side++) {
            double h = sampler.sampleAtResolution((int)Math.round(px - dz * cell * side), (int)Math.round(pz + dx * cell * side), terrainMeters).height();
            if (Double.isFinite(h)) low = Math.min(low, h);
         }
         if (low == Double.POSITIVE_INFINITY) return false;
         ground[k] = low;
      }
      long trend = 0;
      for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) {
         double rise = ground[b] - ground[a];
         if (rise > 0.5) trend++;
         else if (rise < -0.5) trend--;
      }
      if (trend < (brief ? FLOW_SHORT_TREND : FLOW_TREND) * n * (n - 1) * 0.5) return false;
      int quarter = Math.max(1, n / 4);
      double[] first = Arrays.copyOfRange(ground, 0, quarter), last = Arrays.copyOfRange(ground, n - quarter, n);
      Arrays.sort(first);
      Arrays.sort(last);
      return last[quarter / 2] - first[quarter / 2] >= Math.max(FLOW_RISE, (brief ? FLOW_SHORT_GRADE : FLOW_GRADE) * length);
   }

   /** The real width of a waterway: its tag, else the mapped river area it runs in, else its kind's usual width. */
   private double widthMeters(OsmWaterFeature feature, HydrologyWaterMap.Shape shape, List<HydrologyWaterMap.Shape> riverAreas) {
      if (Double.isFinite(feature.widthMeters())) return feature.widthMeters();
      double fallback = RiverScale.defaultWidthMeters(feature.kind());
      if (riverAreas.isEmpty()) return fallback;
      double[] widths = new double[16];
      int n = 0, probes = 0;
      for (int p = 0; p < shape.xs.length && probes < 16; p++) {
         double[] px = shape.xs[p], pz = shape.zs[p];
         int step = Math.max(1, px.length / 8);
         for (int i = 0; i + 1 < px.length && probes < 16; i += step) {
            probes++;
            double mx = (px[i] + px[i + 1]) * 0.5, mz = (pz[i] + pz[i + 1]) * 0.5;
            double dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i], length = Math.hypot(dx, dz);
            if (length < 1.0E-6) continue;
            double blocks = transect(riverAreas, mx, mz, -dz / length, dx / length);
            if (blocks > 0.0) widths[n++] = blocks * scale.metersPerBlock(mz);
         }
      }
      // A line mostly outside any river area keeps its own width.
      if (n * 2 < probes || n == 0) return fallback;
      Arrays.sort(widths, 0, n);
      return Math.max(fallback, widths[n / 2]);
   }

   /** Water width across a point inside river areas (0 when outside), searching at most a few kilometres. */
   static double transect(List<HydrologyWaterMap.Shape> areas, double x, double z, double nx, double nz) {
      if (!containsAny(areas, x, z)) return 0.0;
      double limit = 6000.0, total = 0.0;
      for (int side = -1; side <= 1; side += 2) {
         double step = 0.5, d = 0.0;
         // Coarse steps out, then halving back to the shore.
         while (d < limit && containsAny(areas, x + nx * (d + step) * side, z + nz * (d + step) * side)) { d += step; step = Math.min(64.0, step * 2.0); }
         while (step > 0.25) {
            step *= 0.5;
            if (containsAny(areas, x + nx * (d + step) * side, z + nz * (d + step) * side)) d += step;
         }
         total += d;
      }
      return total;
   }

   static boolean containsAny(List<HydrologyWaterMap.Shape> areas, double x, double z) {
      for (HydrologyWaterMap.Shape area : areas) if (area.contains(x, z)) return true;
      return false;
   }

   private static boolean insideRiverArea(HydrologyWaterMap.Shape line, List<HydrologyWaterMap.Shape> areas) {
      if (areas.isEmpty()) return false;
      int inside = 0, probes = 0;
      for (int p = 0; p < line.xs.length; p++)
         for (int i = 0; i < line.xs[p].length; i += Math.max(1, line.xs[p].length / 6)) {
            probes++;
            if (containsAny(areas, line.xs[p][i], line.zs[p][i])) inside++;
         }
      return inside * 2 > probes;
   }

   /** Half-width of the water a waterway's course carries: its river area's where it lies in one, else its channel's. */
   static double routedHalfWidth(Line line) {
      return line.inRiverArea() ? line.halfWidth() : line.channelHalfWidth();
   }

   /** The planned water surface of a lake, or {@link Integer#MIN_VALUE} when it has none (unplanned, incomplete, no water). */
   interface LakeLevels {
      int level(HydrologyWaterMap.Shape lake);

      /** Whether water mapped as a lake is levelled as a river area instead (it is far from flat in the terrain). */
      default boolean flowing(HydrologyWaterMap.Shape lake) {
         return false;
      }
   }

   /** A lake or the sea that waterways may run into; a lake's level is planned on first use. */
   static final class BaseWater {
      final HydrologyWaterMap.Shape shape;
      final boolean sea;
      volatile double level = Double.NaN;

      BaseWater(HydrologyWaterMap.Shape shape, boolean sea) { this.shape = shape; this.sea = sea; }
   }

   /**
    * A drawn waterway of a tile: its geometry, width and the bounds of the map tile its geometry is valid in. An
    * {@code areaCourse} is drawn through a river area no waterway reaches; it keeps to the area, which keeps its outline.
    */
   record Line(OsmWaterFeature feature, HydrologyWaterMap.Shape shape, double widthMeters, double halfWidth, double channelHalfWidth, int depth,
      double clipMinX, double clipMinZ, double clipMaxX, double clipMaxZ, boolean inRiverArea, boolean areaCourse) {
      boolean engineered() {
         OsmWaterKind kind = feature.kind();
         return kind == OsmWaterKind.CANAL || kind == OsmWaterKind.DITCH || kind == OsmWaterKind.DRAIN;
      }
   }

   /** A solved tile: the network around it, levels per cell, and the waterways and river areas it read. */
   static final class Tile {
      final int tx, tz, coreMinX, coreMinZ, size;
      final RiverScale scale;
      final RiverGraph graph;
      final double[] level, ground;
      final List<Line> lines;
      final List<HydrologyWaterMap.Shape> riverAreas;

      // Lakes and sea a waterway may run into, binned by position over the area the tile read (null when none,
      // as for coarse levels), and the network that plans their levels.
      private final BaseWater[][] bases;
      private final int baseMinX, baseMinZ, baseColumns, baseRows;
      private final RiverNetwork network;
      private static final int BASE_BIN = 256;

      Tile(int tx, int tz, int coreMinX, int coreMinZ, int size, RiverScale scale, RiverGraph graph, double[] level, double[] ground,
         List<Line> lines, List<HydrologyWaterMap.Shape> riverAreas,
         List<BaseWater> bases, RiverNetwork network, int minX, int minZ, int maxX, int maxZ) {
         this.tx = tx; this.tz = tz; this.coreMinX = coreMinX; this.coreMinZ = coreMinZ; this.size = size; this.scale = scale;
         this.graph = graph; this.level = level; this.ground = ground; this.lines = lines; this.riverAreas = riverAreas;
         this.network = network;
         this.baseMinX = Math.floorDiv(minX, BASE_BIN); this.baseMinZ = Math.floorDiv(minZ, BASE_BIN);
         this.baseColumns = Math.floorDiv(maxX, BASE_BIN) - baseMinX + 1; this.baseRows = Math.floorDiv(maxZ, BASE_BIN) - baseMinZ + 1;
         if (bases.isEmpty()) { this.bases = null; return; }
         List<List<BaseWater>> lists = new ArrayList<>(baseColumns * baseRows);
         for (int i = 0; i < baseColumns * baseRows; i++) lists.add(null);
         for (BaseWater water : bases) {
            HydrologyWaterMap.Shape shape = water.shape;
            int x0 = Math.max(baseMinX, (int)Math.floor(shape.minX / BASE_BIN)), x1 = Math.min(baseMinX + baseColumns - 1, (int)Math.floor(shape.maxX / BASE_BIN));
            int z0 = Math.max(baseMinZ, (int)Math.floor(shape.minZ / BASE_BIN)), z1 = Math.min(baseMinZ + baseRows - 1, (int)Math.floor(shape.maxZ / BASE_BIN));
            for (int bz = z0; bz <= z1; bz++) for (int bx = x0; bx <= x1; bx++) {
               int index = (bz - baseMinZ) * baseColumns + bx - baseMinX;
               List<BaseWater> list = lists.get(index);
               if (list == null) lists.set(index, list = new ArrayList<>(2));
               list.add(water);
            }
         }
         this.bases = new BaseWater[baseColumns * baseRows][];
         for (int i = 0; i < this.bases.length; i++) if (lists.get(i) != null) this.bases[i] = lists.get(i).toArray(new BaseWater[0]);
      }

      boolean hasBaseWater() { return bases != null; }

      /** Whether a position lies inside one of the river areas this tile read, as mapped. */
      boolean insideRiverArea(double x, double z) {
         for (HydrologyWaterMap.Shape area : riverAreas) if (area.near(x, z, 0.0) && area.contains(x, z)) return true;
         return false;
      }

      /**
       * Whether a position lies inside one of the river areas this tile read, for block plans as they are drawn: a narrow
       * area moved with the course of its waterway nearest the position (as {@link AreaSurface} moves it, without its
       * levels).
       */
      boolean insideRiverArea(double x, double z, RiverChannels.Terrain terrain) {
         if (terrain == null) return insideRiverArea(x, z);
         double best = Double.POSITIVE_INFINITY, sx = 0.0, sz = 0.0;
         for (PieceEnds.Course piece : ends().near(x, z)) {
            if (!piece.line().inRiverArea()) continue;
            RiverRun.Course course = courses(piece.line(), terrain)[piece.index()];
            if (course == null || course.mappedX() == course.xs()) continue;
            double[] xs = course.xs(), zs = course.zs(), mx = course.mappedX(), mz = course.mappedZ();
            for (int i = 1; i < xs.length; i++) {
               double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1], squared = dx * dx + dz * dz;
               double t = squared > 1.0E-18 ? Math.max(0.0, Math.min(1.0, ((x - xs[i - 1]) * dx + (z - zs[i - 1]) * dz) / squared)) : 0.0;
               double ex = x - xs[i - 1] - dx * t, ez = z - zs[i - 1] - dz * t, d = ex * ex + ez * ez;
               if (d >= best) continue;
               best = d;
               sx = (xs[i - 1] - mx[i - 1]) * (1.0 - t) + (xs[i] - mx[i]) * t;
               sz = (zs[i - 1] - mz[i - 1]) * (1.0 - t) + (zs[i] - mz[i]) * t;
            }
         }
         return insideRiverArea(x - sx, z - sz);
      }

      /** The river area level at a position (block plans' levels), NaN where none reaches. */
      double areaLevel(double x, double z, RiverChannels.Terrain terrain) {
         int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
         AreaSurface surface = areaSurface(Math.floorDiv(bx, RiverChannels.TILE), Math.floorDiv(bz, RiverChannels.TILE), RiverChannels.TILE, terrain);
         return surface == null ? Double.NaN : surface.level(x, z, Math.ceil(terrain.height(bx, bz)));
      }

      /** The river area levels of plan {@code (ptx, ptz)} of {@code planSize} blocks (see {@link RiverNetwork#areaSurface}). */
      AreaSurface areaSurface(int ptx, int ptz, int planSize, RiverChannels.Terrain terrain) {
         if (terrain == null) {
            // A coarse plan draws only the river areas this tile read; none near it leaves nothing to level.
            double reach = Math.max(AreaSurface.GRID, network.cellSize);
            double x0 = ptx * (double)planSize - reach, z0 = ptz * (double)planSize - reach;
            double x1 = (ptx + 1) * (double)planSize + reach, z1 = (ptz + 1) * (double)planSize + reach;
            boolean near = false;
            for (HydrologyWaterMap.Shape area : riverAreas)
               if (area.maxX >= x0 && area.minX <= x1 && area.maxZ >= z0 && area.minZ <= z1 && network.drawnCoarse(area)) { near = true; break; }
            if (!near) return null;
         }
         return network.areaSurface(this, ptx, ptz, planSize, terrain);
      }

      /**
       * The level (a ground height, the water a block below) of the lake or sea holding a position, NaN when none
       * does or its level is unknown. A lake is planned the first time a waterway reaches it.
       */
      double baseLevel(double x, double z) {
         return baseLevel(x, z, true);
      }

      /** {@link #baseLevel} of lakes only: NaN in the sea. */
      double lakeLevel(double x, double z) {
         return baseLevel(x, z, false);
      }

      private double baseLevel(double x, double z, boolean sea) {
         if (bases == null) return Double.NaN;
         int bx = (int)Math.floor(x / BASE_BIN) - baseMinX, bz = (int)Math.floor(z / BASE_BIN) - baseMinZ;
         if (bx < 0 || bz < 0 || bx >= baseColumns || bz >= baseRows || bases[bz * baseColumns + bx] == null) return Double.NaN;
         for (BaseWater water : bases[bz * baseColumns + bx]) {
            if (!water.shape.near(x, z, 0.0) || !water.shape.contains(x, z)) continue;
            if (water.sea) {
               if (!sea) continue;
               int surface = network.outlets.waterLevel((int)Math.floor(x), (int)Math.floor(z));
               if (surface != Integer.MIN_VALUE) return surface + WATER_BODY_LEVEL;
               continue;
            }
            double level = water.level;
            if (Double.isNaN(level)) {
               int planned = network.lakeLevels.level(water.shape);
               // Benign race: every thread computes the same planned level.
               water.level = level = planned == Integer.MIN_VALUE ? Double.NEGATIVE_INFINITY : planned + WATER_BODY_LEVEL;
            }
            if (level != Double.NEGATIVE_INFINITY) return level;
         }
         return Double.NaN;
      }

      /** Level of the cell holding a block position, NaN when no drawn line passes through it. */
      double levelAt(double x, double z) {
         int cell = graph.cellAt(x, z);
         return cell < 0 ? Double.NaN : level[cell];
      }

      private final java.util.concurrent.ConcurrentHashMap<Line, List<RiverRun>> runs = new java.util.concurrent.ConcurrentHashMap<>();
      private final java.util.concurrent.ConcurrentHashMap<Line, RiverRun[]> profiles = new java.util.concurrent.ConcurrentHashMap<>();
      private volatile PieceEnds ends;

      /**
       * The profiled pieces of a waterway, computed once per tile on first use. Each piece's water is held at most
       * at the level of every piece ending where it begins (the same waterway in the previous map tile, the way
       * before it, a tributary joining there), so the water never steps up where pieces meet.
       */
      List<RiverRun> runs(Line line, RiverChannels.Terrain terrain) {
         List<RiverRun> cached = runs.get(line);
         if (cached != null) return cached;
         RiverRun[] own = profiles(line, terrain);
         PieceEnds index = ends();
         List<double[][]> pieces = RiverRun.pieces(line);
         List<RiverRun> computed = new ArrayList<>(own.length);
         for (int i = 0; i < own.length; i++) {
            RiverRun run = own[i];
            if (run == null) continue;
            double cap = Double.POSITIVE_INFINITY;
            // Pieces meet where they are mapped to (their courses meet at the anchor there).
            for (PieceEnds.Piece before : index.endingAt(pieces.get(i)[0][0], pieces.get(i)[1][0])) {
               if (before.line() == line && before.index() == i) continue;
               RiverRun upstream = profiles(before.line(), terrain)[before.index()];
               if (upstream != null) cap = Math.min(cap, upstream.endLevel());
            }
            computed.add(run.capped(cap));
         }
         List<RiverRun> published = runs.putIfAbsent(line, List.copyOf(computed));
         return published != null ? published : runs.get(line);
      }

      private final java.util.concurrent.ConcurrentHashMap<Line, RiverRun.Course[]> courses = new java.util.concurrent.ConcurrentHashMap<>();

      /**
       * Each piece of a waterway routed on its own (null where no level reaches it), in {@link RiverRun#pieces} order.
       * Courses read no river area levels: the narrow river areas move with them (see {@link #carriers}).
       */
      RiverRun.Course[] courses(Line line, RiverChannels.Terrain terrain) {
         RiverRun.Course[] cached = courses.get(line);
         if (cached != null) return cached;
         List<double[][]> pieces = RiverRun.pieces(line);
         RiverRun.Course[] computed = new RiverRun.Course[pieces.size()];
         for (int i = 0; i < computed.length; i++) {
            double[] xs = pieces.get(i)[0], zs = pieces.get(i)[1];
            int radius = terrain == null ? 0 : corridor(line, PieceEnds.length(xs, zs), (zs[0] + zs[zs.length - 1]) * 0.5);
            double[] start = null, end = null;
            if (radius > 0) {
               start = anchor(xs[0], zs[0], terrain);
               end = anchor(xs[xs.length - 1], zs[zs.length - 1], terrain);
            }
            computed[i] = RiverRun.course(line, xs, zs, this, terrain, scale, radius, start, end);
         }
         RiverRun.Course[] published = courses.putIfAbsent(line, computed);
         return published != null ? published : computed;
      }

      /** Each piece of a waterway profiled on its own (null where no level reaches it), in {@link RiverRun#pieces} order. */
      private RiverRun[] profiles(Line line, RiverChannels.Terrain terrain) {
         RiverRun[] cached = profiles.get(line);
         if (cached != null) return cached;
         RiverRun.Course[] routed = courses(line, terrain);
         RiverRun[] computed = new RiverRun[routed.length];
         for (int i = 0; i < computed.length; i++) if (routed[i] != null) computed[i] = RiverRun.profile(routed[i], this, terrain);
         RiverRun[] published = profiles.putIfAbsent(line, computed);
         return published != null ? published : computed;
      }

      /**
       * Corridor half-width of a piece of a waterway (see {@link RiverCorridor#radius}); zero where it keeps its mapped
       * course: engineered channels, waterways in river areas too wide to move (the area is the water, and the terrain
       * model shows its surface), courses drawn through areas no waterway reaches (no mapped line says where the river
       * is) and pieces too long to be sampled finely. A narrow river area moves with its waterway.
       */
      int corridor(Line line, double length, double z) {
         if (line.engineered() || line.areaCourse() || !RiverRun.fine(length)) return 0;
         return RiverCorridor.radius(routedHalfWidth(line), scale.metersPerBlock(z), line.inRiverArea() ? RiverCorridor.MAX_AREA_RADIUS : RiverCorridor.MAX_RADIUS);
      }

      /**
       * Where the course of a piece ending (or beginning) at a mapped point must end, or null where it is free (a
       * stream's head, a line ending on land). Pieces meeting there (the same waterway in the next map tile, the next
       * way of a river, several waterways at a confluence) all end at one point: the lowest ground across them, or their
       * mapped meeting point where one of them keeps its mapped course. Waterways meeting a larger one between its ends
       * end on its course; at a lake, the sea or a river area they keep the mapped point, moved as that water is. Every
       * piece meeting there gets the same point from the same inputs, in this tile or any other holding them.
       */
      private double[] anchor(double x, double z, RiverChannels.Terrain terrain) {
         List<PieceEnds.Piece> meeting = ends().meetingAt(x, z);
         int n = meeting.size();
         if (n == 0) return null;
         double cx = 0.0, cz = 0.0;
         for (PieceEnds.Piece piece : meeting) { cx += piece.x(); cz += piece.z(); }
         cx /= n; cz /= n;
         double[] centre = {cx, cz};
         int radius = Integer.MAX_VALUE;
         PieceEnds.Piece lead = null;
         for (PieceEnds.Piece piece : meeting) {
            radius = Math.min(radius, corridor(piece.line(), piece.length(), piece.midZ()));
            if (lead == null || larger(piece.line(), lead.line())) lead = piece;
         }
         if (radius <= 0) return centre;
         // Near a river area, a lake or the sea courses keep to their lines, moved as that water is (see RiverRun#course),
         // and so do their meeting points.
         double[] held = holding(cx, cz, Math.max(RiverScale.MIN_HALF_WIDTH, routedHalfWidth(lead.line())) + RiverCorridor.HOLD, lead.line(), terrain);
         if (held != null) return new double[]{cx + held[0], cz + held[1]};
         // A larger waterway the pieces run into or out of between its own ends.
         Line receiver = null;
         PieceEnds.Course into = null;
         for (PieceEnds.Course course : ends().near(cx, cz)) {
            Line other = course.line();
            boolean largest = receiver == null || larger(other, receiver);
            for (PieceEnds.Piece piece : meeting) largest &= larger(other, piece.line());
            if (!largest) continue;
            double reach = Math.max(RiverScale.MIN_HALF_WIDTH, other.channelHalfWidth()) + PieceEnds.MEET;
            if (PieceEnds.distance(course.xs(), course.zs(), cx, cz) > reach) continue;
            receiver = other;
            into = course;
         }
         if (receiver != null) {
            RiverRun.Course course = courses(receiver, terrain)[into.index()];
            return course == null ? centre : PieceEnds.nearest(course.xs(), course.zs(), cx, cz);
         }
         if (n == 1) return null;
         // The direction of the water through the meeting point, from every piece's own.
         double tx = 0.0, tz = 0.0;
         for (PieceEnds.Piece piece : meeting) { tx += piece.tx(); tz += piece.tz(); }
         double length = Math.hypot(tx, tz);
         if (length < 0.25) { tx = lead.tx(); tz = lead.tz(); length = 1.0; }
         tx /= length; tz /= length;
         double offset = RiverCorridor.anchor(cx, cz, tx, tz, radius, routedHalfWidth(lead.line()), terrain);
         return new double[]{cx - tz * offset, cz + tx * offset};
      }

      /**
       * The water holding a course near a mapped point (see {@link RiverRun#course}): null when no lake, sea or river area
       * this tile read lies within {@code distance}; else how far that water is drawn from where it is mapped, as
       * {dx, dz}. Lakes, the sea and river areas keeping their outline are not moved; a narrow river area moves with the
       * course of its waterway. An area moving with {@code self} itself, or with a smaller waterway (which follows this
       * one in turn), holds nothing: only larger waterways' courses are read, so courses never wait on each other in a loop.
       */
      double[] holding(double x, double z, double distance, Line self, RiverChannels.Terrain terrain) {
         if (nearBase(x, z, distance)) return new double[2];
         for (HydrologyWaterMap.Shape area : riverAreas) {
            if (!area.near(x, z, distance) || !area.contains(x, z) && !area.within(x, z, distance)) continue;
            // The waterway drawn in the area nearest the point carries it there (an area may be mapped in several pieces).
            PieceEnds.Course carrier = null;
            double nearest = Double.POSITIVE_INFINITY;
            for (PieceEnds.Course course : ends().near(x, z)) {
               if (!course.line().inRiverArea()) continue;
               double[] point = PieceEnds.nearest(course.xs(), course.zs(), x, z);
               double d = Math.hypot(point[0] - x, point[1] - z);
               if (d < nearest && area.contains(point[0], point[1])) { nearest = d; carrier = course; }
            }
            if (carrier == null) return new double[2];
            if (carrier.line() == self || !larger(carrier.line(), self)) continue;
            RiverRun.Course course = courses(carrier.line(), terrain)[carrier.index()];
            return course == null ? new double[2] : shift(course, x, z);
         }
         return null;
      }

      /** How far a course has moved from its mapped line near a mapped point, as {dx, dz}. */
      private static double[] shift(RiverRun.Course course, double x, double z) {
         double[] mx = course.mappedX(), mz = course.mappedZ(), cx = course.xs(), cz = course.zs();
         if (mx == cx) return new double[2];
         double best = Double.POSITIVE_INFINITY;
         double[] out = new double[2];
         for (int i = 1; i < mx.length; i++) {
            double dx = mx[i] - mx[i - 1], dz = mz[i] - mz[i - 1], squared = dx * dx + dz * dz;
            double t = squared > 1.0E-18 ? Math.max(0.0, Math.min(1.0, ((x - mx[i - 1]) * dx + (z - mz[i - 1]) * dz) / squared)) : 0.0;
            double ex = x - mx[i - 1] - dx * t, ez = z - mz[i - 1] - dz * t, d = ex * ex + ez * ez;
            if (d >= best) continue;
            best = d;
            out[0] = (cx[i - 1] - mx[i - 1]) * (1.0 - t) + (cx[i] - mx[i]) * t;
            out[1] = (cz[i - 1] - mz[i - 1]) * (1.0 - t) + (cz[i] - mz[i]) * t;
         }
         return out;
      }

      /**
       * The courses of the waterways in river areas reaching a box, each piece with how far its vertices moved from the
       * mapped line: a narrow river area moves with its waterway's course (see {@link AreaSurface}).
       */
      List<AreaSurface.Carrier> carriers(double minX, double minZ, double maxX, double maxZ, RiverChannels.Terrain terrain) {
         List<AreaSurface.Carrier> out = new ArrayList<>();
         for (Line line : lines) {
            if (!line.inRiverArea()) continue;
            double reach = AreaSurface.carrierReach(line.halfWidth());
            HydrologyWaterMap.Shape shape = line.shape();
            double grow = reach + PieceEnds.AREA_SHIFT;
            if (shape.maxX < minX - grow || shape.minX > maxX + grow || shape.maxZ < minZ - grow || shape.minZ > maxZ + grow) continue;
            for (RiverRun.Course course : courses(line, terrain))
               if (course != null) out.add(new AreaSurface.Carrier(course.xs(), course.zs(), course.mappedX(), course.mappedZ(), reach));
         }
         return out;
      }

      /** Whether a point lies within {@code distance} of a lake or the sea this tile read. */
      private boolean nearBase(double x, double z, double distance) {
         if (bases == null) return false;
         int bx0 = (int)Math.floor((x - distance) / BASE_BIN) - baseMinX, bx1 = (int)Math.floor((x + distance) / BASE_BIN) - baseMinX;
         int bz0 = (int)Math.floor((z - distance) / BASE_BIN) - baseMinZ, bz1 = (int)Math.floor((z + distance) / BASE_BIN) - baseMinZ;
         for (int bz = Math.max(0, bz0); bz <= Math.min(baseRows - 1, bz1); bz++)
            for (int bx = Math.max(0, bx0); bx <= Math.min(baseColumns - 1, bx1); bx++) {
               BaseWater[] list = bases[bz * baseColumns + bx];
               if (list == null) continue;
               for (BaseWater water : list)
                  if (water.shape.near(x, z, distance) && (water.shape.contains(x, z) || water.shape.within(x, z, distance))) return true;
            }
         return false;
      }

      private PieceEnds ends() {
         PieceEnds index = ends;
         if (index == null) ends = index = new PieceEnds(lines);
         return index;
      }

      /**
       * The level of a larger waterway whose water holds a point (inside one of its river areas, or within its
       * channel), NaN when none does: where a tributary reaches the river it flows into. Only a larger waterway
       * receives (wider, or as wide with a lower id), so the levels never depend on each other in a loop.
       */
      double receivingLevel(Line line, double x, double z, RiverChannels.Terrain terrain) {
         // A river area holds its water wherever its outline is drawn, at the level the terrain gives it there.
         if (!line.inRiverArea() && terrain != null && insideRiverArea(x, z, terrain)) {
            int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
            double level = areaSurface(Math.floorDiv(bx, RiverChannels.TILE), Math.floorDiv(bz, RiverChannels.TILE), RiverChannels.TILE, terrain)
               .level(x, z, Math.ceil(terrain.height(bx, bz)));
            if (Double.isFinite(level)) return level;
         }
         double best = Double.POSITIVE_INFINITY, level = Double.NaN;
         int inArea = -1;
         for (PieceEnds.Course course : ends().near(x, z)) {
            Line other = course.line();
            if (!larger(other, line)) continue;
            double reach;
            if (other.inRiverArea()) {
               // A river area's water is wherever its outline is drawn, at the level of the waterways in it.
               if (inArea < 0) inArea = insideRiverArea(x, z, terrain) ? 1 : 0;
               if (inArea == 0) continue;
               reach = other.halfWidth() * 1.25 + 16.0;
            } else reach = Math.max(RiverScale.MIN_HALF_WIDTH, other.channelHalfWidth()) + 1.0;
            // Its water runs along its drawn course, which may lie a corridor away from its mapped line.
            if (PieceEnds.distance(course.xs(), course.zs(), x, z) > reach + PieceEnds.shift(other)) continue;
            RiverRun run = profiles(other, terrain)[course.index()];
            if (run == null || x < run.minX - reach || x > run.maxX + reach || z < run.minZ - reach || z > run.maxZ + reach) continue;
            double[] xs = run.xs, zs = run.zs;
            double nearest = Double.POSITIVE_INFINITY, at = 0.0;
            for (int i = 1; i < xs.length; i++) {
               double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1], length = Math.hypot(dx, dz);
               if (length > 1.0E-9) {
                  double t = Math.max(0.0, Math.min(1.0, ((x - xs[i - 1]) * dx + (z - zs[i - 1]) * dz) / (length * length)));
                  double d = Math.hypot(x - xs[i - 1] - dx * t, z - zs[i - 1] - dz * t);
                  if (d < nearest) { nearest = d; at = run.arc[i - 1] + (run.arc[i] - run.arc[i - 1]) * t; }
               }
            }
            if (nearest > reach || nearest >= best) continue;
            best = nearest;
            level = run.level(at);
         }
         return level;
      }

      /** Whether a waterway receives another: wider, or as wide with a lower id. */
      private static boolean larger(Line a, Line b) {
         double wa = Math.max(a.channelHalfWidth(), a.inRiverArea() ? a.halfWidth() : 0.0);
         double wb = Math.max(b.channelHalfWidth(), b.inRiverArea() ? b.halfWidth() : 0.0);
         return wa > wb || wa == wb && a.feature().featureId() < b.feature().featureId();
      }
   }

   /**
    * The pieces of every waterway of a tile: where each ends, to find the pieces arriving at a point, and where
    * each runs (with its channel or river area), to find the waterway a point lies in.
    */
   static final class PieceEnds {
      /** Ends closer than this (blocks) meet: the same point quantized by two map tiles, or a shared node. */
      static final double MEET = 2.0;
      /**
       * How far a drawn course may lie from its mapped line: its corridor, and a reach towards an anchor beyond its end
       * (for a waterway carrying a narrow river area, {@link #AREA_SHIFT}).
       */
      static final double SHIFT = 2.0 * RiverCorridor.MAX_RADIUS + 16.0, AREA_SHIFT = 2.0 * RiverCorridor.MAX_AREA_RADIUS + 16.0;
      private static final int BIN = 64;

      /** How far a waterway's drawn course may lie from its mapped line. */
      static double shift(Line line) {
         return line.engineered() ? 0.0 : line.inRiverArea() ? AREA_SHIFT : SHIFT;
      }
      /**
       * A mapped end of a piece: its end, or its start ({@code start}), the downstream direction of its line there, and
       * the piece's length and middle row (what its corridor depends on).
       */
      record Piece(Line line, int index, double x, double z, boolean start, double tx, double tz, double length, double midZ) {}
      record Course(Line line, int index, double[] xs, double[] zs) {}
      private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<Piece>> bins = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
      private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<Course>> courses = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

      PieceEnds(List<Line> lines) {
         for (Line line : lines) {
            List<double[][]> pieces = RiverRun.pieces(line);
            double reach = (line.inRiverArea() ? line.halfWidth() * 1.25 + 16.0 : Math.max(RiverScale.MIN_HALF_WIDTH, line.channelHalfWidth()) + 2.0) + 1.0;
            // A course moved through its corridor (a narrow river area with it) is still found from the bins around its mapped line.
            reach += shift(line);
            for (int i = 0; i < pieces.size(); i++) {
               double[] xs = pieces.get(i)[0], zs = pieces.get(i)[1];
               int last = xs.length - 1;
               double length = length(xs, zs), midZ = (zs[0] + zs[last]) * 0.5;
               double[] in = direction(xs, zs, true), out = direction(xs, zs, false);
               Piece end = new Piece(line, i, xs[last], zs[last], false, out[0], out[1], length, midZ);
               Piece start = new Piece(line, i, xs[0], zs[0], true, in[0], in[1], length, midZ);
               bins.computeIfAbsent(key(end.x(), end.z()), k -> new ArrayList<>(2)).add(end);
               bins.computeIfAbsent(key(start.x(), start.z()), k -> new ArrayList<>(2)).add(start);
               Course course = new Course(line, i, xs, zs);
               it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
               for (int k = 1; k < xs.length; k++) {
                  int x0 = (int)Math.floor((Math.min(xs[k - 1], xs[k]) - reach) / BIN), x1 = (int)Math.floor((Math.max(xs[k - 1], xs[k]) + reach) / BIN);
                  int z0 = (int)Math.floor((Math.min(zs[k - 1], zs[k]) - reach) / BIN), z1 = (int)Math.floor((Math.max(zs[k - 1], zs[k]) + reach) / BIN);
                  for (int bz = z0; bz <= z1; bz++) for (int bx = x0; bx <= x1; bx++) {
                     long bin = RiverGraph.key(bx, bz);
                     if (touched.add(bin)) courses.computeIfAbsent(bin, b -> new ArrayList<>(2)).add(course);
                  }
               }
            }
         }
      }

      /** The pieces whose course and channel (or river area) may reach a point. */
      List<Course> near(double x, double z) {
         List<Course> found = courses.get(RiverGraph.key((int)Math.floor(x / BIN), (int)Math.floor(z / BIN)));
         return found == null ? List.of() : found;
      }

      private static long key(double x, double z) {
         return RiverGraph.key((int)Math.floor(x / (MEET * 2.0)), (int)Math.floor(z / (MEET * 2.0)));
      }

      /** The pieces ending within {@link #MEET} of a point. */
      List<Piece> endingAt(double x, double z) {
         List<Piece> found = null;
         for (Piece piece : meetingAt(x, z)) {
            if (piece.start()) continue;
            if (found == null) found = new ArrayList<>(2);
            found.add(piece);
         }
         return found == null ? List.of() : found;
      }

      /**
       * The pieces ending or starting within {@link #MEET} of a point, in an order independent of how lines were read:
       * by position, then waterway, piece and end.
       */
      List<Piece> meetingAt(double x, double z) {
         List<Piece> found = null;
         int bx = (int)Math.floor(x / (MEET * 2.0)), bz = (int)Math.floor(z / (MEET * 2.0));
         for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            List<Piece> bin = bins.get(RiverGraph.key(bx + dx, bz + dz));
            if (bin == null) continue;
            for (Piece piece : bin) {
               if (Math.hypot(piece.x() - x, piece.z() - z) > MEET) continue;
               if (found == null) found = new ArrayList<>(2);
               found.add(piece);
            }
         }
         if (found == null) return List.of();
         found.sort(MEETING_ORDER);
         return found;
      }

      private static final Comparator<Piece> MEETING_ORDER = Comparator.comparingDouble(Piece::x).thenComparingDouble(Piece::z)
         .thenComparingLong(p -> p.line().feature().featureId()).thenComparingInt(Piece::index).thenComparing(Piece::start);

      static double length(double[] xs, double[] zs) {
         double total = 0.0;
         for (int i = 1; i < xs.length; i++) total += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
         return total;
      }

      /** Downstream direction of a polyline at its start or end, over its first or last few blocks. */
      private static double[] direction(double[] xs, double[] zs, boolean atStart) {
         int n = xs.length;
         double ax = atStart ? xs[0] : xs[n - 1], az = atStart ? zs[0] : zs[n - 1], walked = 0.0;
         double bx = ax, bz = az;
         for (int k = 1; k < n && walked < 16.0; k++) {
            int i = atStart ? k : n - 1 - k, j = atStart ? k - 1 : n - k;
            walked += Math.hypot(xs[i] - xs[j], zs[i] - zs[j]);
            bx = xs[i]; bz = zs[i];
         }
         double dx = atStart ? bx - ax : ax - bx, dz = atStart ? bz - az : az - bz, length = Math.hypot(dx, dz);
         return length < 1.0E-9 ? new double[]{0.0, 0.0} : new double[]{dx / length, dz / length};
      }

      /** Distance from a point to a polyline. */
      static double distance(double[] xs, double[] zs, double x, double z) {
         double[] p = nearest(xs, zs, x, z);
         return Math.hypot(p[0] - x, p[1] - z);
      }

      /** The point of a polyline nearest a point. */
      static double[] nearest(double[] xs, double[] zs, double x, double z) {
         double best = Double.POSITIVE_INFINITY, px = xs[0], pz = zs[0];
         for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1], squared = dx * dx + dz * dz;
            double t = squared > 1.0E-18 ? Math.max(0.0, Math.min(1.0, ((x - xs[i - 1]) * dx + (z - zs[i - 1]) * dz) / squared)) : 0.0;
            double qx = xs[i - 1] + dx * t, qz = zs[i - 1] + dz * t, d = Math.hypot(x - qx, z - qz);
            if (d < best) { best = d; px = qx; pz = qz; }
         }
         return new double[]{px, pz};
      }
   }
}
