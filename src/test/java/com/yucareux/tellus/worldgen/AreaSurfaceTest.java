package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AreaSurfaceTest {
   /**
    * A river reach that loops back on itself (Iguazu): a fall across the main channel between pools at 150 and 120,
    * blurred by the terrain model into a ramp, and a long gentle side channel that joins the same two levels around an
    * island. The fall follows the terrain down its ramp, and a plan levels it the same whether or not its grid reaches
    * round the loop.
    */
   @Test void fallFollowsItsRampWhenItsPoolsJoinFarAwayAndLevelsDoNotDependOnTheGridReach() {
      double[][] rings = {{0, 0, 900, 0, 900, 1200, 0, 1200, 0, 0}, {100, 300, 800, 300, 800, 1100, 100, 1100, 100, 300}};
      double[][] lon = new double[2][], lat = new double[2][];
      for (int r = 0; r < 2; r++) {
         lon[r] = new double[rings[r].length / 2];
         lat[r] = new double[rings[r].length / 2];
         for (int i = 0; i < lon[r].length; i++) {
            lon[r][i] = WorldProjection.global(1.0).blockXToLon(rings[r][i * 2]);
            lat[r][i] = WorldProjection.global(1.0).blockZToLat(rings[r][i * 2 + 1]);
         }
      }
      HydrologyWaterMap.Shape loop = new HydrologyWaterMap.Shape(new OsmWaterFeature(7, false, false, OsmWaterKind.RIVER, lon, lat), WorldProjection.global(1.0));
      RiverChannels.Terrain terrain = new RiverChannels.Terrain() {
         public double height(int x, int z) {
            boolean main = z < 300 && x >= 0 && x <= 900;
            if (main) return x < 430 ? 150.0 : x > 470 ? 120.0 : 150.0 - (x - 430) * 0.75;
            double t;
            if (x >= 0 && x < 100 && z >= 300 && z <= 1200) t = z - 300;
            else if (z > 1100 && z <= 1200 && x >= 0 && x <= 900) t = 900 + x;
            else if (x > 800 && x <= 900 && z >= 300 && z <= 1200) t = 1800 + 1200 - z;
            else return 175.0;
            return 150.0 - 30.0 * t / 2700.0;
         }

         public double resolution(int x, int z) {
            return 30.0;
         }
      };
      AreaSurface plan = new AreaSurface(List.of(loop), -50, -50, 561, 561, terrain, AreaSurface.GRID, 1.0, List.of());
      AreaSurface wider = new AreaSurface(List.of(loop), -50, -50, 1073, 1073, terrain, AreaSurface.GRID, 1.0, List.of());
      // Neither the upper pool held out to the end of the ramp nor the lower one cut back into it.
      double previous = Double.POSITIVE_INFINITY;
      for (int x = 380; x <= 520; x += 2) {
         double ground = terrain.height(x, 150), level = plan.level(x, 150, ground);
         assertEquals(ground, level, 1.0, "Level off the terrain at x=" + x);
         assertTrue(level <= previous + 0.25, "Level rises at x=" + x);
         previous = level;
      }
      for (int z = 0; z <= 511; z += 3) for (int x = 0; x <= 511; x += 3) {
         if (!plan.inside(x, z)) continue;
         double ground = terrain.height(x, z);
         assertEquals(wider.level(x, z, ground), plan.level(x, z, ground), 1.0E-9, "Level at " + x + "," + z);
      }
   }
}
