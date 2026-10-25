package com.yucareux.tellus.worldgen.landmark;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.TerrainHeightTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/** The landmarks of one world, placed for its scale on first use. */
public final class TellusLandmarks {
   /** Landmarks shorter than this many blocks at the world's scale are left out. */
   private static final double MIN_HEIGHT_BLOCKS = 4.0;
   private static final int DEFAULT_SEA_LEVEL = 63;
   private static final TellusLandmarks NONE = new TellusLandmarks(WorldProjection.global(0.0), null, null);

   private final WorldProjection projection;
   private final IntBinaryOperator surfaceSampler;
   private final ElevationToY elevationToY;
   private volatile List<LandmarkPlacement> placements;

   /** Where the world puts the ground surface (the top block) for a real elevation at a block row. */
   @FunctionalInterface
   public interface ElevationToY {
      int surfaceY(double elevationMeters, double blockZ);
   }

   private TellusLandmarks(WorldProjection projection, IntBinaryOperator surfaceSampler, ElevationToY elevationToY) {
      this.projection = projection;
      this.surfaceSampler = surfaceSampler;
      this.elevationToY = elevationToY;
      if (surfaceSampler == null) {
         this.placements = List.of();
      }
   }

   /**
    * The landmarks of a world with these settings.
    *
    * @param surfaceSampler the generator's exact terrain surface Y at a block column, used once per landmark for its base
    */
   public static TellusLandmarks create(EarthGeneratorSettings settings, IntBinaryOperator surfaceSampler) {
      return create(settings.projection(), settings.enableBuildings(), surfaceSampler, elevationToY(settings));
   }

   /** Landmarks for a world whose sea is at Y 63 and whose terrain follows the map's own scale; for tests and tools. */
   public static TellusLandmarks create(double worldScale, boolean buildingsEnabled, IntBinaryOperator surfaceSampler) {
      WorldProjection projection = WorldProjection.global(worldScale);
      return create(projection, buildingsEnabled, surfaceSampler, (elevation, blockZ) ->
         DEFAULT_SEA_LEVEL + (int)Math.round(elevation * projection.heightScaleCorrection(blockZ) / worldScale));
   }

   public static TellusLandmarks create(WorldProjection projection, boolean buildingsEnabled, IntBinaryOperator surfaceSampler, ElevationToY elevationToY) {
      return buildingsEnabled && projection.worldScale() > 0.0 && LandmarkCatalog.enabled() ? new TellusLandmarks(projection, surfaceSampler, elevationToY) : NONE;
   }

   /** The world's own elevation-to-height mapping (the same one its terrain uses). */
   public static ElevationToY elevationToY(EarthGeneratorSettings settings) {
      return (elevation, blockZ) -> TerrainHeightTransform.blockOffset(
         elevation,
         blockZ,
         settings.projection(),
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      ) + settings.effectiveHeightOffset();
   }

   public List<LandmarkPlacement> placements() {
      List<LandmarkPlacement> placed = this.placements;
      if (placed == null) {
         synchronized (this) {
            placed = this.placements;
            if (placed == null) {
               List<LandmarkPlacement> result = new ArrayList<>();
               for (LandmarkCatalog.Entry entry : LandmarkCatalog.entries()) {
                  LandmarkMesh mesh = entry.mesh();
                  if (mesh != null) {
                     LandmarkPlacement placement = new LandmarkPlacement(entry, mesh, this.projection, this.surfaceSampler, this.elevationToY);
                     if (placement.heightBlocks() >= MIN_HEIGHT_BLOCKS) {
                        result.add(placement);
                     }
                  }
               }
               placed = List.copyOf(result);
               this.placements = placed;
            }
         }
      }
      return placed;
   }

   /** The landmarks whose blocks may reach into this block rectangle (inclusive). */
   public List<LandmarkPlacement> intersecting(int minX, int minZ, int maxX, int maxZ) {
      List<LandmarkPlacement> all = this.placements();
      if (all.isEmpty()) {
         return List.of();
      }
      List<LandmarkPlacement> found = null;
      for (LandmarkPlacement placement : all) {
         if (placement.intersects(minX, minZ, maxX, maxZ)) {
            if (found == null) {
               found = new ArrayList<>(1);
            }
            found.add(placement);
         }
      }
      return found == null ? List.of() : found;
   }
}
