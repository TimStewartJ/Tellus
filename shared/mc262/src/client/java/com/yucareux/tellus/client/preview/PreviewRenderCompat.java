package com.yucareux.tellus.client.preview;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The renderer API varies by release; preview geometry stays shared. */
public final class PreviewRenderCompat {
   private PreviewRenderCompat() {}

   private static final RenderPipeline PREVIEW_SUN_PIPELINE = RenderPipeline.builder()
      .withLocation("pipeline/tellus_preview_sun")
      .withBindGroupLayout(BindGroupLayouts.GLOBALS)
      .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
      .withVertexShader("core/position_tex_color")
      .withFragmentShader("core/position_tex_color")
      .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
      .withColorTargetState(new ColorTargetState(BlendFunction.OVERLAY))
      .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
      .withPrimitiveTopology(PrimitiveTopology.QUADS)
      .build();
   private static final RenderPipeline PREVIEW_PIPELINE = RenderPipeline.builder()
      .withLocation("pipeline/tellus_terrain_preview")
      .withBindGroupLayout(BindGroupLayouts.GLOBALS)
      .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
      .withVertexShader("core/gui")
      .withFragmentShader("core/gui")
      .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
      .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
      .withPrimitiveTopology(PrimitiveTopology.QUADS)
      .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
      .withCull(false)
      .build();

   public static void blitSun(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int size) {
      graphics.blit(PREVIEW_SUN_PIPELINE, texture, x, y, 0.0F, 0.0F, size, size, 32, 32);
   }

   public abstract static class TerrainState implements GuiElementRenderState {
      @Override
      public RenderPipeline pipeline() {
         return PREVIEW_PIPELINE;
      }
   }
}
