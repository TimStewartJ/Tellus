package com.yucareux.tellus.worldgen.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class TellusBuildingLodWindowGlowTest {
   @Test
   void apartmentsShowAboutTwoInFiveWindowsLitMostlyInWarmLight() {
      WindowCount count = count(BuildingProfile.Archetype.APARTMENT, BuildingProfile.BuildingCategory.RESIDENTIAL, 1);

      assertEquals(0.4, count.litShare(), 0.03);
      assertTrue(count.warmShare() > 0.78 && count.warmShare() < 0.92, "warm share " + count.warmShare());
   }

   @Test
   void officesBurnMostlyCoolLight() {
      WindowCount count = count(BuildingProfile.Archetype.COMMERCIAL, BuildingProfile.BuildingCategory.OFFICE, 1);

      assertEquals(0.3, count.litShare(), 0.03);
      assertTrue(count.warmShare() < 0.3, "warm share " + count.warmShare());
   }

   @Test
   void warehousesStayMostlyDark() {
      assertTrue(count(BuildingProfile.Archetype.INDUSTRIAL, BuildingProfile.BuildingCategory.WAREHOUSE, 4).litShare() < 0.12);
   }

   @Test
   void aWholeRoomIsLitOrDarkTogether() {
      BuildingBlueprint blueprint = blueprint(BuildingProfile.Archetype.APARTMENT, BuildingProfile.BuildingCategory.RESIDENTIAL, 6);
      int bay = blueprint.profile().windowSpacing();
      for (int floor = 0; floor < 6; floor++) {
         for (int bayStart = 0; bayStart < 120; bayStart += bay) {
            int first = TellusBuildingLighting.resolveLodWindowGlow(blueprint, bayStart, 0, floor, 1);
            for (int x = bayStart + 1; x < bayStart + bay; x++) {
               assertEquals(first, TellusBuildingLighting.resolveLodWindowGlow(blueprint, x, 0, floor, 1));
            }
         }
      }
   }

   private static WindowCount count(BuildingProfile.Archetype archetype, BuildingProfile.BuildingCategory category, int cellSize) {
      int windows = 0;
      int lit = 0;
      int warm = 0;
      for (long building = 0; building < 40; building++) {
         BuildingBlueprint blueprint = new BuildingBlueprint(
            "glow-" + building, 9_001L + building * 7_919L, profile(archetype, category, 8), null, 63, 64, 96, 99, 0, 63, 0, 63, 32, 0, Direction.NORTH, 1
         );
         for (int floor = 1; floor < 8; floor++) {
            for (int x = 0; x < 64; x += Math.max(cellSize, blueprint.profile().windowSpacing())) {
               int glow = TellusBuildingLighting.resolveLodWindowGlow(blueprint, x, 0, floor, cellSize);
               windows++;
               if (glow != TellusBuildingLighting.LOD_WINDOW_DARK) {
                  lit++;
                  if (glow == TellusBuildingLighting.LOD_WINDOW_WARM) {
                     warm++;
                  }
               }
            }
         }
      }
      return new WindowCount(windows, lit, warm);
   }

   private static BuildingBlueprint blueprint(BuildingProfile.Archetype archetype, BuildingProfile.BuildingCategory category, int floors) {
      return new BuildingBlueprint("glow", 4_242L, profile(archetype, category, floors), null, 63, 64, 64 + floors * 4, 67 + floors * 4, 0, 119, 0, 11, 60, 0, Direction.NORTH, 1);
   }

   private static BuildingProfile profile(BuildingProfile.Archetype archetype, BuildingProfile.BuildingCategory category, int floors) {
      return new BuildingProfile(archetype, category, BuildingProfile.RoofProfile.FLAT, BuildingProfile.ClimateFamily.TEMPERATE, floors, 4, true, 1, 0, 0, 0, 3);
   }

   private record WindowCount(int windows, int lit, int warm) {
      double litShare() {
         return this.lit / (double)this.windows;
      }

      double warmShare() {
         return this.warm / (double)Math.max(1, this.lit);
      }
   }
}
