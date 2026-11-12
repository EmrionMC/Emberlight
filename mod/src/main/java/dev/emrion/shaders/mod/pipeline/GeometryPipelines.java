package dev.emrion.shaders.mod.pipeline;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.format.VertexLayouts;
import dev.emrion.shaders.mod.vk.Blend;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.VulkanContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Raw geometry pipelines that MC draws with unchanged (ARCHITECTURE §5.1): same vertex formats, same set-0 push
 * descriptors (binding = index in the vanilla {@code uniforms()} list), plus our descriptor set 1, bound when MC sets the pipeline.
 */
public final class GeometryPipelines {
	private GeometryPipelines() {}

	/** Set 1 of one of our pipelines. {@code dynamicOffsets} is updated in place each frame. */
	public record Set1(long pipelineLayout, long set, int[] dynamicOffsets) {}

	private static final Map<VulkanRenderPipeline, Set1> OURS = new IdentityHashMap<>();

	/** Called from {@code VulkanRenderPass.setPipeline} TAIL. */
	public static void onSetPipeline(VkCommandBuffer cb, VulkanRenderPipeline pipeline) {
		Set1 s = OURS.get(pipeline);
		if (s == null) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, s.pipelineLayout, 1, stack.longs(s.set), stack.ints(s.dynamicOffsets));
		}
	}

	/**
	 * Push-descriptor layout identical to MC's for the vanilla <em>backend</em> pipeline's {@code uniforms()} (binding = index,
	 * VS|FS). That list holds only what the vanilla shaders use, in first-use order; the frontend record's list differs.
	 */
	public static long set0Layout(VulkanContext ctx, List<BindGroupLayout.UniformDescription> uniforms) {
		var b = new Descriptors.LayoutBuilder();
		for (int i = 0; i < uniforms.size(); i++) {
			int type = switch (uniforms.get(i).type()) {
				case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				case TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
			};
			b.add(i, type, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
		}
		return b.build(ctx, true);
	}

	/**
	 * Builder preset from the vanilla pipeline: vertex input (canonical locations, VertexLayouts), raster, depth and
	 * color-target state. The caller adds shader stages and the layout.
	 */
	public static GraphicsPipelineBuilder builderFor(RenderPipeline vanilla, FrontendRenderPipeline compiled) {
		var b = new GraphicsPipelineBuilder()
				.cull(vanilla.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
				.polygonMode(VulkanConst.toVk(vanilla.getPolygonMode()))
				.topology(VulkanConst.toVk(vanilla.getPrimitiveTopology()));
		List<VertexFormat> formats = compiled.vertexFormats();
		for (int slot = 0; slot < formats.size(); slot++) {
			VertexFormat f = formats.get(slot);
			if (f == null) continue;
			b.vertexBinding(slot, f.getVertexSize(), f.getStepRate());
			for (VertexFormatElement e : f.getElements()) b.attribute(VertexLayouts.location(e.name()), slot, VulkanConst.toVk(e.format()), e.offset());
		}
		DepthStencilState depth = vanilla.getDepthStencilState();
		// Pipelines without depth state still render in passes with depth (MC builds that variant with depth format D32).
		if (depth != null) {
			b.depth(VK_FORMAT_D32_SFLOAT, true, depth.writeDepth(), VulkanConst.toVk(depth.depthTest()))
					.depthBias(depth.depthBiasConstant(), depth.depthBiasScaleFactor());
		} else {
			b.depth(VK_FORMAT_D32_SFLOAT, false, false, VK_COMPARE_OP_ALWAYS);
		}
		List<ColorTargetState> targets = compiled.colorTargetStates();
		int[] formatsVk = new int[targets.size()];
		Blend[] blends = new Blend[targets.size()];
		for (int i = 0; i < targets.size(); i++) {
			ColorTargetState t = targets.get(i);
			formatsVk[i] = t == null ? VK_FORMAT_UNDEFINED : VulkanConst.toVk(t.format());
			blends[i] = t == null ? null : t.blendFunction().map(GeometryPipelines::blend).orElse(null);
		}
		return b.colors(formatsVk, blends);
	}

	private static Blend blend(BlendFunction f) {
		return new Blend(VulkanConst.toVk(f.color().sourceFactor()), VulkanConst.toVk(f.color().destFactor()), VulkanConst.toVk(f.color().op()),
				VulkanConst.toVk(f.alpha().sourceFactor()), VulkanConst.toVk(f.alpha().destFactor()), VulkanConst.toVk(f.alpha().op()));
	}

	/**
	 * Wraps a raw pipeline so MC can draw with it in place of {@code vanilla}. The returned pipeline owns
	 * {@code pipeline}, {@code layout}, {@code set0Layout} and {@code modules} (MC destroys them on close).
	 */
	public static FrontendRenderPipeline wrap(VulkanContext ctx, FrontendRenderPipeline vanilla, long pipeline, long layout, long set0Layout,
			LongArrayList modules, Set1 set1) {
		var bound = ((VulkanRenderPipeline) vanilla.backendRenderPipeline()).uniforms();
		var backend = new VulkanRenderPipeline(ctx.device, pipeline, 0L, layout, set0Layout, modules, bound);
		OURS.put(backend, set1);
		return new FrontendRenderPipeline("emrion/" + vanilla.name(), backend, vanilla.vertexFormats(), vanilla.uniformIndices(), vanilla.uniforms(),
				vanilla.colorTargetStates(), vanilla.wantsDepthTexture(), vanilla.pushConstantSize());
	}
}
