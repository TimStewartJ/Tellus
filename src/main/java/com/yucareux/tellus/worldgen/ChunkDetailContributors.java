package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.api.detail.ChunkDetailApplyContext;
import com.yucareux.tellus.api.detail.ChunkDetailArea;
import com.yucareux.tellus.api.detail.ChunkDetailClaim;
import com.yucareux.tellus.api.detail.ChunkDetailContributor;
import com.yucareux.tellus.api.detail.ChunkDetailContributorRegistry;
import com.yucareux.tellus.api.detail.ChunkDetailDomain;
import com.yucareux.tellus.api.detail.ChunkDetailLodPlan;
import com.yucareux.tellus.api.detail.ChunkDetailLodPlanContext;
import com.yucareux.tellus.api.detail.ChunkDetailLodPendingException;
import com.yucareux.tellus.api.detail.ChunkDetailLodPlanResult;
import com.yucareux.tellus.api.detail.ChunkDetailPlan;
import com.yucareux.tellus.api.detail.ChunkDetailPlanContext;
import com.yucareux.tellus.api.detail.ChunkDetailPlanResult;
import com.yucareux.tellus.api.detail.ChunkDetailWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Validation and deterministic arbitration for the public chunk-detail contract.
 */
final class ChunkDetailContributors {
   private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(ChunkDetailContributors.class);

   private ChunkDetailContributors() {
   }

