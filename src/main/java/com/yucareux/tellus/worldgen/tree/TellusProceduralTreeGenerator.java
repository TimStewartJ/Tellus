package com.yucareux.tellus.worldgen.tree;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import com.yucareux.tellus.world.data.resolve.ResolveBiome;
import com.yucareux.tellus.world.data.resolve.ResolveEcoregion;
import com.yucareux.tellus.world.data.resolve.ResolveRealm;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Deterministic, data-driven trees for Tellus full-detail terrain.
 *
 * <p>The biome has already been resolved from ESA WorldCover, Köppen climate,
 * and RESOLVE ecoregions. That combined classification selects an ecological
 * growth form and Minecraft material palette; ETH canopy height controls the
 * physical dimensions. Heights are always interpreted as blocks-per-meter and
 * therefore remain 1:1 even when the horizontal map scale changes.</p>
 */
public final class TellusProceduralTreeGenerator {
   private static final int PLACEMENT_FLAGS = 260;
   private static final int MIN_TREE_HEIGHT = 4;
   private static final int BUSH_HEIGHT = 3;
   private static final int BUSH_CROWN_RADIUS = 2;
   public static final int PLACEMENT_CELL_SIZE = 9;
   private static final int NORTHERN_CALIFORNIA_COASTAL_FORESTS_ID = 359;
   private static final int ICELAND_BOREAL_BIRCH_FORESTS_ID = 711;
   private static final int SCANDINAVIAN_MONTANE_BIRCH_FORESTS_ID = 780;
   private static final double COAST_REDWOOD_HEIGHT_CALIBRATION = 1.65;
   private static final long PLAN_SALT = 0x42C5A19F371D2E6BL;
   private static final long REGIONAL_PROFILE_SALT = 0x718D3A42E5C690F1L;
   private static final long BUSH_FOLIAGE_SALT = 0x2A7856E43D19BC0FL;
   private static final int ROOT_SURFACE_SCAN_DEPTH = 10;
   private static final int ROOT_MAX_STEP = 2;
   private static final int ROOT_MAX_ANCHOR_DELTA = 6;
   private static final int BASE_MAX_SUPPORT_DEPTH = 4;
   private static final int BASE_MIN_CONNECTED_CORE_COLUMNS = 6;
   public static final int ROOT_EXCLUSION_RADIUS = 8;

   private TellusProceduralTreeGenerator() {
   }

   /**
    * Returns the single deterministic full-detail tree anchor for a placement
    * cell. Distant Horizons uses the same anchors so its canopy does not slide
    * to a different grid when the corresponding full chunk appears.
    */
   public static TreeAnchor anchorForCell(int cellX, int cellZ, long worldSeed) {
      long seed = seedFromCoords(cellX, 0, cellZ) ^ worldSeed;
      Random random = new Random(seed);
      return new TreeAnchor(
         cellX * PLACEMENT_CELL_SIZE + random.nextInt(PLACEMENT_CELL_SIZE),
         cellZ * PLACEMENT_CELL_SIZE + random.nextInt(PLACEMENT_CELL_SIZE),
         seed
      );
   }

   public static TreePlan plan(
      Holder<Biome> biome, TellusCanopyHeightSource.CanopySample canopy, long seed
   ) {
      return plan(biome, ResolveEcoregion.UNKNOWN, canopy, seed);
   }

   public static TreePlan plan(
      Holder<Biome> biome,
      ResolveEcoregion ecoregion,
      TellusCanopyHeightSource.CanopySample canopy,
      long seed
   ) {
      return plan(profile(biome, ecoregion, seed), canopy, seed);
   }

   public static Profile profile(Holder<Biome> biome, ResolveEcoregion ecoregion, long seed) {
      return regionalProfile(profileForBiome(biome), ecoregion, seed);
   }

   /**
    * Registry-free planning entry point for clients such as the terrain
    * preview. Its key-based profile mapping mirrors the biome-tag mapping used
    * by full-detail generation, then applies the same regional and
    * canopy-height rules.
    */
   public static TreePlan plan(
      ResourceKey<Biome> biomeKey,
      ResolveEcoregion ecoregion,
      TellusCanopyHeightSource.CanopySample canopy,
      long seed
   ) {
      return plan(profile(biomeKey, ecoregion, seed), canopy, seed);
   }

   public static Profile profile(ResourceKey<Biome> biomeKey, ResolveEcoregion ecoregion, long seed) {
      return regionalProfile(profileForBiome(biomeKey), ecoregion, seed);
   }

   public static TreePlan plan(
      Profile profile, TellusCanopyHeightSource.CanopySample canopy, long seed
   ) {
      Random random = new Random(mix(seed ^ PLAN_SALT));
      if (canopy != null && canopy.available() && canopy.maximumHeightMeters() < 2.0) {
         // ESA still identifies vegetation here; preserve that cover as scrub
         // when ETH has no credible tree-height signal for a full-sized tree.
         return TreePlan.bush(profile, true);
      }

      double referenceHeight;
      boolean dataDriven = canopy != null && canopy.available();
      if (dataDriven) {
         double measuredHeight =
            canopy.centerHeightMeters() * 0.30
               + canopy.percentile75Meters() * 0.42
               + canopy.percentile90Meters() * 0.28;
         referenceHeight = calibratedCanopyHeight(profile, measuredHeight);
      } else {
         referenceHeight = lerp(profile.fallbackLow(), profile.fallbackHigh(), random.nextDouble());
      }

      double ageRoll = random.nextDouble();
      double ageFactor;
      if (profile == Profile.COAST_REDWOOD) {
         if (ageRoll < 0.10) {
            ageFactor = lerp(0.42, 0.62, random.nextDouble());
         } else if (ageRoll < 0.28) {
            ageFactor = lerp(0.68, 0.88, random.nextDouble());
         } else if (ageRoll < 0.80) {
            ageFactor = lerp(0.92, 1.08, random.nextDouble());
         } else if (ageRoll < 0.96) {
            ageFactor = lerp(1.08, 1.20, random.nextDouble());
         } else {
            double emergentReference = dataDriven
               ? Math.max(referenceHeight, calibratedCanopyHeight(profile, canopy.maximumHeightMeters()))
               : profile.fallbackHigh();
            referenceHeight = emergentReference;
            ageFactor = lerp(1.14, 1.28, random.nextDouble());
         }
      } else if (dataDriven) {
         /*
          * ETH describes the top of the local canopy, not the age of a random
          * sapling. Keep most representative trees close to that observation,
          * while retaining a small understory and emergent tail for a natural
          * stand silhouette.
          */
         if (ageRoll < 0.10) {
            ageFactor = lerp(0.64, 0.82, random.nextDouble());
         } else if (ageRoll < 0.88) {
            ageFactor = lerp(0.88, 1.06, random.nextDouble());
         } else if (ageRoll < 0.98) {
            ageFactor = lerp(1.02, 1.12, random.nextDouble());
         } else {
            referenceHeight = Math.max(referenceHeight, canopy.maximumHeightMeters());
            ageFactor = lerp(1.02, 1.12, random.nextDouble());
         }
      } else {
         if (ageRoll < 0.12) {
            ageFactor = lerp(0.28, 0.50, random.nextDouble());
         } else if (ageRoll < 0.32) {
            ageFactor = lerp(0.52, 0.76, random.nextDouble());
         } else if (ageRoll < 0.82) {
            ageFactor = lerp(0.78, 1.00, random.nextDouble());
         } else if (ageRoll < 0.97) {
            ageFactor = lerp(1.00, 1.10, random.nextDouble());
         } else {
            double emergentReference = dataDriven
               ? Math.max(referenceHeight, canopy.maximumHeightMeters())
               : profile.fallbackHigh();
            referenceHeight = emergentReference;
            ageFactor = lerp(1.05, 1.18, random.nextDouble());
         }
      }

      int height = clamp((int)Math.round(referenceHeight * ageFactor), MIN_TREE_HEIGHT, profile.maximumHeight());
      int trunkRadius = height >= 76 ? 4 : height >= 52 ? 3 : height >= 28 ? 2 : 1;
      if (profile == Profile.BIRCH || profile == Profile.CHERRY || profile == Profile.EUCALYPTUS) {
         trunkRadius = Math.min(trunkRadius, 2);
      }
      if (profile == Profile.SUBARCTIC_BIRCH || profile == Profile.MALLEE) {
         trunkRadius = 1;
      }
      if (profile == Profile.SAVANNA && height < 18) {
         trunkRadius = 1;
      }

      int crownRadius = clamp(
         (int)Math.round(height * profile.crownRadiusRatio()),
         profile.minimumCrownRadius(),
         profile.maximumCrownRadius()
      );
      int crownBase = clamp((int)Math.round(height * crownBaseRatio(profile, height)), 2, Math.max(2, height - 2));
      int crownHeight = Math.max(3, height - crownBase + 1);
      int maxLean = Math.max(0, (int)Math.round(height * profile.leanRatio()));
      int leanX = maxLean == 0 ? 0 : random.nextInt(maxLean * 2 + 1) - maxLean;
      int leanZ = maxLean == 0 ? 0 : random.nextInt(maxLean * 2 + 1) - maxLean;
      if (leanX == 0 && leanZ == 0 && maxLean > 1 && random.nextBoolean()) {
         leanX = random.nextBoolean() ? 1 : -1;
      }

      return new TreePlan(
         profile,
         height,
         trunkRadius,
         crownRadius,
         crownBase,
         crownHeight,
         leanX,
         leanZ,
         false,
         dataDriven
      );
   }

