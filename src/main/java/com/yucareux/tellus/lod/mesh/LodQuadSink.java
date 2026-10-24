package com.yucareux.tellus.lod.mesh;

/**
 * Receives merged faces from {@link LodGreedyMesher}.
 *
 * <p>Horizontal coordinates are in tile cells, heights in blocks above the world minimum.</p>
 * <ul>
 *    <li>{@code UP}/{@code DOWN}: {@code plane} is the face height; the face covers cells
 *    {@code u..u+width} along X and {@code v..v+height} along Z.</li>
 *    <li>{@code WEST}/{@code EAST}: {@code plane} is the X cell boundary; the face covers
 *    {@code u..u+width} along Z and blocks {@code v..v+height}.</li>
 *    <li>{@code NORTH}/{@code SOUTH}: {@code plane} is the Z cell boundary; the face covers
 *    {@code u..u+width} along X and blocks {@code v..v+height}.</li>
 * </ul>
 */
public interface LodQuadSink {
   void quad(LodFace face, int plane, int u, int v, int width, int height, int material, int light, boolean translucent);

   /** A wall hanging below the edge of a tile that has no neighbour data, covering cracks against it. */
   default void skirt(LodFace face, int plane, int u, int v, int width, int height, int material, int light) {
      this.quad(face, plane, u, v, width, height, material, light, false);
   }
}
