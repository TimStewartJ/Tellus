package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.Mth;

/**
 * Finds the mapped watercourse centreline nearest to a block, for operator diagnostics and automated
 * playtests: where a stream will run, how wide Tellus makes it, and which way it flows.
 */
public final class WatercourseLocator {
   /** How many downstream vertices of the line are reported after the nearest point. */
   private static final int PATH_POINTS = 24;

   private WatercourseLocator() {
   }

   /**
    * A centreline watercourse near a block.
    *
    * @param kind mapped kind (river, stream, canal, ...)
    * @param featureId Overture feature id
    * @param nearestX block X of the nearest point on the line
    * @param nearestZ block Z of the nearest point on the line
    * @param distanceBlocks distance from the query block to that point
    * @param widthBlocks channel width Tellus generates for this line
    * @param headingX downstream unit direction at the nearest point (mapped line order), X component
    * @param headingZ downstream unit direction at the nearest point, Z component
    * @param lengthBlocks total length of the line part within the query
    * @param downstream block positions of the line's vertices after the nearest point, in flow order
    */
   public record Result(
      OsmWaterKind kind,
      long featureId,
      int nearestX,
      int nearestZ,
      double distanceBlocks,
      int widthBlocks,
      double headingX,
      double headingZ,
      double lengthBlocks,
      List<int[]> downstream
   ) {
      public String describe() {
         StringBuilder path = new StringBuilder();
         for (int[] point : this.downstream) {
            if (path.length() > 0) {
               path.append(' ');
            }
            path.append('(').append(point[0]).append(',').append(point[1]).append(')');
         }
         return String.format(
            Locale.ROOT,
            "Watercourse %s #%d: nearest (%d, %d) %.1f blocks away, width %d blocks, heading (%.2f, %.2f) downstream, line %.0f blocks; downstream vertices: %s",
            this.kind,
            this.featureId,
            this.nearestX,
            this.nearestZ,
            this.distanceBlocks,
            this.widthBlocks,
            this.headingX,
            this.headingZ,
            this.lengthBlocks,
            path
         );
      }
   }

   /**
    * The nearest non-ocean centreline within {@code radiusBlocks} of a block, or {@code null}. Reads
    * Overture tiles blocking; meant for operator commands, not generation.
    */
   public static Result nearest(
      TellusOsmWaterSource source, WorldProjection projection, int blockX, int blockZ, int radiusBlocks
   ) {
      int radius = Math.max(1, radiusBlocks);
      TellusOsmWaterSource.WaterQueryResult query = source.waterForAreaWithStatus(
         blockX - radius, blockZ - radius, blockX + radius, blockZ + radius, projection, 0, OsmQueryMode.BLOCKING
      );
      Result best = null;
      for (OsmWaterFeature feature : query.features()) {
         if (!feature.lineGeometry() || feature.oceanHint() || feature.crossesWorldSeam(projection)) {
            continue;
         }
         double midLatitude = (feature.minLat() + feature.maxLat()) * 0.5;
         double metersPerBlock = projection.groundMetersPerBlockX(projection.latToBlockZ(midLatitude));
         int width = feature.kind().centerlineWidthBlocks(metersPerBlock);
         for (int part = 0; part < feature.partCount(); part++) {
            int points = feature.pointCount(part);
            if (points < 2) {
               continue;
            }
            double[] xs = new double[points];
            double[] zs = new double[points];
            double length = 0.0;
            for (int point = 0; point < points; point++) {
               xs[point] = projection.lonToBlockX(feature.lonAt(part, point));
               zs[point] = projection.latToBlockZ(feature.latAt(part, point));
               if (point > 0) {
                  length += Math.hypot(xs[point] - xs[point - 1], zs[point] - zs[point - 1]);
               }
            }
            for (int point = 1; point < points; point++) {
               double dx = xs[point] - xs[point - 1];
               double dz = zs[point] - zs[point - 1];
               double lengthSq = dx * dx + dz * dz;
               double t = lengthSq <= 1.0E-9 ? 0.0 : Mth.clamp(((blockX - xs[point - 1]) * dx + (blockZ - zs[point - 1]) * dz) / lengthSq, 0.0, 1.0);
               double nearX = xs[point - 1] + t * dx;
               double nearZ = zs[point - 1] + t * dz;
               double distance = Math.hypot(blockX - nearX, blockZ - nearZ);
               if (distance > radius || best != null && distance >= best.distanceBlocks()) {
                  continue;
               }
               double segmentLength = Math.sqrt(lengthSq);
               double headingX = segmentLength > 0.0 ? dx / segmentLength : 0.0;
               double headingZ = segmentLength > 0.0 ? dz / segmentLength : 0.0;
               List<int[]> downstream = new ArrayList<>();
               for (int next = point; next < points && downstream.size() < PATH_POINTS; next++) {
                  downstream.add(new int[]{Mth.floor(xs[next]), Mth.floor(zs[next])});
               }
               best = new Result(
                  feature.kind(),
                  feature.featureId(),
                  Mth.floor(nearX),
                  Mth.floor(nearZ),
                  distance,
                  width,
                  headingX,
                  headingZ,
                  length,
                  List.copyOf(downstream)
               );
            }
         }
      }
      return best;
   }
}