   public static boolean place(
      WorldGenLevel level,
      BlockPos ground,
      Holder<Biome> biome,
      TellusCanopyHeightSource.CanopySample canopy,
      long seed
   ) {
      return place(level, ground, biome, ResolveEcoregion.UNKNOWN, canopy, seed);
   }

   public static boolean place(
      WorldGenLevel level,
      BlockPos ground,
      Holder<Biome> biome,
      ResolveEcoregion ecoregion,
      TellusCanopyHeightSource.CanopySample canopy,
      long seed
   ) {
      return place(level, ground, plan(biome, ecoregion, canopy, seed), seed);
   }

   public static boolean place(WorldGenLevel level, BlockPos ground, TreePlan initialPlan, long seed) {
      TreePlan plan = Objects.requireNonNull(initialPlan, "initialPlan");
      if (!plan.present()) {
         return false;
      }
      if (plan.bush()) {
         return placeBush(level, ground, plan, seed);
      }
      int availableHeight = MinecraftVersionCompat.maxBuildHeight(level) - ground.getY() - 2;
      if (availableHeight < MIN_TREE_HEIGHT) {
         return false;
      }
      if (plan.height() > availableHeight) {
         plan = plan.withHeight(availableHeight);
      }
      TrunkPlacementPlan trunk = planTrunkPlacement(
         ground.getX(),
         ground.getY(),
         ground.getZ(),
         plan,
         (worldX, worldZ) -> findRootSurfaceY(
            level, worldX, worldZ, ground.getY()
         ),
         (worldX, worldY, worldZ) -> canPlaceTrunkAt(
            level, new BlockPos(worldX, worldY, worldZ)
         ),
         (worldX, worldY, worldZ) -> canPlaceRootAt(
            level, new BlockPos(worldX, worldY, worldZ)
         )
      );
      if (!trunk.valid()) {
         return false;
      }

      Palette palette = palette(plan.profile(), seed);
      BlockState log = palette.log().defaultBlockState();
      BlockState leaves = persistentLeaves(palette.leaves().defaultBlockState());
      growTrunk(level, trunk, log);
      growRoots(level, ground, plan, log, seed);
      if (plan.height() <= 6) {
         int crownY = ground.getY() + plan.height() - 1;
         placeLeafBlob(
            level,
            leanedX(ground, plan, plan.height() - 1),
            crownY,
            leanedZ(ground, plan, plan.height() - 1),
            2,
            1,
            leaves,
            seed,
            0.82
         );
         return true;
      }
      switch (plan.profile()) {
         case COAST_REDWOOD -> growCoastRedwood(level, ground, plan, log, leaves, seed);
         case CONIFER, TALL_CONIFER -> growConifer(level, ground, plan, log, leaves, seed);
         case PINE -> growPine(level, ground, plan, log, leaves, seed);
         case SAVANNA -> growSavanna(level, ground, plan, log, leaves, seed);
         case TROPICAL -> growBroadleaf(level, ground, plan, log, leaves, seed, 0.78);
         case DRY_BROADLEAF -> growBroadleaf(level, ground, plan, log, leaves, seed, 0.92);
         case BIRCH -> growBroadleaf(level, ground, plan, log, leaves, seed, 0.58);
         case SUBARCTIC_BIRCH -> growSubarcticBirch(level, ground, plan, log, leaves, seed);
         case EUCALYPTUS -> growEucalyptus(level, ground, plan, log, leaves, seed);
         case MALLEE -> growMallee(level, ground, plan, log, leaves, seed);
         case MEDITERRANEAN -> growMediterranean(level, ground, plan, log, leaves, seed);
         case CHERRY -> growBroadleaf(level, ground, plan, log, leaves, seed, 0.84);
         case DARK_BROADLEAF, PALE_BROADLEAF -> growBroadleaf(level, ground, plan, log, leaves, seed, 1.0);
         case SWAMP, TEMPERATE_BROADLEAF -> growBroadleaf(level, ground, plan, log, leaves, seed, 0.90);
      }
      return true;
   }

