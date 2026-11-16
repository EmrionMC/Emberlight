package dev.emrion.shaders.mod.pipeline;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.emrion.shaders.format.VertexLayouts;
import dev.emrion.shaders.mod.pbr.PbrTextures;
import dev.emrion.shaders.mod.vk.Blend;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder;
import dev.emrion.shaders.mod.vk.VulkanContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Raw geometry pipelines MC draws with unchanged: same vertex formats and set-0 push descriptors, plus our set 1 and EmrionDraw push constants. */
public final class GeometryPipelines {
	private GeometryPipelines() {}

	/** Per-pipeline draw constants: alpha-test reference and the extra set-0 samplers the pack reads but vanilla does not push. */
	public record Draw(float alphaRef, boolean blockAtlas, int firstExtra, int[] extras, boolean defaults, boolean sodium) {}

	/** Vertex buffer slot of the constant default attributes (MC uses slots 0 and 1). */
	public static final int DEFAULTS_BINDING = 15;
	/** Canonical input → {VkFormat, offset} in {@link #defaultsBuffer}, formats as in EMRION_BLOCK. */
	public static final Map<String, int[]> DEFAULTS = Map.of(
			"Normal", new int[] {VK_FORMAT_R8G8B8A8_SNORM, 0}, "mc_Entity", new int[] {VK_FORMAT_R16G16_SINT, 4},
			"mc_midTexCoord", new int[] {VK_FORMAT_R32G32_SFLOAT, 8}, "at_tangent", new int[] {VK_FORMAT_R8G8B8A8_SNORM, 16},
			"at_midBlock", new int[] {VK_FORMAT_R16G16B16A16_SFLOAT, 20});
	/** Normal up, mc_Entity (-1, 0) as Iris for unmapped blocks, midTexCoord 0, tangent +X (w 1), midBlock 0. */
	public static void writeDefaults(long address) {
		MemoryUtil.memSet(address, 0, 32);
		MemoryUtil.memPutByte(address + 1, (byte) 127);
		MemoryUtil.memPutShort(address + 4, (short) -1);
		MemoryUtil.memPutByte(address + 16, (byte) 127);
		MemoryUtil.memPutByte(address + 19, (byte) 127);
	}
	/** The defaults vertex buffer (32 bytes, see {@link #writeDefaults}); set by the executor. */
	public static long defaultsBuffer;

	/** Image views and samplers of the extra set-0 samplers, by kind; refreshed per frame by the executor, the PBR views per Sampler0. */
	public static final long[] EXTRA_VIEWS = new long[5], EXTRA_SAMPLERS = new long[5];
	/** First extra kind that follows Sampler0 (normals, specular). */
	private static final int PBR = 3;

	private static final Map<VulkanRenderPipeline, Draw> OURS = new ConcurrentHashMap<>();

	// Render-thread state of the active geometry phase, set by the frame executor.
	/** Set 1 of the phase's flip state, and the dynamic offsets of EmrionUniforms / EmrionPhase. */
	public static long set;
	public static final int[] DYNAMIC_OFFSETS = new int[2];
	/** Textures of every TextureAtlas (TextureAtlasMixin); weak, atlases recreate their texture on reload. */
	public static final Set<GpuTexture> ATLAS_TEXTURES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	/** atlasSize of the current Sampler0 (Iris: the bound albedo texture's size if it is a TextureAtlas, else 0). */
	private static int atlasWidth, atlasHeight;
	/** The texture of the current Sampler0 (null: none since the pass started). */
	private static @Nullable GpuTexture sampler0;
	private static VkCommandBuffer boundCb;
	private static VulkanRenderPipeline bound;
	private static Draw boundDraw;
	/** EmrionDraw push constants: entityColor, entityId, blockEntityId, currentRenderedItemId, alphaTestRef, atlasSize. */
	private static final ByteBuffer PUSH = MemoryUtil.memCalloc(40);

	/** Called from {@code VulkanRenderPass.setPipeline} TAIL. */
	public static void onSetPipeline(VkCommandBuffer cb, VulkanRenderPipeline pipeline) {
		if (dev.emrion.shaders.mod.debug.TerrainStats.ENABLED) dev.emrion.shaders.mod.debug.TerrainStats.bound(cb, NAMES.get(pipeline));
		Draw d = OURS.get(pipeline);
		if (d == null) {
			boundCb = null;
			return;
		}
		boundCb = cb;
		bound = pipeline;
		boundDraw = d;
		bind(cb, pipeline, d);
	}

	/** Vanilla names of our wrapped pipelines (TerrainStats). */
	private static final Map<VulkanRenderPipeline, String> NAMES = new ConcurrentHashMap<>();

