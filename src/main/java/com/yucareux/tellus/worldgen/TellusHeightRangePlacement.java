package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.caves.TellusCaveDepthMapper;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** Shared surface-relative underground placement, independent of mixin signatures. */
public final class TellusHeightRangePlacement {
   private TellusHeightRangePlacement() {}

   /** Returns null when vanilla should handle this placement. */
   public static Stream<BlockPos> positions(HeightProvider height, PlacementContext context, RandomSource random, BlockPos origin) {
      if (!(context.generator() instanceof EarthChunkGenerator earthGenerator)
         || !earthGenerator.settings().usesTerrainShell()) {
         return null;
      }

      WorldGenerationContext vanillaContext = new WorldGenerationContext(
         context.generator(),
         LevelHeightAccessor.create(
            TellusCaveDepthMapper.VANILLA_MIN_Y,
            TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1
         )
      ) {
         @Override
         public int getMinGenY() {
            return TellusCaveDepthMapper.VANILLA_MIN_Y;
         }

         @Override
         public int getGenDepth() {
            return TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1;
         }
      };
      int actualSurfaceY = earthGenerator.getUndergroundPlacementSurfaceY(origin.getX(), origin.getZ());
      int actualBottomY = findUsableUndergroundBottom(context, earthGenerator, origin, actualSurfaceY);
      int virtualSurfaceY = TellusCaveDepthMapper.virtualSurfaceForTellusColumn(
         actualSurfaceY, earthGenerator.getSeaLevel()
      );
      UndergroundFeatureClassifier.Kind featureKind = context.topFeature()
         .map(UndergroundFeatureClassifier::classify)
         .orElse(UndergroundFeatureClassifier.Kind.OTHER);
      if (featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE && !earthGenerator.settings().oreDistribution()
         || featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
            && !earthGenerator.settings().geologicalStonePatches()) {
         return Stream.empty();
      }

      int sampleCount = featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE
         ? OrePlacementDensityPolicy.placementSamples(
            actualSurfaceY - actualBottomY,
            virtualSurfaceY - TellusCaveDepthMapper.VANILLA_MIN_Y,
            random
         )
         : 1;
      int minimumNearbySurfaceY = featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         ? minimumNearbySurfaceY(earthGenerator, origin, GeologicalStonePlacementPolicy.BLOB_SURFACE_SAMPLE_RADIUS)
         : Integer.MAX_VALUE;
      Stream.Builder<BlockPos> positions = Stream.builder();
      for (int sample = 0; sample < sampleCount; sample++) {
         int virtualY = height.sample(random, vanillaContext);
         int actualY = TellusCaveDepthMapper.actualYForVirtualFeature(
            virtualY, virtualSurfaceY, actualSurfaceY, actualBottomY
         );
         if (featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE) {
            actualY = GeologicalStonePlacementPolicy.safeBlobOriginY(
               actualY, minimumNearbySurfaceY, actualBottomY
            );
         }
         if (actualY != Integer.MIN_VALUE
            && !earthGenerator.isUndergroundStructureFeaturePlacementBlocked(
               origin.getX(), actualY, origin.getZ()
            )) {
            positions.add(origin.atY(actualY));
         }
      }
      return positions.build();
   }

   private static int minimumNearbySurfaceY(EarthChunkGenerator generator, BlockPos origin, int radius) {
      int minimumSurfaceY = Integer.MAX_VALUE;
      for (int dz = -radius; dz <= radius; dz++) {
         for (int dx = -radius; dx <= radius; dx++) {
            minimumSurfaceY = Math.min(
               minimumSurfaceY,
               generator.getUndergroundPlacementSurfaceY(origin.getX() + dx, origin.getZ() + dz)
            );
         }
      }
      return minimumSurfaceY;
   }

   private static int findUsableUndergroundBottom(
      PlacementContext context,
      EarthChunkGenerator generator,
      BlockPos origin,
      int surfaceY
   ) {
      int searchBottom = UndergroundGenerationDepthPolicy.deepestGenerationY(
         surfaceY,
         generator.settings().undergroundDepth(),
         context.getLevel().dimensionType().minY()
      );
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(origin.getX(), surfaceY - 1, origin.getZ());
      for (int y = surfaceY - 1; y >= searchBottom; y--) {
         cursor.setY(y);
         if (context.getBlockState(cursor).is(Blocks.BEDROCK)) {
            return Math.min(surfaceY - 1, y + 1);
         }
      }
      return searchBottom;
   }
}
