package com.yucareux.tellus.lod.mesh;

/** What the mesher needs to know about a tile material. */
@FunctionalInterface
public interface LodMaterialTable {
   /**
    * True for materials drawn in the translucent pass, such as water. A translucent span hides only faces of
    * the same material, so the seabed under water keeps its top face.
    */
   boolean isTranslucent(int material);
}
