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

/** Minecraft 26.2 render pipelines for the terrain preview. */
final class TerrainPreviewPipelines {
   static final RenderPipeline SUN = RenderPipeline.builder()
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
   static final RenderPipeline TERRAIN = RenderPipeline.builder()
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

   private TerrainPreviewPipelines() {
   }

   /** Supplies the terrain pipeline so the shared render state does not name the version-specific pipeline type. */
   interface TerrainState extends GuiElementRenderState {
      @Override
      default RenderPipeline pipeline() {
         return TERRAIN;
      }
   }
}