	/** Re-binds set 1 of the currently bound pipeline (after the EmrionPhase offset changed for a new renderStage). */
	public static void rebind() {
		if (boundCb == null) return;
		// only the dynamic offsets changed: set 0's push descriptors, the vertex buffer and the push constants stay valid
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindDescriptorSets(boundCb, VK_PIPELINE_BIND_POINT_GRAPHICS, bound.pipelineLayout(), 1, stack.longs(set), stack.ints(DYNAMIC_OFFSETS));
		}
	}

	/** MC pushes constants at offset 0; for our Sodium terrain pipelines they move to SODIUM_PUSH_OFFSET so EmrionDraw survives. */
	public static boolean pushConstants(VkCommandBuffer cb, VulkanRenderPipeline pipeline, ByteBuffer value) {
		Draw d = pipeline == null ? null : OURS.get(pipeline);
		if (d == null || !d.sodium) return false;
		vkCmdPushConstants(cb, pipeline.pipelineLayout(), VK_SHADER_STAGE_ALL, VertexLayouts.SODIUM_PUSH_OFFSET, value);
		return true;
	}

	/** The render pass ended: nothing of ours is bound any more. */
	public static void unbind() {
		boundCb = null;
		bound = null;
		atlasWidth = atlasHeight = 0; // a new pass starts with no Sampler0
		sampler0 = null;
	}

	private static void bind(VkCommandBuffer cb, VulkanRenderPipeline pipeline, Draw d) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(), 1, stack.longs(set), stack.ints(DYNAMIC_OFFSETS));
		}
		PbrTextures.views(sampler0, EXTRA_VIEWS, PBR); // maps that finished loading since the last bind
		if (d.extras.length > 0) pushExtras(cb, pipeline.pipelineLayout(), d, 0);
		if (d.defaults) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				vkCmdBindVertexBuffers(cb, DEFAULTS_BINDING, stack.longs(defaultsBuffer), stack.longs(0));
			}
		}
		PUSH.putFloat(28, d.alphaRef).putInt(32, atlasWidth).putInt(36, atlasHeight);
		vkCmdPushConstants(cb, pipeline.pipelineLayout(), VK_SHADER_STAGE_ALL, 0, PUSH);
	}

	/** Sampler0 changed (also replayed after setPipeline): update atlasSize and the PBR maps of the new texture. */
	public static void sampler0(@Nullable Object value) {
		GpuTextureView view = value instanceof TextureViewAndSampler t ? t.view() : null;
		GpuTexture texture = view == null ? null : view.texture();
		if (texture == sampler0) return;
		sampler0 = texture;
		boolean atlas = view != null && ATLAS_TEXTURES.contains(texture);
		int w = atlas ? view.getWidth(0) : 0, h = atlas ? view.getHeight(0) : 0;
		boolean sizeChanged = w != atlasWidth || h != atlasHeight;
		atlasWidth = w;
		atlasHeight = h;
		if (boundCb == null) return; // the next bind of ours looks the maps up
		if (sizeChanged) {
			PUSH.putInt(32, w).putInt(36, h);
			vkCmdPushConstants(boundCb, bound.pipelineLayout(), VK_SHADER_STAGE_ALL, 0, PUSH);
		}
		long normals = EXTRA_VIEWS[PBR], specular = EXTRA_VIEWS[PBR + 1];
		PbrTextures.views(texture, EXTRA_VIEWS, PBR);
		if (normals != EXTRA_VIEWS[PBR] || specular != EXTRA_VIEWS[PBR + 1]) pushExtras(boundCb, bound.pipelineLayout(), boundDraw, PBR);
	}

	/** Pushes the extra samplers of kind >= minKind; MC pushes its own bindings before the draw (push descriptors merge). */
	private static void pushExtras(VkCommandBuffer cb, long layout, Draw d, int minKind) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = VkWriteDescriptorSet.calloc(d.extras.length, stack);
			for (int i = 0; i < d.extras.length; i++) {
				if (d.extras[i] < minKind) continue;
				var info = VkDescriptorImageInfo.calloc(1, stack).imageView(EXTRA_VIEWS[d.extras[i]]).sampler(EXTRA_SAMPLERS[d.extras[i]])
						.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
				writes.get().sType$Default().dstBinding(d.firstExtra + i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
						.pImageInfo(info);
			}
			if (writes.flip().remaining() > 0) KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, writes);
		}
	}

	/** Same push-descriptor layout as MC's backend pipeline (binding = index in its uniforms(), which differs from the frontend list). */
	public static long set0Layout(VulkanContext ctx, List<BindGroupLayout.UniformDescription> uniforms, int extraSamplers) {
		var b = new Descriptors.LayoutBuilder();
		for (int i = 0; i < uniforms.size(); i++) {
			int type = switch (uniforms.get(i).type()) {
				case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
				case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
				case TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
			};
			b.add(i, type, VK_SHADER_STAGE_ALL_GRAPHICS);
		}
		for (int i = 0; i < extraSamplers; i++) b.add(uniforms.size() + i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL_GRAPHICS);
		return b.build(ctx, true);
	}

	/** Wireframe debug view (PackManager.setWireframe): read when pipelines are built; needs fillModeNonSolid. */
	public static volatile boolean wireframe;

	/** Builder preset from the vanilla pipeline, with depth state converted to our forward-Z (compare mirrored, bias negated). */
	public static GraphicsPipelineBuilder builderFor(RenderPipeline vanilla, FrontendRenderPipeline compiled) {
		return builderFor(vanilla, compiled, true);
	}

	/** {@code forwardZ = false}: keep MC's reverse-Z depth state (for pipelines drawn in MC's own passes). */
	public static GraphicsPipelineBuilder builderFor(RenderPipeline vanilla, FrontendRenderPipeline compiled, boolean forwardZ) {
		var b = new GraphicsPipelineBuilder()
				.cull(vanilla.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
				.polygonMode(wireframe ? VK_POLYGON_MODE_LINE : VulkanConst.toVk(vanilla.getPolygonMode()))
				.topology(VulkanConst.toVk(vanilla.getPrimitiveTopology()));
		List<VertexFormat> formats = compiled.vertexFormats();
		for (int slot = 0; slot < formats.size(); slot++) {
			VertexFormat f = formats.get(slot);
			if (f == null) continue;
			b.vertexBinding(slot, f.getVertexSize(), f.getStepRate());
			for (VertexFormatElement e : f.getElements()) {
				int location = VertexLayouts.inputLocation(e.name());
				if (location >= 0) b.attribute(location, slot, VulkanConst.toVk(e.format()), e.offset());
			}
		}
		DepthStencilState depth = vanilla.getDepthStencilState();
		// Pipelines without depth state still render in passes with depth (MC builds that variant with depth format D32).
		if (depth != null) {
			int op = VulkanConst.toVk(depth.depthTest());
			float sign = forwardZ ? -1 : 1;
			b.depth(VK_FORMAT_D32_SFLOAT, true, depth.writeDepth(), forwardZ ? forwardZ(op) : op)
					.depthBias(sign * depth.depthBiasConstant(), sign * depth.depthBiasScaleFactor());
		} else {
			b.depth(VK_FORMAT_D32_SFLOAT, false, false, VK_COMPARE_OP_ALWAYS);
		}
		return b;
	}

	/** MC's reverse-Z compare op as the same test on forward-Z depth. */
	static int forwardZ(int op) {
		return switch (op) {
			case VK_COMPARE_OP_GREATER -> VK_COMPARE_OP_LESS;
			case VK_COMPARE_OP_GREATER_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
			case VK_COMPARE_OP_LESS -> VK_COMPARE_OP_GREATER;
			case VK_COMPARE_OP_LESS_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
			default -> op;
		};
	}

	/** The vanilla pipeline's blend function on colour target 0 (gbuffers "vanilla" blend), null if blending is off. */
	public static Blend vanillaBlend(FrontendRenderPipeline compiled) {
		ColorTargetState t = compiled.colorTargetStates().isEmpty() ? null : compiled.colorTargetStates().getFirst();
		return t == null ? null : t.blendFunction().map(GeometryPipelines::blend).orElse(null);
	}

	private static Blend blend(BlendFunction f) {
		return new Blend(VulkanConst.toVk(f.color().sourceFactor()), VulkanConst.toVk(f.color().destFactor()), VulkanConst.toVk(f.color().op()),
				VulkanConst.toVk(f.alpha().sourceFactor()), VulkanConst.toVk(f.alpha().destFactor()), VulkanConst.toVk(f.alpha().op()));
	}

	/** Wraps a raw pipeline so MC draws with it in place of {@code vanilla}; MC destroys pipeline and layouts on close. */
	public static FrontendRenderPipeline wrap(VulkanContext ctx, FrontendRenderPipeline vanilla, long pipeline, long layout, long set0Layout,
			List<ColorTargetState> targets, Draw draw) {
		var uniforms = ((VulkanRenderPipeline) vanilla.backendRenderPipeline()).uniforms();
		var backend = new VulkanRenderPipeline(ctx.device, pipeline, 0L, layout, set0Layout, new LongArrayList(), uniforms);
		OURS.put(backend, draw);
		NAMES.put(backend, vanilla.name());
		return new FrontendRenderPipeline("emrion/" + vanilla.name(), backend, vanilla.vertexFormats(), vanilla.uniformIndices(), vanilla.uniforms(),
				targets, vanilla.wantsDepthTexture(), vanilla.pushConstantSize());
	}

	/** Closes a wrapped pipeline (destroyed by MC after the frames in flight) and forgets it. */
	public static void close(FrontendRenderPipeline p) {
		OURS.remove((VulkanRenderPipeline) p.backendRenderPipeline());
		NAMES.remove((VulkanRenderPipeline) p.backendRenderPipeline());
		p.close();
	}
}
