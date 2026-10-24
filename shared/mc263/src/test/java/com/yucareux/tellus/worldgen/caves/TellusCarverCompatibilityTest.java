package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.*;

import com.yucareux.tellus.worldgen.UndergroundStructureExclusion;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class TellusCarverCompatibilityTest {
   @Test
   void maskProtectsTheShellFloorAndSurfaceAtHighAltitude() {
      int[] surface = columns(10_000);
      int[] floor = columns(9_744);
      ChunkPos chunk = new ChunkPos(-2, 3);
      assertTrue(protectedAt(15, 9_744, 0, chunk, false, surface, null, floor, List.of()));
      assertFalse(protectedAt(15, 9_745, 0, chunk, false, surface, null, floor, List.of()));
      assertFalse(protectedAt(15, 9_995, 0, chunk, false, surface, null, floor, List.of()));
      assertTrue(protectedAt(15, 9_996, 0, chunk, false, surface, null, floor, List.of()));
      assertFalse(protectedAt(15, 9_996, 0, chunk, true, surface, null, floor, List.of()));
   }

   @Test
   void floodProtectionUsesTheCorrectColumnOnChunkEdges() {
      int[] flood = columns(Integer.MAX_VALUE);
      flood[15 * 16] = -20;
      ChunkPos chunk = new ChunkPos(0, 0);
      assertTrue(protectedAt(0, -20, 15, chunk, true, null, flood, null, List.of()));
      assertFalse(protectedAt(0, -21, 15, chunk, true, null, flood, null, List.of()));
      assertFalse(protectedAt(15, -20, 0, chunk, true, null, flood, null, List.of()));
   }

   @Test
   void structureProtectionUsesWorldCoordinatesInNegativeChunks() {
      ChunkPos chunk = new ChunkPos(-2, -3);
      var boxes = List.of(new UndergroundStructureExclusion.Box(-32, 2000, -48, -30, 2010, -46, 0, true));
      assertTrue(protectedAt(0, 2005, 0, chunk, true, null, null, null, boxes));
      assertFalse(protectedAt(15, 2005, 15, chunk, true, null, null, null, boxes));
      assertFalse(protectedAt(0, 1999, 0, chunk, true, null, null, null, boxes));
   }

   private static boolean protectedAt(int x, int y, int z, ChunkPos chunk, boolean reachSurface,
      int[] surface, int[] flood, int[] floor, List<UndergroundStructureExclusion.Box> boxes) {
      return TellusVanillaCarverRunner.isProtected(x, y, z, chunk, reachSurface, surface, flood, floor, boxes);
   }

   private static int[] columns(int value) {
      int[] result = new int[256];
      Arrays.fill(result, value);
      return result;
   }
}
