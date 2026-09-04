package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.api.TellusApi;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class ChunkDetailContributorsTest {
   @Test
   void equalPrioritySurfaceClaimsUseIdentifierThenStableKey() {
      ChunkDetailContributorRegistry.Registration later = TellusApi.registerChunkDetailContributor(
         "test:z_surface",
         contributor(
            Set.of(ChunkDetailDomain.SURFACE_WRITE),
            ready(claim("same", 10, ChunkDetailDomain.SURFACE_WRITE, 1, 1))
         )
      );
      ChunkDetailContributorRegistry.Registration earlier = TellusApi.registerChunkDetailContributor(
         "test:a_surface",
         contributor(
            Set.of(ChunkDetailDomain.SURFACE_WRITE),
            ready(claim("same", 10, ChunkDetailDomain.SURFACE_WRITE, 1, 1))
         )
      );
      try {
         ChunkDetailContributors.Preparation preparation = ChunkDetailContributors.prepare(
            ChunkDetailContributorRegistry.global().snapshot(), context(), false
         );
         ChunkDetailContributors.PreparedPlan a = preparation.plans().stream()
            .filter(plan -> plan.registration().identifier().equals("test:a_surface"))
            .findFirst()
            .orElseThrow();
         ChunkDetailContributors.PreparedPlan z = preparation.plans().stream()
            .filter(plan -> plan.registration().identifier().equals("test:z_surface"))
            .findFirst()
            .orElseThrow();
         assertTrue(a.grantedSurface().contains(1, 1));
         assertFalse(z.grantedSurface().contains(1, 1));
      } finally {
         earlier.close();
         later.close();
      }
   }

   @Test
   void exclusionClaimsRetainTheirHaloAndUnionAcrossContributors() {
      ChunkDetailContributorRegistry.Registration first = TellusApi.registerChunkDetailContributor(
         "test:first_exclusion",
         contributor(
            Set.of(ChunkDetailDomain.MATURE_TREE_EXCLUSION),
            ready(claim("west", 0, ChunkDetailDomain.MATURE_TREE_EXCLUSION, -1, 4))
         )
      );
      ChunkDetailContributorRegistry.Registration second = TellusApi.registerChunkDetailContributor(
         "test:second_exclusion",
         contributor(
            Set.of(ChunkDetailDomain.UNDERSTORY_EXCLUSION),
            ready(claim("east", 0, ChunkDetailDomain.UNDERSTORY_EXCLUSION, 16, 7))
         )
      );
      try {
         ChunkDetailContributors.Preparation preparation = ChunkDetailContributors.prepare(
            ChunkDetailContributorRegistry.global().snapshot(), context(), false
         );
         assertTrue(preparation.suppressesTree(0, 4, 1));
         assertTrue(preparation.suppressesUnderstory(15, 7, 1));
         assertFalse(preparation.suppressesTree(8, 8, 1));
      } finally {
         second.close();
         first.close();
      }
   }

   @Test
   void explicitTreeAnchorAndRootClaimsDoNotReceiveLegacyPadding() {
      ChunkDetailContributorRegistry.Registration registration =
         TellusApi.registerChunkDetailContributor(
            "test:explicit_tree_geometry",
            contributor(
               Set.of(
                  ChunkDetailDomain.TREE_ANCHOR_EXCLUSION,
                  ChunkDetailDomain.TREE_ROOT_EXCLUSION
               ),
               ready(
                  new ChunkDetailClaim(
                     "tree-anchor",
                     0,
                     Set.of(ChunkDetailDomain.TREE_ANCHOR_EXCLUSION),
                     ChunkDetailArea.of(5, 5)
                  ),
                  new ChunkDetailClaim(
                     "tree-root",
                     0,
                     Set.of(ChunkDetailDomain.TREE_ROOT_EXCLUSION),
                     ChunkDetailArea.of(7, 5)
                  )
               )
            )
         );
      try {
         ChunkDetailContributors.Preparation preparation =
            ChunkDetailContributors.prepare(
               ChunkDetailContributorRegistry.global().snapshot(),
               context(),
               false
            );

         assertTrue(preparation.suppressesTree(5, 5, 8));
         assertFalse(preparation.suppressesTree(6, 5, 8));
         assertTrue(preparation.suppressesRoot(7, 5));
         assertFalse(preparation.suppressesRoot(6, 5));
      } finally {
         registration.close();
      }
   }

   @Test
   void pendingPlansRetryThenCanExplicitlyFailOpen() {
      ChunkDetailContributorRegistry.Registration registration = TellusApi.registerChunkDetailContributor(
         "test:pending",
         contributor(
            Set.of(ChunkDetailDomain.SURFACE_WRITE),
            ChunkDetailPlanResult.pending(7, "source warming")
         )
      );
      try {
         ChunkDetailContributors.PendingException pending = assertThrows(
            ChunkDetailContributors.PendingException.class,
            () -> ChunkDetailContributors.prepare(
               ChunkDetailContributorRegistry.global().snapshot(), context(), false
            )
         );
         assertEquals(7, pending.retryAfterTicks());
         assertTrue(
            ChunkDetailContributors.prepare(
               ChunkDetailContributorRegistry.global().snapshot(), context(), true
            ).plans().isEmpty()
         );
      } finally {
         registration.close();
      }
   }

   @Test
   void lodExclusionsUnionAdvertisedDomainsWithoutApplyingWorldWrites() {
      AtomicInteger lodQueries = new AtomicInteger();
      ChunkDetailContributorRegistry.Registration trees =
         TellusApi.registerChunkDetailContributor(
            "test:lod_trees",
            lodContributor(
               Set.of(ChunkDetailDomain.MATURE_TREE_EXCLUSION),
               ChunkDetailLodPlanResult.ready(
                  (domain, x, z, radius) -> {
                     lodQueries.incrementAndGet();
                     return x == 12 && z == 18 && radius == 8;
                  }
               )
            )
         );
      ChunkDetailContributorRegistry.Registration understory =
         TellusApi.registerChunkDetailContributor(
            "test:lod_understory",
            lodContributor(
               Set.of(ChunkDetailDomain.UNDERSTORY_EXCLUSION),
               ChunkDetailLodPlanResult.ready(
                  (domain, x, z, radius) -> x == 20 && z == 24 && radius == 3
               )
            )
         );
      try {
         ChunkDetailLodPlan plan = ChunkDetailContributors.prepareLodExclusions(
            ChunkDetailContributorRegistry.global().snapshot(), lodContext()
         );
         assertTrue(
            plan.suppresses(
               ChunkDetailDomain.MATURE_TREE_EXCLUSION, 12, 18, 8
            )
         );
         assertTrue(
            plan.suppresses(
               ChunkDetailDomain.UNDERSTORY_EXCLUSION, 20, 24, 3
            )
         );
         assertFalse(
            plan.suppresses(ChunkDetailDomain.SURFACE_WRITE, 12, 18, 8)
         );
         assertEquals(1, lodQueries.get());
      } finally {
         understory.close();
         trees.close();
      }
   }

   @Test
   void pendingLodExclusionsRejectAnIncompleteTileForRetry() {
      ChunkDetailContributorRegistry.Registration registration =
         TellusApi.registerChunkDetailContributor(
            "test:lod_pending",
            lodContributor(
               Set.of(ChunkDetailDomain.MATURE_TREE_EXCLUSION),
               ChunkDetailLodPlanResult.pending(11, "route tiles warming")
            )
         );
      try {
         ChunkDetailLodPendingException pending = assertThrows(
            ChunkDetailLodPendingException.class,
            () -> ChunkDetailContributors.prepareLodExclusions(
               ChunkDetailContributorRegistry.global().snapshot(), lodContext()
            )
         );
         assertEquals(11, pending.retryAfterTicks());
      } finally {
         registration.close();
      }
   }

   @Test
   void claimsOutsideTheDeclaredHaloFailOpenWithoutAffectingNativeDetail() {
      ChunkDetailContributorRegistry.Registration registration = TellusApi.registerChunkDetailContributor(
         "test:bad_halo",
         new ChunkDetailContributor() {
            @Override
            public long revision() {
               return 1L;
            }

            @Override
            public int haloBlocks() {
               return 0;
            }

            @Override
            public Set<ChunkDetailDomain> domains() {
               return Set.of(ChunkDetailDomain.SURFACE_WRITE);
            }

            @Override
            public ChunkDetailPlanResult plan(ChunkDetailPlanContext context) {
               return ready(claim("outside", 0, ChunkDetailDomain.SURFACE_WRITE, 16, 0));
            }
         }
      );
      try {
         assertTrue(
            ChunkDetailContributors.prepare(
               ChunkDetailContributorRegistry.global().snapshot(), context(), false
            ).plans().isEmpty()
         );
      } finally {
         registration.close();
      }
   }

   @Test
   void inactiveContributorsDoNotForceOrEnterTheDeferredPlan() {
      ChunkDetailContributorRegistry.Registration registration =
         TellusApi.registerChunkDetailContributor(
            "test:inactive",
            new ChunkDetailContributor() {
               @Override
               public boolean active() {
                  return false;
               }

               @Override
               public long revision() {
                  return 1L;
               }

               @Override
               public int haloBlocks() {
                  return 0;
               }

               @Override
               public Set<ChunkDetailDomain> domains() {
                  return Set.of(ChunkDetailDomain.MATURE_TREE_EXCLUSION);
               }

               @Override
               public ChunkDetailPlanResult plan(ChunkDetailPlanContext context) {
                  throw new AssertionError("inactive contributor was planned");
               }
            }
         );
      try {
         ChunkDetailContributorRegistry.Snapshot snapshot =
            ChunkDetailContributorRegistry.global().snapshot();
         assertTrue(snapshot.isEmpty());
         assertTrue(
            ChunkDetailContributors.prepare(snapshot, context(), false)
               .plans()
               .isEmpty()
         );
      } finally {
         registration.close();
      }
   }

   @Test
   void oneApplyFailureDoesNotSuppressLaterContributorsOrNativeDetail() {
      AtomicInteger applied = new AtomicInteger();
      ChunkDetailContributorRegistry.Registration throwing =
         TellusApi.registerChunkDetailContributor(
            "test:a_throwing",
            applyingContributor(() -> {
               throw new IllegalStateException("expected");
            })
         );
      ChunkDetailContributorRegistry.Registration following =
         TellusApi.registerChunkDetailContributor(
            "test:z_following",
            applyingContributor(applied::incrementAndGet)
         );
      try {
         ChunkDetailContributors.Preparation preparation =
            ChunkDetailContributors.prepare(
               ChunkDetailContributorRegistry.global().snapshot(), context(), false
            );
         preparation.apply(new NoopWriter());
         assertEquals(1, applied.get());
      } finally {
         following.close();
         throwing.close();
      }
   }

   private static ChunkDetailContributor contributor(
      Set<ChunkDetailDomain> domains, ChunkDetailPlanResult result
   ) {
      return new ChunkDetailContributor() {
         @Override
         public long revision() {
            return 1L;
         }

         @Override
         public int haloBlocks() {
            return 1;
         }

         @Override
         public Set<ChunkDetailDomain> domains() {
            return domains;
         }

         @Override
         public ChunkDetailPlanResult plan(ChunkDetailPlanContext context) {
            return result;
         }
      };
   }

   private static ChunkDetailContributor applyingContributor(Runnable apply) {
      return new ChunkDetailContributor() {
         @Override
         public long revision() {
            return 1L;
         }

         @Override
         public int haloBlocks() {
            return 0;
         }

         @Override
         public Set<ChunkDetailDomain> domains() {
            return Set.of(ChunkDetailDomain.MATURE_TREE_EXCLUSION);
         }

         @Override
         public ChunkDetailPlanResult plan(ChunkDetailPlanContext context) {
            return ChunkDetailPlanResult.ready(() -> List.of());
         }

         @Override
         public void apply(
            com.yucareux.tellus.api.detail.ChunkDetailApplyContext context,
            ChunkDetailPlan plan
         ) {
            apply.run();
         }
      };
   }

   private static ChunkDetailContributor lodContributor(
      Set<ChunkDetailDomain> domains, ChunkDetailLodPlanResult result
   ) {
      return new ChunkDetailContributor() {
         @Override
         public long revision() {
            return 1L;
         }

         @Override
         public int haloBlocks() {
            return 0;
         }

         @Override
         public Set<ChunkDetailDomain> domains() {
            return domains;
         }

         @Override
         public ChunkDetailPlanResult plan(ChunkDetailPlanContext context) {
            return ChunkDetailPlanResult.skipped("LOD-only test contributor");
         }

         @Override
         public ChunkDetailLodPlanResult planLodExclusions(
            ChunkDetailLodPlanContext context
         ) {
            return result;
         }
      };
   }

   private static ChunkDetailPlanResult ready(ChunkDetailClaim... claims) {
      ChunkDetailPlan plan = () -> List.of(claims);
      return ChunkDetailPlanResult.ready(plan);
   }

   private static ChunkDetailClaim claim(
      String key,
      int priority,
      ChunkDetailDomain domain,
      int worldX,
      int worldZ
   ) {
      return new ChunkDetailClaim(
         key, priority, Set.of(domain), ChunkDetailArea.of(worldX, worldZ)
      );
   }

   private static ChunkDetailPlanContext context() {
      return new ChunkDetailPlanContext(
         0,
         0,
         -64,
         319,
         42L,
         9L,
         WorldProjection.global(1.0),
         false,
         filled(80),
         filled(80),
         new boolean[256],
         filled(10)
      );
   }

   private static ChunkDetailLodPlanContext lodContext() {
      return new ChunkDetailLodPlanContext(
         0,
         0,
         2,
         16,
         -64,
         319,
         42L,
         WorldProjection.global(1.0),
         false,
         filled(4, 80),
         filled(4, 80),
         new boolean[4],
         filled(4, 10)
      );
   }

   private static int[] filled(int value) {
      int[] values = new int[256];
      java.util.Arrays.fill(values, value);
      return values;
   }

   private static int[] filled(int length, int value) {
      int[] values = new int[length];
      java.util.Arrays.fill(values, value);
      return values;
   }

   private static final class NoopWriter implements ChunkDetailWriter {
      @Override
      public int chunkX() {
         return 0;
      }

      @Override
      public int chunkZ() {
         return 0;
      }

      @Override
      public int minY() {
         return -64;
      }

      @Override
      public int maxY() {
         return 319;
      }

      @Override
      public int surfaceY(int localX, int localZ) {
         return 64;
      }

      @Override
      public BlockState blockState(int localX, int blockY, int localZ) {
         return null;
      }

      @Override
      public boolean hasBlockEntity(int localX, int blockY, int localZ) {
         return false;
      }

      @Override
      public boolean setBlock(
         int localX, int blockY, int localZ, BlockState state, int flags
      ) {
         return false;
      }
   }
}
