package com.yucareux.tellus.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yucareux.tellus.worldgen.WorldProjection;
import org.junit.jupiter.api.Test;

class TellusApiProjectionTest {
   @Test
   void apiVersionIncludesChunkDetailContributors() throws Exception {
      assertEquals(6, TellusApi.API_VERSION);
      assertEquals(
         boolean.class,
         TellusApi.class
            .getMethod(
               "isChunkDetailReady",
               net.minecraft.server.level.ServerLevel.class,
               int.class,
               int.class
            )
            .getReturnType()
      );
      assertEquals(
         com.yucareux.tellus.api.detail.ChunkDetailContributorRegistry.Registration.class,
         TellusApi.class
            .getMethod(
              "registerChunkDetailContributor",
              String.class,
              com.yucareux.tellus.api.detail.ChunkDetailContributor.class
            )
            .getReturnType()
      );
   }

   @Test
   void centeredProjectionKeepsSpawnAtBlockOrigin() {
      WorldProjection projection = WorldProjection.centeredOn(1.0, 47.6062, -122.3321);

      assertEquals(0.0, TellusApi.blockXFromLongitude(-122.3321, projection), 1.0E-6);
      assertEquals(0.0, TellusApi.blockZFromLatitude(47.6062, projection), 1.0E-6);
      assertEquals(-122.3321, TellusApi.longitudeFromBlockX(0.0, projection), 1.0E-6);
      assertEquals(47.6062, TellusApi.latitudeFromBlockZ(0.0, projection), 1.0E-6);
   }

   @Test
   void globalProjectionRetainsHistoricalCoordinates() {
      WorldProjection projection = WorldProjection.global(30.0);

      double x = TellusApi.blockXFromLongitude(12.5, projection);
      double z = TellusApi.blockZFromLatitude(-33.9, projection);

      assertEquals(12.5, TellusApi.longitudeFromBlockX(x, projection), 1.0E-9);
      assertEquals(-33.9, TellusApi.latitudeFromBlockZ(z, projection), 1.0E-9);
   }

   @Test
   void groundScaleUsesWorldProjectionOrigin() {
      WorldProjection projection = WorldProjection.centeredOn(1.0, 60.0, 15.0);

      assertEquals(0.5, TellusApi.groundMetersPerBlock(0.0, projection), 1.0E-6);
   }
}