   static void preflight(
      ChunkDetailContributorRegistry.Snapshot snapshot,
      ChunkDetailPlanContext context,
      boolean failOpenPending
   ) {
      List<String> pendingReasons = new ArrayList<>();
      int retryAfterTicks = 1;
      for (ChunkDetailContributorRegistry.Entry registration : snapshot.entries()) {
         ChunkDetailContributor contributor = registration.contributor();
         boolean active;
         try {
            active = contributor.active();
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed its active-state probe",
               registration.identifier(),
               error
            );
            continue;
         }
         if (!active) {
            continue;
         }
         long revision = contributor.revision();
         ChunkDetailPlanResult result;
         try {
            result = Objects.requireNonNull(
               contributor.preflight(context), "contributor preflight result"
            );
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed preflight for chunk {},{}",
               registration.identifier(),
               context.chunkX(),
               context.chunkZ(),
               error
            );
            continue;
         }
         if (contributor.revision() != revision) {
            result = ChunkDetailPlanResult.pending(
               1, "contributor revision changed during preflight"
            );
         }
         if (result instanceof ChunkDetailPlanResult.Pending pending) {
            if (failOpenPending) {
               LOGGER.warn(
                  "Chunk-detail contributor {} remained pending during preflight for chunk {},{}; continuing: {}",
                  registration.identifier(),
                  context.chunkX(),
                  context.chunkZ(),
                  pending.reason()
               );
            } else {
               retryAfterTicks = Math.max(
                  retryAfterTicks, pending.retryAfterTicks()
               );
               pendingReasons.add(
                  registration.identifier() + ": " + pending.reason()
               );
            }
         } else if (result instanceof ChunkDetailPlanResult.Failed failed) {
            if (failed.retryable() && !failOpenPending) {
               retryAfterTicks = Math.max(retryAfterTicks, 20);
               pendingReasons.add(
                  registration.identifier() + ": " + failed.reason()
               );
            } else {
               LOGGER.error(
                  "Chunk-detail contributor {} declined preflight for chunk {},{}: {}",
                  registration.identifier(),
                  context.chunkX(),
                  context.chunkZ(),
                  failed.reason()
               );
            }
         }
      }
      if (!pendingReasons.isEmpty()) {
         throw new PendingException(
            retryAfterTicks, String.join("; ", pendingReasons)
         );
      }
   }

   static Preparation prepare(
      ChunkDetailContributorRegistry.Snapshot snapshot,
      ChunkDetailPlanContext context,
      boolean failOpenPending
   ) {
      if (snapshot.isEmpty()) {
         return Preparation.empty(context);
      }

      List<PreparedPlan> plans = new ArrayList<>(snapshot.entries().size());
      List<String> pendingReasons = new ArrayList<>();
      int retryAfterTicks = 1;
      for (ChunkDetailContributorRegistry.Entry registration : snapshot.entries()) {
         ChunkDetailContributor contributor = registration.contributor();
         boolean active;
         try {
            active = contributor.active();
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed its active-state probe",
               registration.identifier(),
               error
            );
            continue;
         }
         if (!active) {
            continue;
         }
         long revision = contributor.revision();
         ChunkDetailPlanResult result;
         try {
            result = Objects.requireNonNull(contributor.plan(context), "contributor plan result");
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed while planning chunk {},{}",
               registration.identifier(),
               context.chunkX(),
               context.chunkZ(),
               error
            );
            continue;
         }

         if (contributor.revision() != revision) {
            result = ChunkDetailPlanResult.pending(1, "contributor revision changed during planning");
         }
         if (result instanceof ChunkDetailPlanResult.Ready ready) {
            try {
               plans.add(validate(registration, revision, ready.plan(), context));
            } catch (RuntimeException error) {
               LOGGER.error(
                  "Chunk-detail contributor {} returned an invalid plan for chunk {},{}",
                  registration.identifier(),
                  context.chunkX(),
                  context.chunkZ(),
                  error
               );
            }
         } else if (result instanceof ChunkDetailPlanResult.Pending pending) {
            if (failOpenPending) {
               LOGGER.warn(
                  "Chunk-detail contributor {} remained pending for chunk {},{}; continuing without its claims: {}",
                  registration.identifier(),
                  context.chunkX(),
                  context.chunkZ(),
                  pending.reason()
               );
            } else {
               retryAfterTicks = Math.max(retryAfterTicks, pending.retryAfterTicks());
               pendingReasons.add(registration.identifier() + ": " + pending.reason());
            }
         } else if (result instanceof ChunkDetailPlanResult.Failed failed) {
            if (failed.retryable() && !failOpenPending) {
               retryAfterTicks = Math.max(retryAfterTicks, 20);
               pendingReasons.add(registration.identifier() + ": " + failed.reason());
            } else {
               LOGGER.error(
                  "Chunk-detail contributor {} declined chunk {},{}: {}",
                  registration.identifier(),
                  context.chunkX(),
                  context.chunkZ(),
                  failed.reason()
               );
            }
         }

      }

      if (!pendingReasons.isEmpty()) {
         throw new PendingException(retryAfterTicks, String.join("; ", pendingReasons));
      }
      try {
         return resolve(context, plans);
      } catch (RuntimeException error) {
         LOGGER.error(
            "Could not resolve chunk-detail claims for chunk {},{}; continuing with native detail",
            context.chunkX(),
            context.chunkZ(),
            error
         );
         return Preparation.empty(context);
      }
   }

   static ChunkDetailLodPlan prepareLodExclusions(
      ChunkDetailContributorRegistry.Snapshot snapshot,
      ChunkDetailLodPlanContext context
   ) {
      if (snapshot.isEmpty()) {
         return ChunkDetailLodPlan.none();
      }

      List<PreparedLodPlan> plans = new ArrayList<>(snapshot.entries().size());
      List<String> pendingReasons = new ArrayList<>();
      int retryAfterTicks = 1;
      for (ChunkDetailContributorRegistry.Entry registration : snapshot.entries()) {
         ChunkDetailContributor contributor = registration.contributor();
         if (!usesLodExclusionDomain(contributor.domains())) {
            continue;
         }
         boolean active;
         try {
            active = contributor.active();
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed its active-state probe for LOD planning",
               registration.identifier(),
               error
            );
            continue;
         }
         if (!active) {
            continue;
         }

         long revision = contributor.revision();
         ChunkDetailLodPlanResult result;
         try {
            result = Objects.requireNonNull(
               contributor.planLodExclusions(context),
               "contributor LOD plan result"
            );
         } catch (RuntimeException error) {
            LOGGER.error(
               "Chunk-detail contributor {} failed LOD exclusion planning for [{},{}]-[{},{}]",
               registration.identifier(),
               context.minBlockX(),
               context.minBlockZ(),
               context.maxBlockX(),
               context.maxBlockZ(),
               error
            );
            continue;
         }
         if (contributor.revision() != revision) {
            result = ChunkDetailLodPlanResult.pending(
               1, "contributor revision changed during LOD planning"
            );
         }

         if (result instanceof ChunkDetailLodPlanResult.Ready ready) {
            plans.add(new PreparedLodPlan(registration, ready.plan()));
         } else if (result instanceof ChunkDetailLodPlanResult.Pending pending) {
            retryAfterTicks = Math.max(retryAfterTicks, pending.retryAfterTicks());
            pendingReasons.add(
               registration.identifier() + ": " + pending.reason()
            );
         } else if (result instanceof ChunkDetailLodPlanResult.Failed failed) {
            if (failed.retryable()) {
               retryAfterTicks = Math.max(retryAfterTicks, 20);
               pendingReasons.add(
                  registration.identifier() + ": " + failed.reason()
               );
            } else {
               LOGGER.error(
                  "Chunk-detail contributor {} declined LOD exclusion planning: {}",
                  registration.identifier(),
                  failed.reason()
               );
            }
         }
      }

      if (!pendingReasons.isEmpty()) {
         throw new ChunkDetailLodPendingException(
            retryAfterTicks, String.join("; ", pendingReasons)
         );
      }
      if (plans.isEmpty()) {
         return ChunkDetailLodPlan.none();
      }
      List<PreparedLodPlan> immutablePlans = List.copyOf(plans);
      return (domain, worldX, worldZ, radius) -> {
         if (domain != ChunkDetailDomain.MATURE_TREE_EXCLUSION
            && domain != ChunkDetailDomain.UNDERSTORY_EXCLUSION) {
            return false;
         }
         int checkedRadius = Math.max(0, radius);
         for (PreparedLodPlan prepared : immutablePlans) {
            if (prepared.suppresses(domain, worldX, worldZ, checkedRadius)) {
               return true;
            }
         }
         return false;
      };
   }

   private static boolean usesLodExclusionDomain(
      java.util.Set<ChunkDetailDomain> domains
   ) {
      return domains.contains(ChunkDetailDomain.MATURE_TREE_EXCLUSION)
         || domains.contains(ChunkDetailDomain.UNDERSTORY_EXCLUSION);
   }

   private static PreparedPlan validate(
      ChunkDetailContributorRegistry.Entry registration,
      long revision,
      ChunkDetailPlan plan,
      ChunkDetailPlanContext context
   ) {
      Objects.requireNonNull(plan, "plan");
      List<ChunkDetailClaim> claims = List.copyOf(Objects.requireNonNull(plan.claims(), "plan claims"));
      int halo = registration.contributor().haloBlocks();
      int minX = context.minBlockX() - halo;
      int minZ = context.minBlockZ() - halo;
      int maxX = context.minBlockX() + ChunkDetailPlanContext.CHUNK_SIDE - 1 + halo;
      int maxZ = context.minBlockZ() + ChunkDetailPlanContext.CHUNK_SIDE - 1 + halo;
      for (ChunkDetailClaim claim : claims) {
         Objects.requireNonNull(claim, "plan claim");
         if (!registration.contributor().domains().containsAll(claim.domains())) {
            throw new IllegalArgumentException(
               "Contributor " + registration.identifier() + " returned an undeclared claim domain"
            );
         }
         for (long column : claim.area().packedColumns()) {
            int x = ChunkDetailArea.unpackX(column);
            int z = ChunkDetailArea.unpackZ(column);
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
               throw new IllegalArgumentException(
                  "Contributor "
                     + registration.identifier()
                     + " claimed "
                     + x
                     + ","
                     + z
                     + " outside its "
                     + halo
                     + "-block halo"
               );
            }
         }
      }
      return new PreparedPlan(registration, revision, plan, claims, ChunkDetailArea.empty());
   }

   private static Preparation resolve(
      ChunkDetailPlanContext context,
      List<PreparedPlan> unresolved
   ) {
      Map<Long, SurfaceWinner> surfaceWinners = new HashMap<>();
      ChunkDetailArea.Builder treeExclusions = ChunkDetailArea.builder();
      ChunkDetailArea.Builder understoryExclusions = ChunkDetailArea.builder();
      int ownerMinX = context.minBlockX();
      int ownerMinZ = context.minBlockZ();
      int ownerMaxX = ownerMinX + ChunkDetailPlanContext.CHUNK_SIDE - 1;
      int ownerMaxZ = ownerMinZ + ChunkDetailPlanContext.CHUNK_SIDE - 1;

      for (PreparedPlan prepared : unresolved) {
         for (ChunkDetailClaim claim : prepared.claims()) {
            if (claim.domains().contains(ChunkDetailDomain.MATURE_TREE_EXCLUSION)) {
               treeExclusions.addAll(claim.area());
            }
            if (claim.domains().contains(ChunkDetailDomain.UNDERSTORY_EXCLUSION)) {
               understoryExclusions.addAll(claim.area());
            }
            if (!claim.domains().contains(ChunkDetailDomain.SURFACE_WRITE)) {
               continue;
            }
            SurfaceWinner candidate = new SurfaceWinner(
               prepared.registration().identifier(),
               claim.stableKey(),
               claim.priority()
            );
            for (long column : claim.area().packedColumns()) {
               int x = ChunkDetailArea.unpackX(column);
               int z = ChunkDetailArea.unpackZ(column);
               if (x < ownerMinX || x > ownerMaxX || z < ownerMinZ || z > ownerMaxZ) {
                  continue;
               }
               surfaceWinners.merge(column, candidate, SurfaceWinner::preferred);
            }
         }
      }

      Map<String, ChunkDetailArea.Builder> grants = new HashMap<>();
      for (Map.Entry<Long, SurfaceWinner> winner : surfaceWinners.entrySet()) {
         grants.computeIfAbsent(winner.getValue().contributorId(), ignored -> ChunkDetailArea.builder())
            .add(ChunkDetailArea.unpackX(winner.getKey()), ChunkDetailArea.unpackZ(winner.getKey()));
      }
      List<PreparedPlan> resolved = new ArrayList<>(unresolved.size());
      for (PreparedPlan prepared : unresolved) {
         ChunkDetailArea.Builder grant = grants.get(prepared.registration().identifier());
         resolved.add(prepared.withGrantedSurface(grant == null ? ChunkDetailArea.empty() : grant.build()));
      }
      return new Preparation(
         context,
         List.copyOf(resolved),
         treeExclusions.build(),
         understoryExclusions.build(),
         null,
         null
      );
   }

   record Preparation(
      ChunkDetailPlanContext context,
      List<PreparedPlan> plans,
      ChunkDetailArea treeExclusions,
      ChunkDetailArea understoryExclusions,
      ExclusionMask treeMask,
      ExclusionMask understoryMask
   ) {
      Preparation {
         Objects.requireNonNull(context, "context");
         plans = List.copyOf(plans);
         Objects.requireNonNull(treeExclusions, "treeExclusions");
         Objects.requireNonNull(understoryExclusions, "understoryExclusions");
         treeMask = treeMask == null
            ? ExclusionMask.create(context, treeExclusions, 8)
            : treeMask;
         understoryMask = understoryMask == null
            ? ExclusionMask.create(context, understoryExclusions, 4)
            : understoryMask;
      }

      static Preparation empty(ChunkDetailPlanContext context) {
         return new Preparation(
            context,
            List.of(),
            ChunkDetailArea.empty(),
            ChunkDetailArea.empty(),
            ExclusionMask.empty(context, 8),
            ExclusionMask.empty(context, 4)
         );
      }

      boolean suppressesTree(int worldX, int worldZ, int rootRadius) {
         return this.treeMask.contains(worldX, worldZ, rootRadius);
      }

      boolean suppressesUnderstory(int worldX, int worldZ, int radius) {
         return this.understoryMask.contains(worldX, worldZ, radius);
      }

      void apply(ChunkDetailWriter writer) {
         for (PreparedPlan prepared : this.plans) {
            ChunkDetailWriter grantedWriter = new GrantEnforcingWriter(
               writer, prepared.grantedSurface()
            );
            try {
               prepared.registration().contributor().apply(
                  new ChunkDetailApplyContext(
                     this.context, grantedWriter, prepared.grantedSurface()
                  ),
                  prepared.plan()
               );
            } catch (RuntimeException error) {
               LOGGER.error(
                  "Chunk-detail contributor {} failed while applying chunk {},{}; continuing with native detail",
                  prepared.registration().identifier(),
                  this.context.chunkX(),
                  this.context.chunkZ(),
                  error
               );
            }
         }
      }

      private static final class ExclusionMask {
            private static final int HALO = 16;
            private static final int SIDE = ChunkDetailPlanContext.CHUNK_SIDE + HALO * 2;
            private final int minX;
            private final int minZ;
            private final boolean[][] byRadius;

            private ExclusionMask(
               int minX, int minZ, boolean[][] byRadius
            ) {
               this.minX = minX;
               this.minZ = minZ;
               this.byRadius = byRadius;
            }

            private static ExclusionMask empty(
               ChunkDetailPlanContext context, int maxRadius
            ) {
               return new ExclusionMask(
                  context.minBlockX() - HALO,
                  context.minBlockZ() - HALO,
                  new boolean[Math.max(0, maxRadius) + 1][SIDE * SIDE]
               );
            }

            private static ExclusionMask create(
               ChunkDetailPlanContext context,
               ChunkDetailArea area,
               int maxRadius
            ) {
               ExclusionMask mask = empty(context, maxRadius);
               if (area.isEmpty()) {
                  return mask;
               }
               for (int radius = 0; radius < mask.byRadius.length; radius++) {
                  boolean[] values = mask.byRadius[radius];
                  for (long packed : area.packedColumns()) {
                     int centerX = ChunkDetailArea.unpackX(packed);
                     int centerZ = ChunkDetailArea.unpackZ(packed);
                     for (int dz = -radius; dz <= radius; dz++) {
                        int localZ = centerZ + dz - mask.minZ;
                        if (localZ < 0 || localZ >= SIDE) {
                           continue;
                        }
                        for (int dx = -radius; dx <= radius; dx++) {
                           int localX = centerX + dx - mask.minX;
                           if (localX >= 0 && localX < SIDE) {
                              values[localZ * SIDE + localX] = true;
                           }
                        }
                     }
                  }
               }
               return mask;
            }

            private boolean contains(int worldX, int worldZ, int radius) {
               int localX = worldX - this.minX;
               int localZ = worldZ - this.minZ;
               if (localX < 0 || localX >= SIDE || localZ < 0 || localZ >= SIDE) {
                  return false;
               }
               int checkedRadius = Math.max(
                  0, Math.min(radius, this.byRadius.length - 1)
               );
               return this.byRadius[checkedRadius][localZ * SIDE + localX];
            }
      }
   }

   private record GrantEnforcingWriter(
      ChunkDetailWriter delegate, ChunkDetailArea grantedSurface
   ) implements ChunkDetailWriter {
      private GrantEnforcingWriter {
         Objects.requireNonNull(delegate, "delegate");
         Objects.requireNonNull(grantedSurface, "grantedSurface");
      }

      @Override
      public int chunkX() {
         return this.delegate.chunkX();
      }

      @Override
      public int chunkZ() {
         return this.delegate.chunkZ();
      }

      @Override
      public int minY() {
         return this.delegate.minY();
      }

      @Override
      public int maxY() {
         return this.delegate.maxY();
      }

      @Override
      public int surfaceY(int localX, int localZ) {
         return this.delegate.surfaceY(localX, localZ);
      }

      @Override
      public net.minecraft.world.level.block.state.BlockState blockState(
         int localX, int blockY, int localZ
      ) {
         return this.delegate.blockState(localX, blockY, localZ);
      }

      @Override
      public boolean hasBlockEntity(int localX, int blockY, int localZ) {
         return this.delegate.hasBlockEntity(localX, blockY, localZ);
      }

      @Override
      public boolean setBlock(
         int localX,
         int blockY,
         int localZ,
         net.minecraft.world.level.block.state.BlockState state,
         int flags
      ) {
         int worldX = this.delegate.chunkX() * ChunkDetailPlanContext.CHUNK_SIDE
            + localX;
         int worldZ = this.delegate.chunkZ() * ChunkDetailPlanContext.CHUNK_SIDE
            + localZ;
         if (!this.grantedSurface.contains(worldX, worldZ)) {
            throw new IllegalStateException(
               "Chunk-detail write attempted outside a granted surface column: "
                  + worldX
                  + ","
                  + worldZ
            );
         }
         return this.delegate.setBlock(localX, blockY, localZ, state, flags);
      }
   }

   record PreparedPlan(
      ChunkDetailContributorRegistry.Entry registration,
      long revision,
      ChunkDetailPlan plan,
      List<ChunkDetailClaim> claims,
      ChunkDetailArea grantedSurface
   ) {
      PreparedPlan {
         claims = List.copyOf(claims);
         Objects.requireNonNull(grantedSurface, "grantedSurface");
      }

      PreparedPlan withGrantedSurface(ChunkDetailArea area) {
         return new PreparedPlan(this.registration, this.revision, this.plan, this.claims, area);
      }
   }

   private static final class PreparedLodPlan {
      private final ChunkDetailContributorRegistry.Entry registration;
      private final ChunkDetailLodPlan plan;
      private final AtomicBoolean failureLogged = new AtomicBoolean();

      private PreparedLodPlan(
         ChunkDetailContributorRegistry.Entry registration,
         ChunkDetailLodPlan plan
      ) {
         this.registration = Objects.requireNonNull(registration, "registration");
         this.plan = Objects.requireNonNull(plan, "plan");
      }

      private boolean suppresses(
         ChunkDetailDomain domain, int worldX, int worldZ, int radius
      ) {
         if (!this.registration.contributor().domains().contains(domain)) {
            return false;
         }
         try {
            return this.plan.suppresses(domain, worldX, worldZ, radius);
         } catch (RuntimeException error) {
            if (this.failureLogged.compareAndSet(false, true)) {
               LOGGER.error(
                  "Chunk-detail contributor {} failed an LOD exclusion query; continuing with native LOD detail",
                  this.registration.identifier(),
                  error
               );
            }
            return false;
         }
      }
   }

   private record SurfaceWinner(String contributorId, String claimKey, int priority) {
      private static final Comparator<SurfaceWinner> ORDER = Comparator
         .comparingInt(SurfaceWinner::priority)
         .reversed()
         .thenComparing(SurfaceWinner::contributorId)
         .thenComparing(SurfaceWinner::claimKey);

      private static SurfaceWinner preferred(SurfaceWinner left, SurfaceWinner right) {
         return ORDER.compare(left, right) <= 0 ? left : right;
      }
   }

   static final class PendingException extends RuntimeException {
      private final int retryAfterTicks;

      PendingException(int retryAfterTicks, String message) {
         super(message, null, false, false);
         this.retryAfterTicks = Math.max(1, retryAfterTicks);
      }

      int retryAfterTicks() {
         return this.retryAfterTicks;
      }
   }
}
