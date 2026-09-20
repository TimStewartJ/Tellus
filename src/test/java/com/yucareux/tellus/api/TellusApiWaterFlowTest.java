package com.yucareux.tellus.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusApiWaterFlowTest {
   @Test
   void confirmsSourceOrderWhenTerrainDropsAlongMappedLine() {
      TellusApi.FlowDirection direction = TellusApi.flowDirectionFromTerrain(12.0, 10.9);

      assertEquals(1.0, direction.sign());
      assertTrue(direction.terrainConfirmed());
   }

   @Test
   void reversesSourceOrderWhenTerrainRisesAlongMappedLine() {
      TellusApi.FlowDirection direction = TellusApi.flowDirectionFromTerrain(10.0, 11.1);

      assertEquals(-1.0, direction.sign());
      assertTrue(direction.terrainConfirmed());
   }

   @Test
   void keepsSourceOrderWhenTerrainDifferenceIsExactlyOneMeter() {
      TellusApi.FlowDirection uphill = TellusApi.flowDirectionFromTerrain(10.0, 11.0);
      TellusApi.FlowDirection downhill = TellusApi.flowDirectionFromTerrain(11.0, 10.0);

      assertEquals(1.0, uphill.sign());
      assertFalse(uphill.terrainConfirmed());
      assertEquals(1.0, downhill.sign());
      assertFalse(downhill.terrainConfirmed());
   }

   @Test
   void keepsSourceOrderWhenTerrainIsFlatOrUnavailable() {
      TellusApi.FlowDirection flat = TellusApi.flowDirectionFromTerrain(10.0, 10.5);
      TellusApi.FlowDirection missingFirst = TellusApi.flowDirectionFromTerrain(Double.NaN, 8.0);
      TellusApi.FlowDirection missingLast = TellusApi.flowDirectionFromTerrain(8.0, Double.NaN);

      assertEquals(1.0, flat.sign());
      assertFalse(flat.terrainConfirmed());
      assertEquals(1.0, missingFirst.sign());
      assertFalse(missingFirst.terrainConfirmed());
      assertEquals(1.0, missingLast.sign());
      assertFalse(missingLast.terrainConfirmed());
   }
}
