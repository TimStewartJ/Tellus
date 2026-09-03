package com.yucareux.tellus.integration.distant_horizons.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedTerrainNetworkPolicyTest {
   @Test
   void cacheOnlyScopesNestAndRestoreThreadState() {
      assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
      ManagedTerrainNetworkPolicy.Scope outer = ManagedTerrainNetworkPolicy.cacheOnly();
      try (outer) {
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
         ManagedTerrainNetworkPolicy.Scope inner = ManagedTerrainNetworkPolicy.cacheOnly();
         try (inner) {
            assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
         }
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
      }
      assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
   }

   @Test
   void networkAllowanceTemporarilySuspendsAndRestoresCacheOnlyState() {
      try (ManagedTerrainNetworkPolicy.Scope outer =
         ManagedTerrainNetworkPolicy.cacheOnly()) {
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
         try (ManagedTerrainNetworkPolicy.Scope allowed =
            ManagedTerrainNetworkPolicy.networkAllowed()) {
            assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
            try (ManagedTerrainNetworkPolicy.Scope nested =
               ManagedTerrainNetworkPolicy.cacheOnly()) {
               assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
            }
            assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
         }
         assertTrue(ManagedTerrainNetworkPolicy.isCacheOnly());
      }
      assertFalse(ManagedTerrainNetworkPolicy.isCacheOnly());
   }
}
