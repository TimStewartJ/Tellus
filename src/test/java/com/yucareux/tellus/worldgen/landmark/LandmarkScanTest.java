package com.yucareux.tellus.worldgen.landmark;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in diagnostic (see {@code tools/landmark-scan}).
 *
 * <p>{@code TELLUS_LANDMARK_SCAN} is one of
 * <ul>
 *    <li>{@code overture|gameDir|lat|lon|radiusMeters}: lists the Overture buildings around a point (landmark
 *        suppression applied, so a landmark's own buildings no longer show)</li>
 *    <li>{@code roads|gameDir|lat|lon|radiusMeters}: lists the map's bridge roads around a point and which ones a
 *        landmark carries itself</li>
 *    <li>{@code voxel|gameDir|landmarkId|scale}: builds the landmark's blocks (on flat ground, or at its elevation for
 *        sea-level landmarks) and renders a top view (with the outlines of every Overture building near it, suppressed
 *        ones in red, and the map's roads, carried bridge roads in yellow), side views and LOD side views to
 *        {@code TELLUS_LANDMARK_SCAN_OUT}</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "TELLUS_LANDMARK_SCAN", matches = ".+")
class LandmarkScanTest {
   private static final int GROUND = 100;
   private static final int SEA_LEVEL = 63;

   @Test
   void scan() throws Exception {
      String[] spec = System.getenv("TELLUS_LANDMARK_SCAN").split("[|]");
      System.setProperty("tellus.gameDir", Path.of(spec[1]).toAbsolutePath().toString());
      System.setProperty("tellus.configDir", Path.of(spec[1]).resolve("config").toAbsolutePath().toString());
      Path out = Path.of(System.getenv().getOrDefault("TELLUS_LANDMARK_SCAN_OUT", "build/landmark-scan"));
      Files.createDirectories(out);
      switch (spec[0]) {
         case "overture" -> dumpOverture(Double.parseDouble(spec[2]), Double.parseDouble(spec[3]), Double.parseDouble(spec[4]));
         case "roads" -> dumpRoads(Double.parseDouble(spec[2]), Double.parseDouble(spec[3]), Double.parseDouble(spec[4]));
         case "voxel" -> voxel(spec[2], Double.parseDouble(spec[3]), out);
         default -> throw new IllegalArgumentException(spec[0]);
      }
   }

   private static List<OsmBuildingFeature> overture(double lat, double lon, double radius) {
      TellusOsmBuildingSource source = new TellusOsmBuildingSource();
      int x = (int)Math.floor(EarthProjection.longitudeToBlockX(lon, 1.0));
      int z = (int)Math.floor(EarthProjection.latToBlockZ(lat, 1.0));
      int r = (int)Math.ceil(radius * EarthProjection.heightScaleCorrectionAtLatitude(lat));
      List<OsmBuildingFeature> features = source.buildingsForAreaWithStatus(x - r, z - r, x + r, z + r, 1.0, 0, OsmQueryMode.BLOCKING).features();
      return features;
   }

   private static void dumpOverture(double lat, double lon, double radius) {
      List<OsmBuildingFeature> features = overture(lat, lon, radius);
      System.out.printf(Locale.ROOT, "LANDMARKSCAN_OVERTURE features=%d (after landmark suppression)%n", features.size());
      List<Long> kept = new ArrayList<>();
      for (OsmBuildingFeature f : features) {
         kept.add(f.featureId());
      }
      // Every building, the ones a landmark replaces marked.
      for (OsmBuildingFeature f : LandmarkCatalog.withoutSuppression(() -> overture(lat, lon, radius))) {
         double dx = (f.centroidLon() - lon) * 111319.49 * Math.cos(Math.toRadians(lat)), dz = (f.centroidLat() - lat) * 111319.49;
         System.out.printf(Locale.ROOT, "LANDMARKSCAN_FEATURE %s kind=%s fid=%d bid=%s h=%.1f minh=%.1f area=%.0f centroid=(%.1fE,%.1fN m) name=%s%n",
            kept.contains(f.featureId()) ? "kept" : "REPLACED", f.kind(), f.featureId(), f.buildingId(), f.heightMeters(), f.minHeightMeters(),
            f.areaSquareMeters(), dx, dz, f.metadata().name());
      }
   }

