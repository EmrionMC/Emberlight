package dev.emrion.shaders.mod.pipeline;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * MC 26 draws base and glint in one pass; packs expect Iris's split (base via gbuffers_entities, glint as a second draw via
 * gbuffers_armor_glint), so with a pack the same vertices are drawn again with {@link #PIPELINE} and the glint matrix.
 */
public final class GlintPass {
	private GlintPass() {}

	public static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS).withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS).withBindGroupLayout(BindGroupLayouts.FOG)
			.withLocation("emberlight/pipeline/entity_glint").withVertexShader("core/glint").withFragmentShader("core/glint")
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0).withCull(false).withColorTargetState(new ColorTargetState(BlendFunction.GLINT))
			.withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS)
			// EQUAL as Iris: only where the base wrote depth (not its cut-out texels, not through walls); the converter declares
			// gl_Position invariant so the pack's entity and glint programs give bit-identical depth
			.withDepthStencilState(new DepthStencilState(CompareOp.EQUAL, false)).build();

	/** A pipeline that draws base and glint in one pass (vanilla), not the separate glint pipeline. */
	public static boolean fused(RenderPipeline p) {
		// by the GLINT_SNIPPET define, not the name: ITEM_CUTOUT_GLINT(_SPECIAL) are registered as pipeline/item_cutout
		return p != RenderPipelines.GLINT && p != PIPELINE && p.getShaderDefines().flags().contains("GLINT");
	}

	// glint transforms written at prepare time (outside any render pass), taken by the draw
	private static final Map<Object, GpuBufferSlice> pending = new IdentityHashMap<>();
	private static GpuBufferSlice last;

	public static void written(GpuBufferSlice glint) {
		last = glint;
	}

	public static void prepared(Object prepared) {
		if (last == null) return;
		if (pending.size() > 4096) pending.clear(); // prepared but never drawn
		pending.put(prepared, last);
		last = null;
	}

	public static GpuBufferSlice take(Object prepared) {
		return pending.remove(prepared);
	}
}
