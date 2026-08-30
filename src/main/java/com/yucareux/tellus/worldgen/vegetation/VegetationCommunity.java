package com.yucareux.tellus.worldgen.vegetation;

import com.yucareux.tellus.worldgen.MountainSurfaceRules;
import com.yucareux.tellus.worldgen.tree.TellusProceduralTreeGenerator;

/**
 * Broad vegetation physiognomies used below the individual tree-profile layer.
 */
public enum VegetationCommunity {
   TEMPERATE_FOREST,
   BOREAL_FOREST,
   SUBARCTIC_SCRUB,
   TROPICAL_MOIST_FOREST,
   TROPICAL_DRY_FOREST,
   PINE_OAK_FOREST,
   MEDITERRANEAN_SCRUB,
   SAVANNA,
   EUCALYPT_WOODLAND,
   MALLEE,
   TEMPERATE_SCRUB,
   XERIC_SCRUB,
   GRASSLAND,
   CROPLAND,
   WETLAND,
   ALPINE_TUNDRA,
   NONE;

   public static VegetationCommunity resolve(
      int coverClass, TellusProceduralTreeGenerator.Profile profile
   ) {
      if (coverClass == MountainSurfaceRules.ESA_WETLAND
         || coverClass == MountainSurfaceRules.ESA_MANGROVES
         || profile == TellusProceduralTreeGenerator.Profile.SWAMP) {
         return WETLAND;
      }
      if (coverClass == MountainSurfaceRules.ESA_CROPLAND) {
         return CROPLAND;
      }
      if (coverClass == MountainSurfaceRules.ESA_MOSS_LICHEN) {
         return ALPINE_TUNDRA;
      }
      if (coverClass == MountainSurfaceRules.ESA_BARE
         || coverClass == MountainSurfaceRules.ESA_SNOW_ICE
         || coverClass == MountainSurfaceRules.ESA_BUILT
         || coverClass == MountainSurfaceRules.ESA_WATER
         || coverClass == MountainSurfaceRules.ESA_NO_DATA) {
         return NONE;
      }

      boolean shrubland = coverClass == MountainSurfaceRules.ESA_SHRUBLAND;
      boolean grassland = coverClass == MountainSurfaceRules.ESA_GRASSLAND;
      return switch (profile) {
         case TROPICAL -> shrubland ? TEMPERATE_SCRUB : grassland ? GRASSLAND : TROPICAL_MOIST_FOREST;
         case DRY_BROADLEAF -> shrubland ? XERIC_SCRUB : grassland ? SAVANNA : TROPICAL_DRY_FOREST;
         case PINE -> shrubland ? MEDITERRANEAN_SCRUB : grassland ? GRASSLAND : PINE_OAK_FOREST;
         case MEDITERRANEAN -> shrubland ? MEDITERRANEAN_SCRUB : grassland ? GRASSLAND : MEDITERRANEAN_SCRUB;
         case EUCALYPTUS -> shrubland ? TEMPERATE_SCRUB : grassland ? SAVANNA : EUCALYPT_WOODLAND;
         case MALLEE -> MALLEE;
         case SAVANNA -> SAVANNA;
         case CONIFER, TALL_CONIFER, COAST_REDWOOD -> shrubland
            ? TEMPERATE_SCRUB
            : grassland ? GRASSLAND : BOREAL_FOREST;
         case SUBARCTIC_BIRCH -> SUBARCTIC_SCRUB;
         case BIRCH, CHERRY, DARK_BROADLEAF, PALE_BROADLEAF, TEMPERATE_BROADLEAF -> shrubland
            ? TEMPERATE_SCRUB
            : grassland ? GRASSLAND : TEMPERATE_FOREST;
         case SWAMP -> WETLAND;
      };
   }

   public boolean wooded() {
      return switch (this) {
         case TEMPERATE_FOREST,
            BOREAL_FOREST,
            TROPICAL_MOIST_FOREST,
            TROPICAL_DRY_FOREST,
            PINE_OAK_FOREST,
            EUCALYPT_WOODLAND -> true;
         default -> false;
      };
   }

   public boolean shrubDominated() {
      return switch (this) {
         case SUBARCTIC_SCRUB,
            MEDITERRANEAN_SCRUB,
            MALLEE,
            TEMPERATE_SCRUB,
            XERIC_SCRUB -> true;
         default -> false;
      };
   }
}
