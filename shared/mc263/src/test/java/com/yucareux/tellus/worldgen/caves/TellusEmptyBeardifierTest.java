package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.junit.jupiter.api.Test;

class TellusEmptyBeardifierTest {
   @Test
   void projectedVanillaCaveFieldIsIndependentOfRetargetedStructures() {
      assertEquals(0.0F, TellusEmptyBeardifier.instance().sampleValue(SamplerContext.EMPTY_UNCACHED, 32, 2048, -48));
   }
}
