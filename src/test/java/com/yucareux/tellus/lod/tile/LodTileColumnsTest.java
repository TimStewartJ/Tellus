package com.yucareux.tellus.lod.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LodTileColumnsTest {
   @Test
   void touchingSpansWithTheSameMaterialAndLightMerge() {
      LodTileColumns tile = LodTileColumns.builder(2)
         .column(0, 0)
         .span(0, 4, 7, 0xF0)
         .span(4, 9, 7, 0xF0)
         .span(9, 10, 7, 0xE0)
         .span(12, 15, 3, 0xF0)
         .build();

      assertEquals(3, tile.spanCount());
      assertEquals(0, tile.bottom(0));
      assertEquals(9, tile.top(0));
      assertEquals(0xE0, tile.light(1));
      assertEquals(12, tile.bottom(2));
   }

   @Test
   void columnsThatWereNeverStartedStayEmpty() {
      LodTileColumns tile = LodTileColumns.builder(3)
         .column(1, 0).span(0, 5, 1, 0xF0)
         .column(2, 2).span(0, 6, 1, 0xF0).span(8, 9, 2, 0xF0)
         .build();

      assertEquals(0, tile.spanEnd(0) - tile.spanStart(0));
      assertEquals(1, tile.spanEnd(tile.columnIndex(1, 0)) - tile.spanStart(tile.columnIndex(1, 0)));
      for (int column = 2; column < 8; column++) {
         assertEquals(0, tile.spanEnd(column) - tile.spanStart(column), "column " + column);
      }
      assertEquals(2, tile.spanEnd(8) - tile.spanStart(8));
      assertEquals(3, tile.spanCount());
   }

   @Test
   void rejectsOverlapsAndOutOfOrderColumns() {
      LodTileColumns.Builder builder = LodTileColumns.builder(2).column(1, 0).span(0, 5, 1, 0);
      assertThrows(IllegalArgumentException.class, () -> builder.span(4, 6, 2, 0));
      assertThrows(IllegalStateException.class, () -> builder.column(0, 0));
      assertThrows(IllegalArgumentException.class, () -> builder.span(6, 6, 2, 0));
      assertThrows(IllegalStateException.class, () -> LodTileColumns.builder(2).span(0, 1, 1, 0));
   }

   @Test
   void serializedTilesReadBackIdentically() throws IOException {
      LodTileColumns tile = LodTileColumns.builder(4)
         .column(0, 0).span(0, 30, 5, 0xF0).span(30, 33, 9, 0xF0)
         .column(3, 1).span(0, 11087, 2, 0x0F)
         .column(2, 3).span(5, 6, 1, 0xFF)
         .build();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      tile.writeTo(new DataOutputStream(bytes));

      LodTileColumns copy = LodTileColumns.readFrom(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

      assertEquals(tile.width(), copy.width());
      assertEquals(tile.spanCount(), copy.spanCount());
      for (int column = 0; column < tile.columnCount(); column++) {
         assertEquals(tile.spanStart(column), copy.spanStart(column));
         assertEquals(tile.spanEnd(column), copy.spanEnd(column));
      }
      for (int span = 0; span < tile.spanCount(); span++) {
         assertEquals(tile.bottom(span), copy.bottom(span));
         assertEquals(tile.top(span), copy.top(span));
         assertEquals(tile.material(span), copy.material(span));
         assertEquals(tile.light(span), copy.light(span));
      }
   }
}