   private static List<RoadFeature> roads(double lat, double lon, double radius, double scale) {
      TellusOsmRoadSource source = new TellusOsmRoadSource();
      int x = (int)Math.floor(EarthProjection.longitudeToBlockX(lon, scale));
      int z = (int)Math.floor(EarthProjection.latToBlockZ(lat, scale));
      int r = (int)Math.ceil(radius * EarthProjection.heightScaleCorrectionAtLatitude(lat) / scale);
      return source.roadsForAreaWithStatus(x - r, z - r, x + r, z + r, scale, 0, OsmQueryMode.BLOCKING).features();
   }

   /** The map's bridge roads around a point, and which of them a landmark carries. */
   private static void dumpRoads(double lat, double lon, double radius) {
      List<RoadFeature> all = LandmarkCatalog.withoutSuppression(() -> roads(lat, lon, radius, 1.0));
      for (RoadFeature road : all) {
         if (road.mode() != RoadMode.BRIDGE) {
            continue;
         }
         double first = (road.latAt(0) - lat) * 111319.49, last = (road.latAt(road.pointCount() - 1) - lat) * 111319.49;
         System.out.printf(Locale.ROOT, "LANDMARKSCAN_ROAD way=%d class=%s tag=%s points=%d north=%.0f..%.0f m replaced=%b%n",
            road.wayId(), road.roadClass(), road.highwayTag(), road.pointCount(), first, last, LandmarkCatalog.replacesRoad(road));
      }
   }

   private static void voxel(String id, double scale, Path out) throws Exception {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      TellusLandmarks landmarks = TellusLandmarks.create(scale, true, (x, z) -> GROUND);
      LandmarkPlacement placement = landmarks.placements().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
      long started = System.nanoTime();
      LandmarkVolume volume = placement.volume();
      double millis = (System.nanoTime() - started) / 1e6;
      int solidColumns = 0;
      long blocks = 0;
      for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
         for (int x = volume.minX(); x <= volume.maxX(); x++) {
            int column = volume.column(x, z);
            if (column < 0) {
               continue;
            }
            if (volume.endSpan(column) > volume.firstSpan(column)) {
               solidColumns++;
            }
            for (int s = volume.firstSpan(column); s < volume.endSpan(column); s++) {
               blocks += volume.spanTop(s) - volume.spanBottom(s) + 1;
            }
         }
      }
      LandmarkDefinition definition = placement.definition();
      double anchorX = EarthProjection.longitudeToBlockX(definition.longitude(), scale);
      double anchorZ = EarthProjection.latToBlockZ(definition.latitude(), scale);
      int base = placement.baseY();
      System.out.printf(Locale.ROOT,
         "LANDMARKSCAN_VOXEL id=%s scale=%.2f built=%.0fms size=%dx%dx%d heightBlocks=%.1f blocksPerMeter=%.4f spans=%d columns=%d blocks=%d anchor=(%.1f,%.1f) base=%d%n",
         id, scale, millis, volume.maxX() - volume.minX() + 1, volume.height(), volume.maxZ() - volume.minZ() + 1, placement.heightBlocks(),
         placement.heightBlocks() / placement.meshHeightMeters(), volume.spanCount(), solidColumns, blocks, anchorX, anchorZ, base);

