package com.yucareux.tellus.worldgen;

import java.net.URL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighYPackedCoordinateBoundaryTest {
   @Test
   void clampsTransientBlockPosLookupsBeyondPackedSafetyRange() {
      assertClamped(0, HighYPackedCoordinateProfile.Y_MAX + 1, 0, 0, HighYPackedCoordinateProfile.Y_MAX, 0);
      assertClamped(0, HighYPackedCoordinateProfile.Y_MIN - 1, 0, 0, HighYPackedCoordinateProfile.Y_MIN, 0);
      assertClamped(
         Integer.MAX_VALUE,
         Integer.MAX_VALUE,
         Integer.MIN_VALUE,
         HighYPackedCoordinateProfile.X_MAX,
         HighYPackedCoordinateProfile.Y_MAX,
         HighYPackedCoordinateProfile.Z_MIN
      );
   }

   @Test
   void keepsStrictPackingForWorldDataValidation() {
      assertThrows(
         IllegalArgumentException.class,
         () -> HighYPackedCoordinateProfile.pack(0, HighYPackedCoordinateProfile.Y_MAX + 1, 0)
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> HighYPackedCoordinateProfile.pack(0, HighYPackedCoordinateProfile.Y_MIN - 1, 0)
      );
   }

   @Test
   void detectsSableFromItsMainClass() {
      assertFalse(HighYPackedCoordinateProfile.isBlockedBySable());

      ClassLoader withSable = new ClassLoader(null) {
         @Override
         protected URL findResource(String name) {
            return name.equals("dev/ryanhcode/sable/Sable.class") ? HighYPackedCoordinateProfile.class.getResource("HighYPackedCoordinateProfile.class") : null;
         }
      };
      assertTrue(HighYPackedCoordinateProfile.hasSable(withSable));
   }

   private static void assertClamped(int x, int y, int z, int expectedX, int expectedY, int expectedZ) {
      long packed = HighYPackedCoordinateProfile.packClamped(x, y, z);
      assertEquals(expectedX, HighYPackedCoordinateProfile.unpackX(packed));
      assertEquals(expectedY, HighYPackedCoordinateProfile.unpackY(packed));
      assertEquals(expectedZ, HighYPackedCoordinateProfile.unpackZ(packed));
   }
}