   private static boolean placeBush(WorldGenLevel level, BlockPos ground, TreePlan plan, long seed) {
      BlockPos logPosition = ground.above();
      BlockPos crownTop = logPosition.above(2);
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, logPosition)
         || !MinecraftVersionCompat.isInsideBuildHeight(level, crownTop)
         || !level.ensureCanWrite(logPosition)
         || !level.ensureCanWrite(crownTop)
         || !canReplaceTrunk(level.getBlockState(logPosition))
         || !canReplaceTrunk(level.getBlockState(crownTop))) {
         return false;
      }

      Palette palette = palette(plan.profile(), seed);
      BlockState log = axis(palette.log().defaultBlockState(), Direction.Axis.Y);
      BlockState leaves = persistentLeaves(palette.leaves().defaultBlockState());
      if (!level.setBlock(logPosition, log, PLACEMENT_FLAGS)) {
         return false;
      }

      long foliageSeed = seed ^ BUSH_FOLIAGE_SALT;
      placeNoisyLeafDisc(
         level, logPosition.getX(), logPosition.getY(), logPosition.getZ(), 1, leaves, foliageSeed, 1.0
      );
      placeNoisyLeafDisc(
         level, logPosition.getX(), logPosition.getY() + 1, logPosition.getZ(), 2, leaves, foliageSeed, 0.82
      );
      placeNoisyLeafDisc(
         level, logPosition.getX(), logPosition.getY() + 2, logPosition.getZ(), 1, leaves, foliageSeed, 0.78
      );
      setLeaves(level, logPosition.above(), leaves);
      setLeaves(level, logPosition.above(2), leaves);
      return true;
   }

   private static int trunkTop(TreePlan plan) {
      if (plan.profile() == Profile.COAST_REDWOOD) {
         return Math.max(plan.crownBase() + 2, plan.height() - 3);
      } else if (plan.profile() == Profile.MALLEE || plan.profile() == Profile.SUBARCTIC_BIRCH) {
         return Math.max(2, plan.crownBase() + 1);
      } else if (plan.profile().conifer()) {
         return Math.max(1, plan.height() - 1);
      } else {
         return Math.max(plan.crownBase() + 2, (int)Math.round(plan.height() * 0.88));
      }
   }

   private static int trunkRadiusAt(TreePlan plan, int relativeY) {
      double progress = relativeY / (double)Math.max(1, plan.height());
      double taper = 1.0 - progress * 0.72;
      int radius = Math.max(0, (int)Math.ceil(plan.trunkRadius() * taper) - 1);
      if (plan.profile() == Profile.COAST_REDWOOD
         && (relativeY == 1 || relativeY == 2 && plan.trunkRadius() >= 3)) {
         radius++;
      }
      if (plan.profile() == Profile.MALLEE && relativeY == 1) {
         radius = Math.max(1, radius);
      }
      return radius;
   }

   static TrunkPlacementPlan planTrunkPlacement(
      int anchorX,
      int anchorGroundY,
      int anchorZ,
      TreePlan plan,
      RootSurfaceSampler surfaces,
      TrunkPlacementTester trunkPlacement,
      TrunkPlacementTester rootPlacement
   ) {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(surfaces, "surfaces");
      Objects.requireNonNull(trunkPlacement, "trunkPlacement");
      Objects.requireNonNull(rootPlacement, "rootPlacement");
      int baseTargetY = anchorGroundY + 1;
      BasalFootprintPlan basalFootprint = planBasalFootprint(
         anchorX,
         anchorGroundY,
         anchorZ,
         plan,
         (worldX, worldZ) -> {
            int surfaceY = surfaces.surfaceY(worldX, worldZ);
            if (!isDirectSupportSurface(surfaceY, baseTargetY)) {
               return surfaceY;
            }
            return trunkPlacement.canPlace(worldX, baseTargetY, worldZ)
                  && supportPathIsClear(
                     worldX,
                     surfaceY + 1,
                     baseTargetY - 1,
                     worldZ,
                     rootPlacement
                  )
               ? surfaceY
               : Integer.MIN_VALUE;
         }
      );
      if (!basalFootprint.valid()) {
         return TrunkPlacementPlan.invalid();
      }
      Map<Long, Integer> basalSurfaces = new HashMap<>();
      for (BasalColumn column : basalFootprint.columns()) {
         basalSurfaces.put(
            packColumn(column.worldX(), column.worldZ()), column.surfaceY()
         );
      }
      List<TrunkBlock> supports = new ArrayList<>();
      List<TrunkBlock> trunk = new ArrayList<>();
      Set<Long> previousLayer = Set.of();
      Set<Long> supportBlocks = new HashSet<>();

      int trunkTop = trunkTop(plan);
      for (int y = 1; y <= trunkTop; y++) {
         double progress = y / (double)Math.max(1, plan.height());
         int centerX = anchorX + (int)Math.round(plan.leanX() * progress);
         int centerZ = anchorZ + (int)Math.round(plan.leanZ() * progress);
         int targetY = anchorGroundY + y;
         int radius = trunkRadiusAt(plan, y);
         int coreRadius = y == 1 ? Math.min(1, radius) : 0;
         Set<Long> currentLayer = new LinkedHashSet<>();

         for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
               if (!insideDisc(dx, dz, radius)) {
                  continue;
               }
               int worldX = centerX + dx;
               int worldZ = centerZ + dz;
               long column = packColumn(worldX, worldZ);
               boolean centerline = dx == 0 && dz == 0;
               boolean core = y == 1 && insideDisc(dx, dz, coreRadius);
               Integer basalSurface = core ? basalSurfaces.get(column) : null;
               if (core && basalSurface == null) {
                  continue;
               }
               boolean placeable = core
                  || trunkPlacement.canPlace(worldX, targetY, worldZ);
               boolean connected = y > 1 && touchesPreviousLayer(
                  previousLayer, worldX, worldZ
               );
               int surfaceY = core
                  ? basalSurface
                  : connected
                     ? Integer.MIN_VALUE
                     : surfaces.surfaceY(worldX, worldZ);
               boolean directlySupported = core
                  || !connected
                     && placeable
                     && isDirectSupportSurface(surfaceY, targetY)
                     && supportPathIsClear(
                        worldX,
                        surfaceY + 1,
                        targetY - 1,
                        worldZ,
                        rootPlacement
                     );
               if (!placeable || !directlySupported && !connected) {
                  if (centerline) {
                     return TrunkPlacementPlan.invalid();
                  }
                  continue;
               }

               if (directlySupported) {
                  for (int supportY = surfaceY + 1; supportY < targetY; supportY++) {
                     long packed = BlockPos.asLong(worldX, supportY, worldZ);
                     if (supportBlocks.add(packed)) {
                        supports.add(new TrunkBlock(worldX, supportY, worldZ));
                     }
                  }
               }
               currentLayer.add(column);
               trunk.add(new TrunkBlock(worldX, targetY, worldZ));
            }
         }
         if (!currentLayer.contains(packColumn(centerX, centerZ))) {
            return TrunkPlacementPlan.invalid();
         }
         previousLayer = Set.copyOf(currentLayer);
      }
      return new TrunkPlacementPlan(true, supports, trunk);
   }

   public static BasalFootprintPlan planBasalFootprint(
      int anchorX,
      int anchorGroundY,
      int anchorZ,
      TreePlan plan,
      BasalSurfaceSampler surfaces
   ) {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(surfaces, "surfaces");
      int targetY = anchorGroundY + 1;
      int coreRadius = Math.min(1, trunkRadiusAt(plan, 1));
      Map<Long, BasalColumn> supported = new HashMap<>();
      for (int dz = -coreRadius; dz <= coreRadius; dz++) {
         for (int dx = -coreRadius; dx <= coreRadius; dx++) {
            if (!insideDisc(dx, dz, coreRadius)) {
               continue;
            }
            int worldX = anchorX + dx;
            int worldZ = anchorZ + dz;
            int surfaceY = surfaces.surfaceY(worldX, worldZ);
            if (isDirectSupportSurface(surfaceY, targetY)) {
               supported.put(
                  packColumn(worldX, worldZ),
                  new BasalColumn(worldX, worldZ, surfaceY)
               );
            }
         }
      }

      long center = packColumn(anchorX, anchorZ);
      if (!supported.containsKey(center)) {
         return BasalFootprintPlan.invalid(targetY);
      }
      Set<Long> connected = new LinkedHashSet<>();
      ArrayDeque<Long> pending = new ArrayDeque<>();
      connected.add(center);
      pending.add(center);
      while (!pending.isEmpty()) {
         long current = pending.removeFirst();
         int worldX = (int)(current >> 32);
         int worldZ = (int)current;
         for (Direction direction : Direction.Plane.HORIZONTAL) {
            long neighbor = packColumn(
               worldX + direction.getStepX(), worldZ + direction.getStepZ()
            );
            if (supported.containsKey(neighbor) && connected.add(neighbor)) {
               pending.addLast(neighbor);
            }
         }
      }

      int requiredColumns = coreRadius == 0
         ? 1
         : BASE_MIN_CONNECTED_CORE_COLUMNS;
      if (connected.size() < requiredColumns) {
         return BasalFootprintPlan.invalid(targetY);
      }
      List<BasalColumn> columns = connected.stream()
         .map(supported::get)
         .toList();
      return new BasalFootprintPlan(true, targetY, columns);
   }

   private static boolean isDirectSupportSurface(int surfaceY, int targetY) {
      return surfaceY != Integer.MIN_VALUE
         && surfaceY < targetY
         && targetY - surfaceY <= BASE_MAX_SUPPORT_DEPTH;
   }

   private static boolean insideDisc(int dx, int dz, int radius) {
      return dx * dx + dz * dz <= radius * radius + radius;
   }

   private static boolean touchesPreviousLayer(
      Set<Long> previousLayer, int worldX, int worldZ
   ) {
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (previousLayer.contains(packColumn(worldX + dx, worldZ + dz))) {
               return true;
            }
         }
      }
      return false;
   }

   private static boolean supportPathIsClear(
      int worldX,
      int minimumY,
      int maximumY,
      int worldZ,
      TrunkPlacementTester placement
   ) {
      for (int y = minimumY; y <= maximumY; y++) {
         if (!placement.canPlace(worldX, y, worldZ)) {
            return false;
         }
      }
      return true;
   }

   private static long packColumn(int worldX, int worldZ) {
      return (long)worldX << 32 | worldZ & 0xFFFF_FFFFL;
   }

   private static void growTrunk(
      WorldGenLevel level, TrunkPlacementPlan plan, BlockState log
   ) {
      BlockState verticalLog = axis(log, Direction.Axis.Y);
      for (TrunkBlock support : plan.supports()) {
         setRootLog(
            level,
            new BlockPos(support.worldX(), support.worldY(), support.worldZ()),
            verticalLog
         );
      }
      for (TrunkBlock block : plan.trunk()) {
         setLog(
            level,
            new BlockPos(block.worldX(), block.worldY(), block.worldZ()),
            verticalLog
         );
      }
   }

   private static void growRoots(
      WorldGenLevel level, BlockPos ground, TreePlan plan, BlockState log, long seed
   ) {
      if (plan.trunkRadius() < 2
         && plan.profile() != Profile.TROPICAL
         && plan.profile() != Profile.SWAMP
         && plan.profile() != Profile.COAST_REDWOOD) {
         return;
      }
      Random random = new Random(seed ^ 0x6B76CE20B945A13DL);
      boolean coastRedwood = plan.profile() == Profile.COAST_REDWOOD;
      int roots = Math.max(coastRedwood ? 6 : 4, plan.trunkRadius() * 2 + (coastRedwood ? 4 : 2));
      int length = coastRedwood
         ? Math.min(8, plan.trunkRadius() + 4)
         : Math.min(6, plan.trunkRadius() + (plan.profile() == Profile.TROPICAL ? 3 : 1));
      for (int index = 0; index < roots; index++) {
         double angle = Math.PI * 2.0 * index / roots + random.nextDouble() * 0.35;
         int endX = ground.getX() + (int)Math.round(Math.cos(angle) * length);
         int endZ = ground.getZ() + (int)Math.round(Math.sin(angle) * length);
         int baseHeight = Math.min(
            coastRedwood ? 4 : 3,
            plan.trunkRadius() + (coastRedwood ? 1 : 0)
         );
         List<RootColumn> columns = planRootColumns(
            ground.getX(),
            ground.getY(),
            ground.getZ(),
            endX,
            endZ,
            baseHeight,
            (worldX, worldZ) -> findRootSurfaceY(
               level, worldX, worldZ, ground.getY()
            )
         );
         Direction.Axis rootAxis = Math.abs(endX - ground.getX()) >= Math.abs(endZ - ground.getZ())
            ? Direction.Axis.X
            : Direction.Axis.Z;
         BlockState horizontalLog = axis(log, rootAxis);
         BlockState verticalLog = axis(log, Direction.Axis.Y);
         for (RootColumn column : columns) {
            boolean supported = true;
            for (int y = column.surfaceY() + 1; y < column.topY(); y++) {
               if (!setRootLog(
                  level,
                  new BlockPos(column.worldX(), y, column.worldZ()),
                  verticalLog
               )) {
                  supported = false;
                  break;
               }
            }
            if (supported) {
               setRootLog(
                  level,
                  new BlockPos(column.worldX(), column.topY(), column.worldZ()),
                  horizontalLog
               );
            }
         }
      }
   }

   static List<RootColumn> planRootColumns(
      int startX,
      int anchorGroundY,
      int startZ,
      int endX,
      int endZ,
      int baseHeight,
      RootSurfaceSampler surfaces
   ) {
      Objects.requireNonNull(surfaces, "surfaces");
      int dx = endX - startX;
      int dz = endZ - startZ;
      int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)));
      List<RootColumn> columns = new ArrayList<>(steps + 1);
      int previousX = Integer.MIN_VALUE;
      int previousZ = Integer.MIN_VALUE;
      int previousSurface = anchorGroundY;
      for (int step = 0; step <= steps; step++) {
         double progress = step / (double)steps;
         int worldX = (int)Math.round(startX + dx * progress);
         int worldZ = (int)Math.round(startZ + dz * progress);
         if (worldX == previousX && worldZ == previousZ) {
            continue;
         }
         int surfaceY = surfaces.surfaceY(worldX, worldZ);
         if (surfaceY == Integer.MIN_VALUE
            || Math.abs(surfaceY - previousSurface) > ROOT_MAX_STEP
            || Math.abs(surfaceY - anchorGroundY) > ROOT_MAX_ANCHOR_DELTA) {
            break;
         }
         int ridge = Math.max(0, Math.max(1, baseHeight) - 1 - step);
         columns.add(new RootColumn(worldX, worldZ, surfaceY, surfaceY + 1 + ridge));
         previousX = worldX;
         previousZ = worldZ;
         previousSurface = surfaceY;
      }
      return List.copyOf(columns);
   }

   private static int findRootSurfaceY(
      WorldGenLevel level, int worldX, int worldZ, int anchorGroundY
   ) {
      int heightmapTop = level.getHeight(
         Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ
      ) - 1;
      int startY = Math.min(heightmapTop, anchorGroundY + ROOT_MAX_ANCHOR_DELTA);
      int minimumY = Math.max(
         MinecraftVersionCompat.minBuildHeight(level),
         anchorGroundY - ROOT_SURFACE_SCAN_DEPTH
      );
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int y = startY; y >= minimumY; y--) {
         cursor.set(worldX, y, worldZ);
         BlockState state = level.getBlockState(cursor);
         if (state.isAir()
            || state.is(BlockTags.LOGS)
            || state.is(BlockTags.LEAVES)
            || state.is(BlockTags.REPLACEABLE_BY_TREES)
            || state.getFluidState().isEmpty() && state.canBeReplaced()) {
            continue;
         }
         if (!state.getFluidState().isEmpty() || !supportsTreeRoot(state)) {
            return Integer.MIN_VALUE;
         }
         cursor.setY(y + 1);
         return level.getFluidState(cursor).isEmpty() ? y : Integer.MIN_VALUE;
      }
      return Integer.MIN_VALUE;
   }

   static boolean supportsTreeRoot(BlockState state) {
      return state.is(BlockTags.DIRT)
         || state.is(BlockTags.BASE_STONE_OVERWORLD)
         || state.is(Blocks.GRASS_BLOCK)
         || state.is(Blocks.PODZOL)
         || state.is(Blocks.MYCELIUM)
         || state.is(Blocks.GRAVEL)
         || state.is(Blocks.SAND)
         || state.is(Blocks.RED_SAND)
         || state.is(Blocks.SANDSTONE)
         || state.is(Blocks.RED_SANDSTONE)
         || state.is(Blocks.TERRACOTTA)
         || state.is(Blocks.DIRT_PATH)
         || state.is(Blocks.CLAY)
         || state.is(Blocks.MUD)
         || state.is(Blocks.PACKED_MUD)
         || state.is(Blocks.MOSS_BLOCK)
         || state.is(Blocks.SNOW_BLOCK);
   }

   private static void growCoastRedwood(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x4CD52E9A7138B60FL);
      int crownBase = plan.crownBase();
      int crownSpan = Math.max(1, plan.height() - crownBase);
      int relativeY = crownBase;
      double rotation = random.nextDouble() * Math.PI * 2.0;

      while (relativeY <= plan.height() - 2) {
         double vertical = (relativeY - crownBase) / (double)crownSpan;
         double envelope = 0.24 + 0.76 * Math.pow(Math.max(0.0, 1.0 - vertical), 0.68);
         int reach = Math.max(2, (int)Math.round(plan.crownRadius() * envelope));
         int centerX = leanedX(ground, plan, relativeY);
         int centerZ = leanedZ(ground, plan, relativeY);
         int coreRadius = Math.max(1, Math.min(3, (reach + 1) / 2));
         placeLeafBlob(
            level,
            centerX,
            ground.getY() + relativeY,
            centerZ,
            coreRadius,
            2,
            leaves,
            seed + relativeY * 43L,
            0.68
         );

         int branches = 2 + random.nextInt(3);
         for (int branch = 0; branch < branches; branch++) {
            double angle = rotation
               + Math.PI * 2.0 * branch / branches
               + lerp(-0.38, 0.38, random.nextDouble());
            int length = Math.max(2, (int)Math.round(reach * lerp(0.68, 1.05, random.nextDouble())));
            int foliageX = centerX + (int)Math.round(Math.cos(angle) * length);
            int foliageZ = centerZ + (int)Math.round(Math.sin(angle) * length);
            int rise = vertical > 0.62 ? 1 + random.nextInt(2) : random.nextInt(3) - 1;
            int foliageRelativeY = clamp(relativeY + rise, crownBase + 1, plan.height() - 2);
            int woodyLength = Math.max(1, length - 2);
            int logEndX = centerX + (int)Math.round(Math.cos(angle) * woodyLength);
            int logEndZ = centerZ + (int)Math.round(Math.sin(angle) * woodyLength);
            int logEndRelativeY = relativeY
               + (int)Math.round((foliageRelativeY - relativeY) * woodyLength / (double)Math.max(1, length));
            placeLogLine(
               level,
               centerX,
               ground.getY() + relativeY,
               centerZ,
               logEndX,
               ground.getY() + logEndRelativeY,
               logEndZ,
               log,
               0
            );

            int branchLeafRadius = Math.max(1, Math.min(3, 1 + reach / 3));
            placeLeafBlob(
               level,
               foliageX,
               ground.getY() + foliageRelativeY,
               foliageZ,
               branchLeafRadius,
               2,
               leaves,
               seed + relativeY * 97L + branch * 131L,
               0.74
            );
            if (length >= 4) {
               int middleX = (centerX + foliageX) >> 1;
               int middleZ = (centerZ + foliageZ) >> 1;
               int middleY = ground.getY() + ((relativeY + foliageRelativeY) >> 1);
               placeLeafBlob(
                  level,
                  middleX,
                  middleY,
                  middleZ,
                  Math.max(1, branchLeafRadius - 1),
                  1,
                  leaves,
                  seed ^ relativeY * 181L ^ branch * 67L,
                  0.70
               );
            }
         }

         rotation += 1.81 + lerp(-0.22, 0.22, random.nextDouble());
         relativeY += 3 + random.nextInt(3);
      }

      int leaderX = leanedX(ground, plan, plan.height() - 2);
      int leaderZ = leanedZ(ground, plan, plan.height() - 2);
      placeLeafBlob(
         level,
         leaderX,
         ground.getY() + plan.height() - 2,
         leaderZ,
         2,
         2,
         leaves,
         seed ^ 0x135F40A672B8DEC9L,
         0.78
      );

      if (plan.height() >= 58 && random.nextInt(4) == 0) {
         int forkStartY = Math.max(crownBase + 3, plan.height() - Math.max(8, crownSpan / 3));
         int forkStartX = leanedX(ground, plan, forkStartY);
         int forkStartZ = leanedZ(ground, plan, forkStartY);
         double angle = rotation + Math.PI * 0.62;
         int forkX = leanedX(ground, plan, plan.height() - 4) + (int)Math.round(Math.cos(angle) * 2.0);
         int forkZ = leanedZ(ground, plan, plan.height() - 4) + (int)Math.round(Math.sin(angle) * 2.0);
         placeLogLine(
            level,
            forkStartX,
            ground.getY() + forkStartY,
            forkStartZ,
            forkX,
            ground.getY() + plan.height() - 4,
            forkZ,
            log,
            0
         );
         placeLeafBlob(
            level,
            forkX,
            ground.getY() + plan.height() - 3,
            forkZ,
            2,
            3,
            leaves,
            seed ^ 0x6E3A1D95C4B2078FL,
            0.72
         );
      }
   }

   private static void growConifer(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x1E638A7A72F4C901L);
      int crownBase = plan.crownBase();
      int crownSpan = Math.max(1, plan.height() - crownBase);
      for (int y = crownBase; y <= plan.height(); y++) {
         double vertical = (y - crownBase) / (double)crownSpan;
         int centerX = leanedX(ground, plan, y);
         int centerZ = leanedZ(ground, plan, y);
         double envelope = Math.pow(Math.max(0.0, 1.0 - vertical), 0.64);
         int radius = Math.max(1, (int)Math.round(plan.crownRadius() * envelope));
         if (((y - crownBase) & 1) == 0 || y >= plan.height() - 2) {
            placeNoisyLeafDisc(level, centerX, ground.getY() + y, centerZ, radius, leaves, seed + y, 0.72);
         }
         if ((y - crownBase) % 3 == 0 && radius >= 2) {
            int branches = radius >= 6 ? 6 : 4;
            for (int branch = 0; branch < branches; branch++) {
               double angle = Math.PI * 2.0 * branch / branches + random.nextDouble() * 0.45;
               int length = Math.max(1, radius - 2 - random.nextInt(2));
               int endX = centerX + (int)Math.round(Math.cos(angle) * length);
               int endZ = centerZ + (int)Math.round(Math.sin(angle) * length);
               placeLogLine(
                  level,
                  centerX,
                  ground.getY() + y,
                  centerZ,
                  endX,
                  ground.getY() + y - random.nextInt(2),
                  endZ,
                  log,
                  0
               );
            }
         }
      }
      placeLeafBlob(
         level,
         leanedX(ground, plan, plan.height() - 1),
         ground.getY() + plan.height() - 1,
         leanedZ(ground, plan, plan.height() - 1),
         2,
         1,
         leaves,
         seed ^ 0x2BEBD83F2F34A6E5L,
         0.78
      );
   }

   /**
    * Open, high-crowned pine form used by tropical/subtropical pine and
    * pine-oak ecoregions. It intentionally avoids the dense triangular spruce
    * silhouette used for boreal and cool-temperate conifers.
    */
   private static void growPine(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x73A2E5C90F184B6DL);
      int crownBase = plan.crownBase();
      int crownSpan = Math.max(1, plan.height() - crownBase);
      double rotation = random.nextDouble() * Math.PI * 2.0;
      for (int relativeY = crownBase; relativeY <= plan.height() - 2; relativeY += 2 + random.nextInt(2)) {
         double vertical = (relativeY - crownBase) / (double)crownSpan;
         double envelope = Math.max(0.22, Math.sin(Math.PI * Math.pow(vertical, 0.82)));
         int reach = Math.max(2, (int)Math.round(plan.crownRadius() * envelope));
         int centerX = leanedX(ground, plan, relativeY);
         int centerZ = leanedZ(ground, plan, relativeY);
         int branches = 3 + random.nextInt(3);
         for (int branch = 0; branch < branches; branch++) {
            double angle = rotation
               + Math.PI * 2.0 * branch / branches
               + lerp(-0.34, 0.34, random.nextDouble());
            int length = Math.max(2, (int)Math.round(reach * lerp(0.72, 1.08, random.nextDouble())));
            int endX = centerX + (int)Math.round(Math.cos(angle) * length);
            int endZ = centerZ + (int)Math.round(Math.sin(angle) * length);
            int endRelativeY = clamp(
               relativeY + (vertical > 0.58 ? 1 + random.nextInt(2) : random.nextInt(2)),
               crownBase,
               plan.height() - 1
            );
            placeLogLine(
               level,
               centerX,
               ground.getY() + relativeY,
               centerZ,
               endX,
               ground.getY() + endRelativeY,
               endZ,
               log,
               0
            );
            placeLeafBlob(
               level,
               endX,
               ground.getY() + endRelativeY + 1,
               endZ,
               Math.max(1, Math.min(3, reach / 2)),
               1 + (vertical > 0.72 ? 1 : 0),
               leaves,
               seed + relativeY * 97L + branch * 181L,
               0.62
            );
         }
         placeLeafBlob(
            level,
            centerX,
            ground.getY() + relativeY + 1,
            centerZ,
            Math.max(1, reach / 3),
            2,
            leaves,
            seed ^ relativeY * 131L,
            0.58
         );
         rotation += 1.67 + lerp(-0.25, 0.25, random.nextDouble());
      }

      placeLeafBlob(
         level,
         leanedX(ground, plan, plan.height() - 1),
         ground.getY() + plan.height() - 1,
         leanedZ(ground, plan, plan.height() - 1),
         2,
         2,
         leaves,
         seed ^ 0x4A702B36C95DE18FL,
         0.68
      );
   }

   /**
    * A tall, open eucalypt crown: a clear pale bole, ascending scaffold
    * branches, and separated foliage clusters rather than an oak-like sphere.
    */
   private static void growEucalyptus(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x31D87C5A94E26B0FL);
      int branchBase = Math.max(3, plan.crownBase() - 2);
      int crownSpan = Math.max(3, plan.height() - branchBase);
      int branches = clamp(5 + plan.crownRadius() / 2, 5, 9);
      for (int branch = 0; branch < branches; branch++) {
         int startRelativeY = branchBase + random.nextInt(Math.max(1, crownSpan / 2));
         int startX = leanedX(ground, plan, startRelativeY);
         int startZ = leanedZ(ground, plan, startRelativeY);
         double angle = Math.PI * 2.0 * branch / branches + random.nextDouble() * 0.82;
         int length = Math.max(2, (int)Math.round(plan.crownRadius() * lerp(0.62, 1.12, random.nextDouble())));
         int endRelativeY = clamp(
            startRelativeY + Math.max(2, crownSpan / 4) + random.nextInt(Math.max(2, crownSpan / 3)),
            startRelativeY + 1,
            plan.height() - 1
         );
         int endX = startX + (int)Math.round(Math.cos(angle) * length);
         int endZ = startZ + (int)Math.round(Math.sin(angle) * length);
         placeLogLine(
            level,
            startX,
            ground.getY() + startRelativeY,
            startZ,
            endX,
            ground.getY() + endRelativeY,
            endZ,
            log,
            0
         );
         int clusterRadius = Math.max(2, Math.min(4, plan.crownRadius() / 2 + random.nextInt(2)));
         placeLeafBlob(
            level,
            endX,
            ground.getY() + endRelativeY,
            endZ,
            clusterRadius,
            Math.max(1, clusterRadius - 1),
            leaves,
            seed + branch * 149L,
            0.56
         );
         if (length >= 4) {
            placeLeafBlob(
               level,
               (startX + endX) >> 1,
               ground.getY() + ((startRelativeY + endRelativeY) >> 1),
               (startZ + endZ) >> 1,
               Math.max(1, clusterRadius - 1),
               1,
               leaves,
               seed ^ branch * 233L,
               0.52
            );
         }
      }

      placeLeafBlob(
         level,
         leanedX(ground, plan, plan.height() - 2),
         ground.getY() + plan.height() - 2,
         leanedZ(ground, plan, plan.height() - 2),
         Math.max(2, plan.crownRadius() / 2),
         2,
         leaves,
         seed ^ 0x587AC4E12D9036BFL,
         0.60
      );
   }

   /** Multi-stemmed mallee growth form, branching from a woody basal hub. */
   private static void growMallee(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x6ED34A91B752C80FL);
      int stems = 4 + random.nextInt(4);
      double rotation = random.nextDouble() * Math.PI * 2.0;
      for (int stem = 0; stem < stems; stem++) {
         double angle = rotation + Math.PI * 2.0 * stem / stems + lerp(-0.28, 0.28, random.nextDouble());
         int outward = 1 + random.nextInt(Math.max(1, plan.crownRadius() / 2 + 1));
         int endRelativeY = clamp(
            plan.height() - random.nextInt(Math.max(2, plan.crownHeight() / 2 + 1)),
            plan.crownBase() + 2,
            plan.height()
         );
         int endX = ground.getX() + (int)Math.round(Math.cos(angle) * outward);
         int endZ = ground.getZ() + (int)Math.round(Math.sin(angle) * outward);
         placeLogLine(
            level,
            ground.getX(),
            ground.getY() + 1,
            ground.getZ(),
            endX,
            ground.getY() + endRelativeY,
            endZ,
            log,
            0
         );
         int clusterRadius = Math.max(2, plan.crownRadius() / 2 + 1);
         placeLeafBlob(
            level,
            endX,
            ground.getY() + endRelativeY,
            endZ,
            clusterRadius,
            Math.max(1, plan.crownHeight() / 4),
            leaves,
            seed + stem * 109L,
            0.64
         );
      }
   }

   /** Wind-shaped, often multi-stemmed birch at the boreal/tundra tree line. */
   private static void growSubarcticBirch(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x5A9C31E7D2048B6FL);
      int stems = 2 + random.nextInt(3);
      for (int stem = 0; stem < stems; stem++) {
         double angle = Math.PI * 2.0 * stem / stems + random.nextDouble() * 0.85;
         int outward = 1 + random.nextInt(2);
         int endRelativeY = Math.max(plan.crownBase() + 2, plan.height() - random.nextInt(4));
         int endX = ground.getX() + (int)Math.round(Math.cos(angle) * outward);
         int endZ = ground.getZ() + (int)Math.round(Math.sin(angle) * outward);
         placeLogLine(
            level,
            ground.getX(),
            ground.getY() + 1,
            ground.getZ(),
            endX,
            ground.getY() + endRelativeY,
            endZ,
            log,
            0
         );
         placeLeafBlob(
            level,
            endX,
            ground.getY() + endRelativeY - 1,
            endZ,
            Math.max(2, plan.crownRadius() / 2 + 1),
            Math.max(2, plan.crownHeight() / 3),
            leaves,
            seed + stem * 83L,
            0.62
         );
      }
   }

   /** Open sclerophyll/stone-pine-like woodland crown with broad separated lobes. */
   private static void growMediterranean(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x18B7D95C4E2A603FL);
      int branchY = Math.max(3, plan.crownBase() - 1);
      int centerX = leanedX(ground, plan, branchY);
      int centerZ = leanedZ(ground, plan, branchY);
      int branches = 4 + random.nextInt(3);
      for (int branch = 0; branch < branches; branch++) {
         double angle = Math.PI * 2.0 * branch / branches + random.nextDouble() * 0.7;
         int length = Math.max(2, plan.crownRadius() - random.nextInt(Math.max(2, plan.crownRadius() / 3 + 1)));
         int endX = centerX + (int)Math.round(Math.cos(angle) * length);
         int endZ = centerZ + (int)Math.round(Math.sin(angle) * length);
         int endRelativeY = clamp(
            branchY + 2 + random.nextInt(Math.max(2, plan.crownHeight() / 2 + 1)),
            branchY + 1,
            plan.height() - 1
         );
         placeLogLine(
            level,
            centerX,
            ground.getY() + branchY,
            centerZ,
            endX,
            ground.getY() + endRelativeY,
            endZ,
            log,
            0
         );
         placeLeafBlob(
            level,
            endX,
            ground.getY() + endRelativeY,
            endZ,
            Math.max(2, plan.crownRadius() / 2 + 1),
            Math.max(1, plan.crownHeight() / 5),
            leaves,
            seed + branch * 127L,
            0.66
         );
      }
      placeLeafBlob(
         level,
         leanedX(ground, plan, plan.height() - 2),
         ground.getY() + plan.height() - 2,
         leanedZ(ground, plan, plan.height() - 2),
         Math.max(2, plan.crownRadius() / 2),
         2,
         leaves,
         seed,
         0.60
      );
   }

   private static void growSavanna(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed
   ) {
      Random random = new Random(seed ^ 0x55D4AC317B96590DL);
      int branchY = Math.max(3, plan.crownBase() - 1);
      int centerX = leanedX(ground, plan, branchY);
      int centerZ = leanedZ(ground, plan, branchY);
      int branches = 4 + random.nextInt(3);
      int crownVerticalRadius = Math.max(2, plan.crownHeight() / 5);
      int crownRelativeY = Math.max(branchY + 2, plan.height() - crownVerticalRadius);
      for (int branch = 0; branch < branches; branch++) {
         double angle = Math.PI * 2.0 * branch / branches + random.nextDouble() * 0.65;
         int length = Math.max(3, plan.crownRadius() - random.nextInt(3));
         int endX = centerX + (int)Math.round(Math.cos(angle) * length);
         int endZ = centerZ + (int)Math.round(Math.sin(angle) * length);
         int endY = ground.getY() + crownRelativeY + random.nextInt(3) - 1;
         endY = Math.min(ground.getY() + plan.height() - crownVerticalRadius, endY);
         placeLogLine(level, centerX, ground.getY() + branchY, centerZ, endX, endY, endZ, log, plan.trunkRadius() >= 3 ? 1 : 0);
         placeLeafBlob(
            level,
            endX,
            endY + 1,
            endZ,
            Math.max(2, plan.crownRadius() / 2 + 1),
            crownVerticalRadius,
            leaves,
            seed + branch * 31L,
            0.82
         );
      }
      int crownCenterY = ground.getY() + plan.height() - crownVerticalRadius;
      placeLeafBlob(
         level,
         leanedX(ground, plan, plan.crownBase()),
         crownCenterY,
         leanedZ(ground, plan, plan.crownBase()),
         plan.crownRadius(),
         crownVerticalRadius,
         leaves,
         seed,
         0.70
      );
   }

   private static void growBroadleaf(
      WorldGenLevel level,
      BlockPos ground,
      TreePlan plan,
      BlockState log,
      BlockState leaves,
      long seed,
      double horizontalScale
   ) {
      Random random = new Random(seed ^ 0x2A6F82D1C64E750BL);
      int branchBase = Math.max(3, plan.crownBase() - 1);
      int branches = clamp(4 + plan.crownRadius() / 2, 4, 10);
      for (int branch = 0; branch < branches; branch++) {
         int startY = branchBase + random.nextInt(Math.max(1, plan.crownHeight() / 2));
         int startX = leanedX(ground, plan, startY);
         int startZ = leanedZ(ground, plan, startY);
         double angle = Math.PI * 2.0 * branch / branches + random.nextDouble() * 0.72;
         int length = Math.max(2, (int)Math.round(plan.crownRadius() * horizontalScale * lerp(0.62, 1.0, random.nextDouble())));
         int endX = startX + (int)Math.round(Math.cos(angle) * length);
         int endZ = startZ + (int)Math.round(Math.sin(angle) * length);
         int endY = ground.getY()
            + Math.min(plan.height() - 1, startY + 1 + random.nextInt(Math.max(2, plan.crownHeight() / 3 + 1)));
         placeLogLine(level, startX, ground.getY() + startY, startZ, endX, endY, endZ, log, plan.trunkRadius() >= 3 ? 1 : 0);
      }

      int centerRelativeY = plan.crownBase() + Math.max(1, plan.crownHeight() / 2);
      int horizontalRadius = Math.max(2, (int)Math.round(plan.crownRadius() * horizontalScale));
      int verticalRadius = Math.max(1, plan.height() - centerRelativeY);
      double centralDensity = switch (plan.profile()) {
         case DARK_BROADLEAF, PALE_BROADLEAF -> 0.86;
         case DRY_BROADLEAF -> 0.62;
         default -> 0.76;
      };
      placeLeafBlob(
         level,
         leanedX(ground, plan, centerRelativeY),
         ground.getY() + centerRelativeY,
         leanedZ(ground, plan, centerRelativeY),
         horizontalRadius,
         verticalRadius,
         leaves,
         seed,
         centralDensity
      );

      int lobes = clamp(3 + plan.crownRadius() / 3, 3, 7);
      for (int lobe = 0; lobe < lobes; lobe++) {
         double angle = Math.PI * 2.0 * lobe / lobes + random.nextDouble() * 0.7;
         int offset = Math.max(1, horizontalRadius / 2);
         int lobeX = leanedX(ground, plan, centerRelativeY) + (int)Math.round(Math.cos(angle) * offset);
         int lobeZ = leanedZ(ground, plan, centerRelativeY) + (int)Math.round(Math.sin(angle) * offset);
         int lobeVerticalRadius = Math.max(1, verticalRadius / 2);
         int lobeY = ground.getY() + centerRelativeY + random.nextInt(5) - 2;
         lobeY = clamp(
            lobeY,
            ground.getY() + plan.crownBase() + lobeVerticalRadius,
            ground.getY() + plan.height() - lobeVerticalRadius
         );
         placeLeafBlob(
            level,
            lobeX,
            lobeY,
            lobeZ,
            Math.max(2, horizontalRadius / 2 + 1),
            lobeVerticalRadius,
            leaves,
            seed + lobe * 101L,
            plan.profile() == Profile.DRY_BROADLEAF ? 0.58 : 0.72
         );
      }
   }

   private static void placeLogLine(
      WorldGenLevel level,
      int startX,
      int startY,
      int startZ,
      int endX,
      int endY,
      int endZ,
      BlockState log,
      int radius
   ) {
      int dx = endX - startX;
      int dy = endY - startY;
      int dz = endZ - startZ;
      int steps = Math.max(1, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
      Direction.Axis branchAxis = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)
         ? Direction.Axis.Y
         : Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
      BlockState oriented = axis(log, branchAxis);
      for (int step = 0; step <= steps; step++) {
         double progress = step / (double)steps;
         int x = (int)Math.round(startX + dx * progress);
         int y = (int)Math.round(startY + dy * progress);
         int z = (int)Math.round(startZ + dz * progress);
         placeLogDisc(level, x, y, z, radius, oriented);
      }
   }

   private static void placeLogDisc(
      WorldGenLevel level, int centerX, int y, int centerZ, int radius, BlockState log
   ) {
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int dz = -radius; dz <= radius; dz++) {
         for (int dx = -radius; dx <= radius; dx++) {
            if (dx * dx + dz * dz > radius * radius + radius) {
               continue;
            }
            cursor.set(centerX + dx, y, centerZ + dz);
            setLog(level, cursor, log);
         }
      }
   }

   private static void placeNoisyLeafDisc(
      WorldGenLevel level,
      int centerX,
      int y,
      int centerZ,
      int radius,
      BlockState leaves,
      long seed,
      double density
   ) {
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      double radiusSquared = Math.max(1.0, radius * radius);
      for (int dz = -radius; dz <= radius; dz++) {
         for (int dx = -radius; dx <= radius; dx++) {
            double distance = (dx * dx + dz * dz) / radiusSquared;
            if (distance > 1.0 || unitHash(seed, centerX + dx, y, centerZ + dz) > density + (1.0 - distance) * 0.20) {
               continue;
            }
            cursor.set(centerX + dx, y, centerZ + dz);
            setLeaves(level, cursor, leaves);
         }
      }
   }

   private static void placeLeafBlob(
      WorldGenLevel level,
      int centerX,
      int centerY,
      int centerZ,
      int horizontalRadius,
      int verticalRadius,
      BlockState leaves,
      long seed,
      double density
   ) {
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      double horizontalSquared = Math.max(1.0, horizontalRadius * horizontalRadius);
      double verticalSquared = Math.max(1.0, verticalRadius * verticalRadius);
      for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
         double verticalDistance = dy * dy / verticalSquared;
         for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
               double normalized = (dx * dx + dz * dz) / horizontalSquared + verticalDistance;
               if (normalized > 1.0) {
                  continue;
               }
               double localDensity = density + Math.max(0.0, 0.26 * (1.0 - normalized));
               if (unitHash(seed, centerX + dx, centerY + dy, centerZ + dz) > localDensity) {
                  continue;
               }
               cursor.set(centerX + dx, centerY + dy, centerZ + dz);
               setLeaves(level, cursor, leaves);
            }
         }
      }
   }

   private static void setLog(WorldGenLevel level, BlockPos position, BlockState log) {
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, position) || !level.ensureCanWrite(position)) {
         return;
      }
      BlockState current = level.getBlockState(position);
      if (canReplaceTrunk(current)) {
         level.setBlock(position, log, PLACEMENT_FLAGS);
      }
   }

   private static boolean setRootLog(
      WorldGenLevel level, BlockPos position, BlockState log
   ) {
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, position)
         || !level.ensureCanWrite(position)
         || !level.getFluidState(position).isEmpty()) {
         return false;
      }
      BlockState current = level.getBlockState(position);
      if (current.is(BlockTags.LOGS)) {
         return true;
      }
      if (!canReplaceTrunk(current) && !current.canBeReplaced()) {
         return false;
      }
      return level.setBlock(position, log, PLACEMENT_FLAGS);
   }

   private static void setLeaves(WorldGenLevel level, BlockPos position, BlockState leaves) {
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, position) || !level.ensureCanWrite(position)) {
         return;
      }
      BlockState current = level.getBlockState(position);
      if (current.isAir() || current.is(BlockTags.LEAVES) || current.is(BlockTags.REPLACEABLE_BY_TREES)) {
         level.setBlock(position, leaves, PLACEMENT_FLAGS);
      }
   }

   private static boolean canReplaceTrunk(BlockState state) {
      return state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE_BY_TREES);
   }

   private static boolean canPlaceTrunkAt(WorldGenLevel level, BlockPos position) {
      return MinecraftVersionCompat.isInsideBuildHeight(level, position)
         && level.ensureCanWrite(position)
         && level.getFluidState(position).isEmpty()
         && canReplaceTrunk(level.getBlockState(position));
   }

   private static boolean canPlaceRootAt(WorldGenLevel level, BlockPos position) {
      if (!MinecraftVersionCompat.isInsideBuildHeight(level, position)
         || !level.ensureCanWrite(position)
         || !level.getFluidState(position).isEmpty()) {
         return false;
      }
      BlockState current = level.getBlockState(position);
      return current.is(BlockTags.LOGS)
         || canReplaceTrunk(current)
         || current.canBeReplaced();
   }

   @FunctionalInterface
   interface RootSurfaceSampler {
      int surfaceY(int worldX, int worldZ);
   }

   @FunctionalInterface
   public interface BasalSurfaceSampler {
      int surfaceY(int worldX, int worldZ);
   }

   @FunctionalInterface
   interface TrunkPlacementTester {
      boolean canPlace(int worldX, int worldY, int worldZ);
   }

   record RootColumn(int worldX, int worldZ, int surfaceY, int topY) {
   }

   record TrunkBlock(int worldX, int worldY, int worldZ) {
   }

   public record BasalColumn(int worldX, int worldZ, int surfaceY) {
   }

   public record BasalFootprintPlan(
      boolean valid, int targetY, List<BasalColumn> columns
   ) {
      public BasalFootprintPlan {
         columns = List.copyOf(columns);
      }

      private static BasalFootprintPlan invalid(int targetY) {
         return new BasalFootprintPlan(false, targetY, List.of());
      }
   }

   record TrunkPlacementPlan(
      boolean valid, List<TrunkBlock> supports, List<TrunkBlock> trunk
   ) {
      TrunkPlacementPlan {
         supports = List.copyOf(supports);
         trunk = List.copyOf(trunk);
      }

      static TrunkPlacementPlan invalid() {
         return new TrunkPlacementPlan(false, List.of(), List.of());
      }
   }

   private static BlockState persistentLeaves(BlockState leaves) {
      return leaves.hasProperty(BlockStateProperties.PERSISTENT)
         ? leaves.setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE)
         : leaves;
   }

   private static BlockState axis(BlockState log, Direction.Axis axis) {
      return log.hasProperty(BlockStateProperties.AXIS) ? log.setValue(BlockStateProperties.AXIS, axis) : log;
   }

   private static int leanedX(BlockPos ground, TreePlan plan, int relativeY) {
      return ground.getX() + (int)Math.round(plan.leanX() * relativeY / (double)Math.max(1, plan.height()));
   }

   private static int leanedZ(BlockPos ground, TreePlan plan, int relativeY) {
      return ground.getZ() + (int)Math.round(plan.leanZ() * relativeY / (double)Math.max(1, plan.height()));
   }

   public static boolean isCoastRedwoodEcoregion(ResolveEcoregion ecoregion) {
      return ecoregion != null
         && ecoregion.available()
         && ecoregion.ecoId() == NORTHERN_CALIFORNIA_COASTAL_FORESTS_ID;
   }

   static Profile regionalProfile(Profile biomeProfile, ResolveEcoregion ecoregion, long seed) {
      if (ecoregion == null || !ecoregion.available()) {
         return biomeProfile;
      }

      int roll = (int)((mix(seed ^ REGIONAL_PROFILE_SALT) >>> 1) % 100L);
      if (isCoastRedwoodEcoregion(ecoregion)
         && (biomeProfile == Profile.CONIFER || biomeProfile == Profile.TALL_CONIFER || biomeProfile == Profile.PINE)) {
         if (roll < 62) {
            return Profile.COAST_REDWOOD;
         }
         if (roll < 86) {
            return Profile.TALL_CONIFER;
         }
         return Profile.TEMPERATE_BROADLEAF;
      }

      if (ecoregion.ecoId() == ICELAND_BOREAL_BIRCH_FORESTS_ID
         || ecoregion.ecoId() == SCANDINAVIAN_MONTANE_BIRCH_FORESTS_ID) {
         return roll < 86 ? Profile.SUBARCTIC_BIRCH : Profile.CONIFER;
      }

      String regionalName = ecoregion.name().toLowerCase(Locale.ROOT);
      if (regionalName.contains("mallee")) {
         return roll < 84 ? Profile.MALLEE : Profile.EUCALYPTUS;
      }

      if (regionalName.contains("pine-oak")) {
         return roll < 68 ? Profile.PINE : Profile.TEMPERATE_BROADLEAF;
      }

      if (ecoregion.biome() == ResolveBiome.TROPICAL_CONIFEROUS_FORESTS) {
         return roll < 82 ? Profile.PINE : Profile.TEMPERATE_BROADLEAF;
      }

      boolean mediterraneanRegion = ecoregion.biome() == ResolveBiome.MEDITERRANEAN_FORESTS_WOODLANDS_SCRUB
         || regionalName.contains("mediterranean")
         || regionalName.contains("chaparral")
         || regionalName.contains("matorral")
         || regionalName.contains("sclerophyll");
      if (mediterraneanRegion && canUseDryWoodlandProfile(biomeProfile)) {
         if (ecoregion.realm() == ResolveRealm.AUSTRALASIA) {
            return roll < 82 ? Profile.EUCALYPTUS : Profile.MEDITERRANEAN;
         }
         if (roll < 58) {
            return Profile.MEDITERRANEAN;
         }
         return roll < 78 ? Profile.PINE : Profile.TEMPERATE_BROADLEAF;
      }

      if (ecoregion.realm() == ResolveRealm.AUSTRALASIA
         && isAustralianEucalyptBiome(ecoregion.biome())
         && canUseEucalyptProfile(biomeProfile)) {
         return roll < 76 ? Profile.EUCALYPTUS : biomeProfile;
      }

      if (ecoregion.biome() == ResolveBiome.TROPICAL_DRY_BROADLEAF_FORESTS
         && canUseDryWoodlandProfile(biomeProfile)) {
         if (roll < 68) {
            return Profile.DRY_BROADLEAF;
         }
         return roll < 90 ? Profile.SAVANNA : Profile.TROPICAL;
      }

      return biomeProfile;
   }

   private static boolean canUseDryWoodlandProfile(Profile profile) {
      return profile == Profile.TEMPERATE_BROADLEAF
         || profile == Profile.DARK_BROADLEAF
         || profile == Profile.TROPICAL
         || profile == Profile.SAVANNA
         || profile == Profile.CONIFER
         || profile == Profile.TALL_CONIFER;
   }

   private static boolean canUseEucalyptProfile(Profile profile) {
      return profile == Profile.TEMPERATE_BROADLEAF
         || profile == Profile.DARK_BROADLEAF
         || profile == Profile.SAVANNA
         || profile == Profile.CONIFER;
   }

   private static boolean isAustralianEucalyptBiome(ResolveBiome biome) {
      return biome == ResolveBiome.TEMPERATE_BROADLEAF_MIXED_FORESTS
         || biome == ResolveBiome.TROPICAL_DRY_BROADLEAF_FORESTS
         || biome == ResolveBiome.TROPICAL_GRASSLANDS_SAVANNAS_SHRUBLANDS
         || biome == ResolveBiome.TEMPERATE_GRASSLANDS_SAVANNAS_SHRUBLANDS;
   }

   private static double calibratedCanopyHeight(Profile profile, double measuredHeightMeters) {
      if (profile != Profile.COAST_REDWOOD || !Double.isFinite(measuredHeightMeters)) {
         return measuredHeightMeters;
      }
      return calibratedCoastRedwoodHeight(measuredHeightMeters);
   }

   public static double calibratedCoastRedwoodHeight(double measuredHeightMeters) {
      return Double.isFinite(measuredHeightMeters)
         ? Math.min(Profile.COAST_REDWOOD.maximumHeight(), measuredHeightMeters * COAST_REDWOOD_HEIGHT_CALIBRATION)
         : measuredHeightMeters;
   }

   private static double crownBaseRatio(Profile profile, int height) {
      if (profile != Profile.COAST_REDWOOD) {
         return profile.crownBaseRatio();
      }
      if (height < 28) {
         return 0.30;
      }
      if (height < 48) {
         return lerp(0.42, 0.56, (height - 28) / 20.0);
      }
      return Math.min(0.66, 0.58 + (height - 48) * 0.0015);
   }

   private static Profile profileForBiome(Holder<Biome> biome) {
      if (biome.is(Biomes.CHERRY_GROVE)) {
         return Profile.CHERRY;
      }
      if (MinecraftVersionCompat.isPaleGarden(biome)) {
         return Profile.PALE_BROADLEAF;
      }
      if (biome.is(BiomeTags.IS_JUNGLE)) {
         return Profile.TROPICAL;
      }
      if (biome.is(BiomeTags.IS_SAVANNA)) {
         return Profile.SAVANNA;
      }
      if (biome.is(Biomes.OLD_GROWTH_PINE_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
         return Profile.TALL_CONIFER;
      }
      if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Biomes.GROVE) || biome.is(Biomes.WINDSWEPT_FOREST)) {
         return Profile.CONIFER;
      }
      if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
         return Profile.BIRCH;
      }
      if (biome.is(Biomes.DARK_FOREST)) {
         return Profile.DARK_BROADLEAF;
      }
      if (biome.is(Biomes.SWAMP)) {
         return Profile.SWAMP;
      }
      return Profile.TEMPERATE_BROADLEAF;
   }

   private static Profile profileForBiome(ResourceKey<Biome> biomeKey) {
      if (Biomes.CHERRY_GROVE.equals(biomeKey)) {
         return Profile.CHERRY;
      }
      if (MinecraftVersionCompat.isPaleGarden(biomeKey)) {
         return Profile.PALE_BROADLEAF;
      }
      if (Biomes.JUNGLE.equals(biomeKey)
         || Biomes.BAMBOO_JUNGLE.equals(biomeKey)
         || Biomes.SPARSE_JUNGLE.equals(biomeKey)) {
         return Profile.TROPICAL;
      }
      if (Biomes.SAVANNA.equals(biomeKey)
         || Biomes.SAVANNA_PLATEAU.equals(biomeKey)
         || Biomes.WINDSWEPT_SAVANNA.equals(biomeKey)) {
         return Profile.SAVANNA;
      }
      if (Biomes.OLD_GROWTH_PINE_TAIGA.equals(biomeKey)
         || Biomes.OLD_GROWTH_SPRUCE_TAIGA.equals(biomeKey)) {
         return Profile.TALL_CONIFER;
      }
      if (Biomes.TAIGA.equals(biomeKey)
         || Biomes.SNOWY_TAIGA.equals(biomeKey)
         || Biomes.GROVE.equals(biomeKey)
         || Biomes.WINDSWEPT_FOREST.equals(biomeKey)) {
         return Profile.CONIFER;
      }
      if (Biomes.BIRCH_FOREST.equals(biomeKey) || Biomes.OLD_GROWTH_BIRCH_FOREST.equals(biomeKey)) {
         return Profile.BIRCH;
      }
      if (Biomes.DARK_FOREST.equals(biomeKey)) {
         return Profile.DARK_BROADLEAF;
      }
      if (Biomes.SWAMP.equals(biomeKey) || Biomes.MANGROVE_SWAMP.equals(biomeKey)) {
         return Profile.SWAMP;
      }
      return Profile.TEMPERATE_BROADLEAF;
   }

   /** Material state shared with coarse LOD columns for a planned tree. */
   public static BlockState logState(Profile profile, long seed) {
      return palette(profile, seed).log().defaultBlockState();
   }

   /** Material state shared with coarse LOD columns for a planned tree. */
   public static BlockState leavesState(Profile profile, long seed) {
      return persistentLeaves(palette(profile, seed).leaves().defaultBlockState());
   }

   private static Palette palette(Profile profile, long seed) {
      return switch (profile) {
         case CONIFER, TALL_CONIFER, COAST_REDWOOD -> new Palette(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
         case PINE -> new Palette(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
         case TROPICAL -> new Palette(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES);
         case DRY_BROADLEAF -> (mix(seed) & 3L) == 0L
            ? new Palette(Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES)
            : new Palette(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES);
         case SAVANNA -> new Palette(Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES);
         case BIRCH, SUBARCTIC_BIRCH -> new Palette(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
         case EUCALYPTUS, MALLEE -> (mix(seed) & 3L) == 0L
            ? new Palette(Blocks.BIRCH_LOG, Blocks.ACACIA_LEAVES)
            : new Palette(MinecraftVersionCompat.paleOakLogOr(Blocks.ACACIA_LOG), Blocks.ACACIA_LEAVES);
         case MEDITERRANEAN -> (mix(seed) & 3L) == 0L
            ? new Palette(Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES)
            : new Palette(Blocks.OAK_LOG, Blocks.OAK_LEAVES);
         case DARK_BROADLEAF -> new Palette(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES);
         case CHERRY -> new Palette(Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES);
         case PALE_BROADLEAF -> new Palette(
            MinecraftVersionCompat.paleOakLogOr(Blocks.DARK_OAK_LOG),
            MinecraftVersionCompat.paleOakLeavesOr(Blocks.DARK_OAK_LEAVES)
         );
         case SWAMP -> new Palette(Blocks.OAK_LOG, Blocks.OAK_LEAVES);
         case TEMPERATE_BROADLEAF -> (mix(seed) & 7L) == 0L
            ? new Palette(Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES)
            : new Palette(Blocks.OAK_LOG, Blocks.OAK_LEAVES);
      };
   }

   private static long seedFromCoords(int x, int y, int z) {
      long seed = x * 3129871 ^ z * 116129781L ^ y;
      seed = seed * seed * 42317861L + seed * 11L;
      return seed >> 16;
   }

   private static double unitHash(long seed, int x, int y, int z) {
      long value = seed;
      value ^= (long)x * 0x632BE59BD9B4E019L;
      value ^= (long)y * 0x9E3779B97F4A7C15L;
      value ^= (long)z * 0x94D049BB133111EBL;
      return (mix(value) >>> 11) * 0x1.0p-53;
   }

   private static long mix(long value) {
      value ^= value >>> 30;
      value *= 0xBF58476D1CE4E5B9L;
      value ^= value >>> 27;
      value *= 0x94D049BB133111EBL;
      return value ^ value >>> 31;
   }

   private static double lerp(double start, double end, double progress) {
      return start + (end - start) * progress;
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   public enum Profile {
      TEMPERATE_BROADLEAF(12, 34, 64, 0.25, 3, 11, 0.45, 0.05, false),
      DARK_BROADLEAF(14, 38, 64, 0.28, 4, 12, 0.40, 0.04, false),
      PALE_BROADLEAF(12, 30, 52, 0.26, 3, 11, 0.42, 0.04, false),
      BIRCH(10, 27, 42, 0.16, 2, 7, 0.43, 0.03, false),
      SUBARCTIC_BIRCH(5, 14, 22, 0.22, 2, 6, 0.28, 0.10, false),
      CHERRY(8, 22, 36, 0.25, 3, 9, 0.44, 0.05, false),
      CONIFER(14, 38, 68, 0.17, 3, 10, 0.28, 0.025, true),
      TALL_CONIFER(24, 58, 96, 0.15, 4, 11, 0.30, 0.02, true),
      PINE(14, 38, 62, 0.18, 3, 9, 0.48, 0.035, true),
      COAST_REDWOOD(42, 78, 112, 0.11, 3, 9, 0.58, 0.008, true),
      TROPICAL(18, 52, 96, 0.24, 4, 12, 0.48, 0.06, false),
      DRY_BROADLEAF(10, 30, 48, 0.28, 3, 10, 0.52, 0.08, false),
      EUCALYPTUS(14, 38, 70, 0.18, 3, 8, 0.56, 0.07, false),
      MALLEE(5, 12, 20, 0.38, 3, 8, 0.25, 0.08, false),
      MEDITERRANEAN(8, 24, 38, 0.32, 3, 10, 0.48, 0.10, false),
      SAVANNA(7, 18, 30, 0.34, 3, 10, 0.56, 0.12, false),
      SWAMP(10, 28, 48, 0.27, 3, 11, 0.42, 0.08, false);

      private final int fallbackLow;
      private final int fallbackHigh;
      private final int maximumHeight;
      private final double crownRadiusRatio;
      private final int minimumCrownRadius;
      private final int maximumCrownRadius;
      private final double crownBaseRatio;
      private final double leanRatio;
      private final boolean conifer;

      Profile(
         int fallbackLow,
         int fallbackHigh,
         int maximumHeight,
         double crownRadiusRatio,
         int minimumCrownRadius,
         int maximumCrownRadius,
         double crownBaseRatio,
         double leanRatio,
         boolean conifer
      ) {
         this.fallbackLow = fallbackLow;
         this.fallbackHigh = fallbackHigh;
         this.maximumHeight = maximumHeight;
         this.crownRadiusRatio = crownRadiusRatio;
         this.minimumCrownRadius = minimumCrownRadius;
         this.maximumCrownRadius = maximumCrownRadius;
         this.crownBaseRatio = crownBaseRatio;
         this.leanRatio = leanRatio;
         this.conifer = conifer;
      }

      int fallbackLow() {
         return this.fallbackLow;
      }

      int fallbackHigh() {
         return this.fallbackHigh;
      }

      int maximumHeight() {
         return this.maximumHeight;
      }

      double crownRadiusRatio() {
         return this.crownRadiusRatio;
      }

      int minimumCrownRadius() {
         return this.minimumCrownRadius;
      }

      int maximumCrownRadius() {
         return this.maximumCrownRadius;
      }

      double crownBaseRatio() {
         return this.crownBaseRatio;
      }

      double leanRatio() {
         return this.leanRatio;
      }

      boolean conifer() {
         return this.conifer;
      }
   }

   public record TreeAnchor(int worldX, int worldZ, long seed) {
   }

   public record TreePlan(
      Profile profile,
      int height,
      int trunkRadius,
      int crownRadius,
      int crownBase,
      int crownHeight,
      int leanX,
      int leanZ,
      boolean bush,
      boolean dataDriven
   ) {
      private static TreePlan bush(Profile profile, boolean dataDriven) {
         return new TreePlan(
            profile, BUSH_HEIGHT, 1, BUSH_CROWN_RADIUS, 1, BUSH_HEIGHT, 0, 0, true, dataDriven
         );
      }

      public boolean present() {
         return this.bush || this.height >= MIN_TREE_HEIGHT;
      }

      public TreePlan withHeight(int newHeight) {
         if (this.bush) {
            return this;
         }
         double scale = newHeight / (double)Math.max(1, this.height);
         return new TreePlan(
            this.profile,
            newHeight,
            Math.max(1, (int)Math.round(this.trunkRadius * scale)),
            Math.max(2, (int)Math.round(this.crownRadius * scale)),
            clamp((int)Math.round(this.crownBase * scale), 2, Math.max(2, newHeight - 2)),
            Math.max(3, (int)Math.round(this.crownHeight * scale)),
            (int)Math.round(this.leanX * scale),
            (int)Math.round(this.leanZ * scale),
            false,
            this.dataDriven
         );
      }
   }

   private record Palette(Block log, Block leaves) {
   }
}