      // Top view: highest block per column, with every Overture building outline and map road near the landmark.
      int pad = 40;
      int width = volume.maxX() - volume.minX() + 1 + pad * 2, depth = volume.maxZ() - volume.minZ() + 1 + pad * 2;
      int zoom = Math.max(1, 900 / Math.max(width, depth));
      BufferedImage top = new BufferedImage(width * zoom, depth * zoom, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = top.createGraphics();
      g.setColor(new Color(0x2A3A2A));
      g.fillRect(0, 0, top.getWidth(), top.getHeight());
      for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
         for (int x = volume.minX(); x <= volume.maxX(); x++) {
            int column = volume.column(x, z);
            if (column >= 0 && volume.endSpan(column) > volume.firstSpan(column)) {
               int end = volume.endSpan(column);
               float t = volume.spanTop(end - 1) / (float)volume.height();
               g.setColor(shade(stateColor(placement.materialState(volume.spanMaterial(end - 1))), 0.55F + 0.45F * t));
               g.fillRect((x - volume.minX() + pad) * zoom, (z - volume.minZ() + pad) * zoom, zoom, zoom);
            } else if (placement.standsOnTerrain() && placement.insideBase(x, z, 0.0)) {
               g.setColor(new Color(0x56605A));
               g.fillRect((x - volume.minX() + pad) * zoom, (z - volume.minZ() + pad) * zoom, zoom, zoom);
            }
         }
      }
      double radius = placement.meshRadiusMeters() + 60.0;
      g.setStroke(new BasicStroke(Math.max(1.0F, zoom * 0.5F)));
      if (scale <= 15.0) {
         List<OsmBuildingFeature> kept = overture(definition.latitude(), definition.longitude(), radius);
         List<Long> keptIds = new ArrayList<>();
         for (OsmBuildingFeature f : kept) {
            keptIds.add(f.featureId());
         }
         // The raw query: suppression off for this read, to draw what the landmark replaces.
         List<OsmBuildingFeature> all = LandmarkCatalog.withoutSuppression(() -> overture(definition.latitude(), definition.longitude(), radius));
         int suppressed = 0;
         for (OsmBuildingFeature f : all) {
            boolean isKept = keptIds.contains(f.featureId());
            if (!isKept) {
               suppressed++;
            }
            g.setColor(isKept ? new Color(0x6FB0FF) : new Color(0xFF4040));
            for (int part = 0; part < f.partCount(); part++) {
               int n = f.pointCount(part);
               int[] px = new int[n], pz = new int[n];
               for (int p = 0; p < n; p++) {
                  px[p] = (int)Math.round((EarthProjection.longitudeToBlockX(f.lonAt(part, p), scale) - volume.minX() + pad) * zoom);
                  pz[p] = (int)Math.round((EarthProjection.latToBlockZ(f.latAt(part, p), scale) - volume.minZ() + pad) * zoom);
               }
               g.drawPolygon(px, pz, n);
            }
         }
         System.out.printf(Locale.ROOT, "LANDMARKSCAN_SUPPRESSION overture=%d suppressed=%d kept=%d%n", all.size(), suppressed, kept.size());
      }
      // Map roads: kept ones in white, the ones the landmark carries (left out of the world) in yellow.
      List<RoadFeature> mapRoads = LandmarkCatalog.withoutSuppression(() -> roads(definition.latitude(), definition.longitude(), radius, scale));
      List<RoadFeature> keptRoads = LandmarkCatalog.withoutReplacedRoads(mapRoads);
      int carried = 0;
      for (RoadFeature road : mapRoads) {
         boolean replaced = !keptRoads.contains(road);
         carried += replaced ? 1 : 0;
         g.setColor(replaced ? new Color(0xFFE040) : new Color(0xF0F0F0));
         int n = road.pointCount();
         int[] px = new int[n], pz = new int[n];
         for (int p = 0; p < n; p++) {
            px[p] = (int)Math.round((EarthProjection.longitudeToBlockX(road.lonAt(p), scale) - volume.minX() + pad) * zoom);
            pz[p] = (int)Math.round((EarthProjection.latToBlockZ(road.latAt(p), scale) - volume.minZ() + pad) * zoom);
         }
         g.drawPolyline(px, pz, n);
      }
      System.out.printf(Locale.ROOT, "LANDMARKSCAN_ROADS carried=%d%n", carried);
      g.setColor(Color.WHITE);
      g.fillOval((int)((anchorX - volume.minX() + pad) * zoom) - 3, (int)((anchorZ - volume.minZ() + pad) * zoom) - 3, 6, 6);
      g.dispose();
      ImageIO.write(top, "png", out.resolve(id + "-top.png").toFile());

      // Side views looking north (x across) and looking west (z across); near rows paint over far ones.
      for (boolean alongZ : new boolean[]{false, true}) {
         int across = alongZ ? volume.maxZ() - volume.minZ() + 1 : volume.maxX() - volume.minX() + 1;
         int sideZoom = Math.max(1, 1600 / Math.max(across, volume.height()));
         BufferedImage side = new BufferedImage(across * sideZoom, (volume.height() + 2) * sideZoom, BufferedImage.TYPE_INT_RGB);
         Graphics2D s = side.createGraphics();
         s.setColor(new Color(0x9CC8F0));
         s.fillRect(0, 0, side.getWidth(), side.getHeight());
         int seaLayer = SEA_LEVEL - base;
         if (seaLayer >= 0 && seaLayer < volume.height()) {
            s.setColor(new Color(0x2E5E9E));
            s.fillRect(0, (volume.height() - seaLayer) * sideZoom, side.getWidth(), (seaLayer + 2) * sideZoom);
         }
         int firstRow = alongZ ? volume.minX() : volume.maxZ();
         int lastRow = alongZ ? volume.maxX() : volume.minZ();
         int step = alongZ ? 1 : -1;
         for (int row = firstRow; row != lastRow + step; row += step) {
            for (int a = 0; a < across; a++) {
               int column = alongZ ? volume.column(row, volume.minZ() + a) : volume.column(volume.minX() + a, row);
               if (column < 0) {
                  continue;
               }
               for (int span = volume.firstSpan(column); span < volume.endSpan(column); span++) {
                  s.setColor(stateColor(placement.materialState(volume.spanMaterial(span))));
                  int y0 = volume.spanBottom(span), y1 = volume.spanTop(span);
                  s.fillRect(a * sideZoom, (volume.height() - y1) * sideZoom, sideZoom, (y1 - y0 + 1) * sideZoom);
               }
            }
         }
         s.dispose();
         ImageIO.write(side, "png", out.resolve(id + (alongZ ? "-side-west.png" : "-side.png")).toFile());
      }

