package com.yucareux.tellus.lod.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.lod.tile.LodTileColumns;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LodGreedyMesherTest {
   private static final int STONE = 2;
   private static final int GRASS = 4;
   private static final int LEAVES = 6;
   private static final int WATER = 1;
   private static final int SKY = 0xF0;
   private static final LodMaterialTable ODD_IS_WATER = material -> (material & 1) == 1;

   @Test
   void flatGroundIsOneTopQuad() {
      LodTileColumns tile = filled(64, (builder, x, z) -> builder.span(0, 40, GRASS, SKY));
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 0, recorder);

      assertEquals(1, recorder.quads.size());
      Quad top = recorder.quads.get(0);
      assertEquals(LodFace.UP, top.face);
      assertEquals(40, top.plane);
      assertEquals(64, top.width);
      assertEquals(64, top.height);
   }

   @Test
   void edgesWithoutNeighboursGetOneSkirtPerSide() {
      LodTileColumns tile = filled(64, (builder, x, z) -> builder.span(0, 40, GRASS, SKY));
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 6, recorder);

      assertEquals(4, recorder.skirts.size());
      for (Quad skirt : recorder.skirts) {
         assertEquals(34, skirt.v);
         assertEquals(6, skirt.height);
         assertEquals(64, skirt.width);
      }
   }

   @Test
   void aStepBecomesTwoTopsAndOneMergedWall() {
      LodTileColumns tile = filled(4, (builder, x, z) -> builder.span(0, x < 2 ? 10 : 12, STONE, SKY));
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 0, recorder);

      assertEquals(3, recorder.quads.size());
      Quad wall = recorder.only(LodFace.WEST);
      assertEquals(2, wall.plane);
      assertEquals(0, wall.u);
      assertEquals(4, wall.width);
      assertEquals(10, wall.v);
      assertEquals(2, wall.height);
   }

   @Test
   void seabedKeepsItsTopFaceUnderWater() {
      LodTileColumns tile = filled(8, (builder, x, z) -> builder.span(0, 5, STONE, SKY).span(5, 8, WATER, SKY));
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 0, recorder);

      assertEquals(2, recorder.quads.size());
      assertTrue(recorder.quads.stream().anyMatch(quad -> quad.face == LodFace.UP && quad.plane == 5 && !quad.translucent));
      assertTrue(recorder.quads.stream().anyMatch(quad -> quad.face == LodFace.UP && quad.plane == 8 && quad.translucent));
   }

   @Test
   void floatingCanopyHasTopBottomAndFourSides() {
      LodTileColumns tile = filled(3, (builder, x, z) -> {
         builder.span(0, 10, GRASS, SKY);
         if (x == 1 && z == 1) {
            builder.span(12, 15, LEAVES, SKY);
         }
      });
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 0, recorder);

      assertEquals(7, recorder.quads.size());
      assertEquals(1, recorder.count(LodFace.DOWN));
      assertEquals(2, recorder.count(LodFace.UP));
      for (LodFace side : new LodFace[]{LodFace.NORTH, LodFace.SOUTH, LodFace.WEST, LodFace.EAST}) {
         Quad wall = recorder.only(side);
         assertEquals(12, wall.v);
         assertEquals(3, wall.height);
      }
   }

   @Test
   void neighbourTilesReplaceSkirtsWithExactWalls() {
      LodTileColumns high = filled(4, (builder, x, z) -> builder.span(0, 20, STONE, SKY));
      LodTileColumns same = filled(4, (builder, x, z) -> builder.span(0, 20, STONE, SKY));
      LodTileColumns low = filled(4, (builder, x, z) -> builder.span(0, 15, STONE, SKY));
      LodGreedyMesher mesher = new LodGreedyMesher(ODD_IS_WATER);

      Recorder seamless = new Recorder();
      mesher.mesh(high, same, same, same, same, 8, seamless);
      assertEquals(1, seamless.quads.size());
      assertEquals(0, seamless.skirts.size());

      Recorder cliff = new Recorder();
      mesher.mesh(high, null, low, same, same, 8, cliff);
      Quad wall = cliff.only(LodFace.EAST);
      assertEquals(4, wall.plane);
      assertEquals(15, wall.v);
      assertEquals(5, wall.height);
      assertEquals(4, wall.width);
      assertEquals(1, cliff.skirts.size());
      assertEquals(LodFace.WEST, cliff.skirts.get(0).face);
   }

   @Test
   void waterBodiesHaveNoInternalWalls() {
      LodTileColumns tile = filled(16, (builder, x, z) -> {
         int seabed = 3 + (x * 7 + z * 3) % 5;
         builder.span(0, seabed, STONE, SKY).span(seabed, 10, WATER, SKY);
      });
      Recorder recorder = new Recorder();

      new LodGreedyMesher(ODD_IS_WATER).mesh(tile, 0, recorder);

      for (Quad quad : recorder.quads) {
         if (quad.translucent) {
            assertEquals(LodFace.UP, quad.face);
         }
      }
      assertEquals(1, recorder.quads.stream().filter(quad -> quad.translucent).count());
   }

   @Test
   void mergedQuadsCoverExactlyTheVisibleFaceArea() {
      Random random = new Random(42L);
      int[] heights = new int[64 * 64];
      for (int index = 0; index < heights.length; index++) {
         heights[index] = 20 + random.nextInt(4);
      }
      LodTileColumns tile = filled(64, (builder, x, z) -> {
         int height = heights[z * 64 + x];
         builder.span(0, height, (x / 8 + z / 8) % 2 == 0 ? STONE : GRASS, SKY);
         if (random.nextInt(10) == 0) {
            builder.span(height + 2, height + 4 + random.nextInt(3), LEAVES, SKY);
         } else if (height < 21) {
            builder.span(height, 22, WATER, SKY);
         }
      });
      Recorder recorder = new Recorder();
      LodGreedyMesher mesher = new LodGreedyMesher(ODD_IS_WATER);

      mesher.mesh(tile, 0, recorder);

      long topArea = 0L;
      long bottomArea = 0L;
      long sideArea = 0L;
      for (Quad quad : recorder.quads) {
         long area = (long)quad.width * quad.height;
         if (quad.face == LodFace.UP) {
            topArea += area;
         } else if (quad.face == LodFace.DOWN) {
            bottomArea += area;
         } else {
            sideArea += area;
         }
      }
      assertEquals(mesher.rawTopFaces(), topArea);
      assertEquals(mesher.rawBottomFaces(), bottomArea);
      assertEquals(exposedSideArea(tile), sideArea);
      assertTrue(recorder.quads.size() < mesher.rawTopFaces() + mesher.rawBottomFaces() + mesher.rawSideFaces());
   }

   /** Brute force: count block-sized side faces whose neighbouring voxel does not hide them. */
   private static long exposedSideArea(LodTileColumns tile) {
      int width = tile.width();
      int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
      long area = 0L;
      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            int column = tile.columnIndex(x, z);
            for (int[] offset : offsets) {
               int nx = x + offset[0];
               int nz = z + offset[1];
               if (nx < 0 || nz < 0 || nx >= width || nz >= width) {
                  continue;
               }
               int other = tile.columnIndex(nx, nz);
               int floor = tile.bottom(tile.spanStart(other));
               for (int span = tile.spanStart(column); span < tile.spanEnd(column); span++) {
                  for (int y = tile.bottom(span); y < tile.top(span); y++) {
                     if (y >= floor && !hidden(tile, other, y, tile.material(span))) {
                        area++;
                     }
                  }
               }
            }
         }
      }
      return area;
   }

   private static boolean hidden(LodTileColumns tile, int column, int y, int faceMaterial) {
      for (int span = tile.spanStart(column); span < tile.spanEnd(column); span++) {
         if (y >= tile.bottom(span) && y < tile.top(span)) {
            int material = tile.material(span);
            return !ODD_IS_WATER.isTranslucent(material) || material == faceMaterial;
         }
      }
      return false;
   }

   private static LodTileColumns filled(int width, ColumnFiller filler) {
      LodTileColumns.Builder builder = LodTileColumns.builder(width);
      for (int z = 0; z < width; z++) {
         for (int x = 0; x < width; x++) {
            builder.column(x, z);
            filler.fill(builder, x, z);
         }
      }
      return builder.build();
   }

   @FunctionalInterface
   private interface ColumnFiller {
      void fill(LodTileColumns.Builder builder, int x, int z);
   }

   private record Quad(LodFace face, int plane, int u, int v, int width, int height, int material, boolean translucent) {
   }

   private static final class Recorder implements LodQuadSink {
      final List<Quad> quads = new ArrayList<>();
      final List<Quad> skirts = new ArrayList<>();

      @Override
      public void quad(LodFace face, int plane, int u, int v, int width, int height, int material, int light, boolean translucent) {
         this.quads.add(new Quad(face, plane, u, v, width, height, material, translucent));
      }

      @Override
      public void skirt(LodFace face, int plane, int u, int v, int width, int height, int material, int light) {
         this.skirts.add(new Quad(face, plane, u, v, width, height, material, false));
      }

      long count(LodFace face) {
         return this.quads.stream().filter(quad -> quad.face == face).count();
      }

      Quad only(LodFace face) {
         List<Quad> matches = this.quads.stream().filter(quad -> quad.face == face).toList();
         assertEquals(1, matches.size(), "quads facing " + face + ": " + matches);
         return matches.get(0);
      }
   }
}
