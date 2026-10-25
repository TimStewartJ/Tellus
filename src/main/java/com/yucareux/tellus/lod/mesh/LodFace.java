package com.yucareux.tellus.lod.mesh;

/** Face directions, named like Minecraft's: north is -Z, west is -X. */
public enum LodFace {
   DOWN,
   UP,
   NORTH,
   SOUTH,
   WEST,
   EAST;

   public boolean horizontal() {
      return this == DOWN || this == UP;
   }
}