      // LOD cost: every cell of every 64-cell LOD tile that touches the landmark, per cell size (after a warm-up pass).
      for (int pass = 0; pass < 2; pass++) {
         StringBuilder timings = new StringBuilder();
         for (int cell : new int[]{1, 2, 4, 8, 16, 32, 64}) {
            int tile = cell * 64;
            long cellStarted = System.nanoTime();
            long[] runs = new long[1];
            int tiles = 0;
            for (int tz = Math.floorDiv(volume.minZ(), tile) * tile; tz <= volume.maxZ(); tz += tile) {
               for (int tx = Math.floorDiv(volume.minX(), tile) * tile; tx <= volume.maxX(); tx += tile) {
                  tiles++;
                  for (int cz = 0; cz < 64; cz++) {
                     for (int cx = 0; cx < 64; cx++) {
                        placement.lodCellSpans(tx + cx * cell, tz + cz * cell, cell, -2048, 4096, (bottomY, topY, state) -> runs[0]++);
                     }
                  }
               }
            }
            timings.append(String.format(Locale.ROOT, " cell%d=%.2fms/%dtiles/%druns", cell, (System.nanoTime() - cellStarted) / 1e6, tiles, runs[0]));
         }
         if (pass == 1) {
            System.out.println("LANDMARKSCAN_LODCOST" + timings);
         }
      }

      // LOD side views along the landmark's longer side, every row of cells across it drawn over the others.
      boolean alongZ = volume.maxZ() - volume.minZ() > volume.maxX() - volume.minX();
      for (int cell : new int[]{2, 4, 8, 16, 32, 64}) {
         int firstFixed = Math.floorDiv(alongZ ? volume.minX() : volume.minZ(), cell) * cell;
         int lastFixed = alongZ ? volume.maxX() : volume.maxZ();
         int first = Math.floorDiv(alongZ ? volume.minZ() : volume.minX(), cell) * cell;
         int cells = ((alongZ ? volume.maxZ() : volume.maxX()) - first) / cell + 1;
         int lodZoom = Math.max(1, 1600 / Math.max(cells * cell, volume.height()));
         BufferedImage lod = new BufferedImage(cells * cell * lodZoom, (volume.height() + 2) * lodZoom, BufferedImage.TYPE_INT_RGB);
         Graphics2D l = lod.createGraphics();
         l.setColor(new Color(0x9CC8F0));
         l.fillRect(0, 0, lod.getWidth(), lod.getHeight());
         long[] count = new long[1];
         for (int c = 0; c < cells; c++) {
            int along = first + c * cell;
            int drawX = c * cell * lodZoom;
            LandmarkPlacement.SpanSink sink = (bottomY, topY, state) -> {
               l.setColor(stateColor(state));
               l.fillRect(drawX, (volume.height() - (topY - base)) * lodZoom, cell * lodZoom, (topY - bottomY + 1) * lodZoom);
               count[0]++;
            };
            for (int fixed = firstFixed; fixed <= lastFixed; fixed += cell) {
               if (alongZ) {
                  placement.lodCellSpans(fixed, along, cell, -2048, 4096, sink);
               } else {
                  placement.lodCellSpans(along, fixed, cell, -2048, 4096, sink);
               }
            }
         }
         l.dispose();
         ImageIO.write(lod, "png", out.resolve(id + "-lod" + cell + ".png").toFile());
         System.out.printf(Locale.ROOT, "LANDMARKSCAN_LOD cell=%d spans=%d%n", cell, count[0]);
      }
   }

   private static Color stateColor(BlockState state) {
      return new Color(state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col);
   }

   private static Color shade(Color color, float light) {
      return new Color(Math.min(255, (int)(color.getRed() * light)), Math.min(255, (int)(color.getGreen() * light)), Math.min(255, (int)(color.getBlue() * light)));
   }
}
