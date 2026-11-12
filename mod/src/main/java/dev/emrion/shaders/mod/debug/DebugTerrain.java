package dev.emrion.shaders.mod.debug;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.format.VertexLayouts;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.pipeline.Set0Patcher;
import dev.emrion.shaders.mod.vk.DescriptorPool;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Geometry swap proof (dev only, {@code -Demrion.debug.terrain=true}): vanilla SOLID terrain (both the multidraw-indirect and
 * the per-section UBO variant) draws with a raw VkPipeline built from our SPIR-V. Set 0 = MC's push descriptors at the
 * patched binding order, set 1 = our descriptor set with a dynamic-offset UBO from the ring (a pulsing tint). The shader
 * shows face normals over the albedo.
 */
public final class DebugTerrain {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.terrain");
	/** Test-only extension of the canonical set-0 table: real packs read projection/camera from EmrionUniforms instead. */
	private static final List<String> PLACEHOLDERS = concat(VertexLayouts.SET0_CANONICAL, List.of("Projection", "Globals"));
	private static final int TINT_BYTES = 16;

	private static final Map<RenderPipeline, CompiledRenderPipeline> BUILT = new IdentityHashMap<>();
	private static boolean resolvingVanilla;
	private static @Nullable UboRing ring;
	private static long set1Layout, set1;
	private static final int[] DYNAMIC_OFFSETS = new int[1];

	private DebugTerrain() {}

	public static @Nullable CompiledRenderPipeline replacement(RenderPipeline pipeline) {
		if (resolvingVanilla) return null;
		if (pipeline != RenderPipelines.SOLID_TERRAIN && pipeline != RenderPipelines.SOLID_TERRAIN_MULTIDRAW) return null;
		CompiledRenderPipeline p = BUILT.get(pipeline);
		if (p == null) BUILT.put(pipeline, p = build(pipeline));
		return p;
	}

	/** Per frame, from {@code GameRenderer.render} HEAD: write this frame's tint into the ring. */
	public static void beginFrame() {
		if (ring == null) return;
		ring.nextFrame();
		long offset = ring.alloc(TINT_BYTES);
		float pulse = 0.75F + 0.25F * (float) Math.sin(System.nanoTime() / 4e8);
		long a = ring.address(offset);
		MemoryUtil.memPutFloat(a, pulse);
		MemoryUtil.memPutFloat(a + 4, pulse);
		MemoryUtil.memPutFloat(a + 8, 1.0F);
		MemoryUtil.memPutFloat(a + 12, 1.0F);
		DYNAMIC_OFFSETS[0] = (int) offset;
	}

	private static CompiledRenderPipeline build(RenderPipeline vanilla) {
		VulkanContext ctx = VulkanContext.get();
		resolvingVanilla = true;
		FrontendRenderPipeline compiled;
		try {
			compiled = (FrontendRenderPipeline) RenderSystem.getCompiledPipelineNullable(vanilla);
		} finally {
			resolvingVanilla = false;
		}
		if (ring == null) createSet1(ctx);
		boolean multidraw = vanilla == RenderPipelines.SOLID_TERRAIN_MULTIDRAW;
		// MC's set-0 binding order is the backend's list (first use across the vanilla shaders), not the frontend record's.
		List<BindGroupLayout.UniformDescription> bound = ((VulkanRenderPipeline) compiled.backendRenderPipeline()).uniforms();
		List<String> order = bound.stream().map(BindGroupLayout.UniformDescription::name).toList();
		EmrionShadersClient.LOG.info("DebugTerrain: replacing {} (uniforms {}, multidraw indirect in use: {})", vanilla.getLocation(), order,
				Minecraft.getInstance().levelRenderer.isChunkRenderingUsingMultiDrawIndirect());

		byte[] vs = ShaderModules.resource("/assets/emrionshaders/spv/debug_terrain_" + (multidraw ? "mdi" : "ubo") + ".vert.spv");
		byte[] fs = ShaderModules.resource("/assets/emrionshaders/spv/debug_terrain.frag.spv");
		Set0Patcher.patch(vs, PLACEHOLDERS, order);
		Set0Patcher.patch(fs, PLACEHOLDERS, order);
		LongArrayList modules = LongArrayList.of(ShaderModules.create(ctx, vs), ShaderModules.create(ctx, fs));
		long set0Layout = GeometryPipelines.set0Layout(ctx, bound);
		long layout = Descriptors.pipelineLayout(ctx, compiled.pushConstantSize(), set0Layout, set1Layout);
		long pipeline = GeometryPipelines.builderFor(vanilla, compiled)
				.stage(VK_SHADER_STAGE_VERTEX_BIT, modules.getLong(0)).stage(VK_SHADER_STAGE_FRAGMENT_BIT, modules.getLong(1))
				.layout(layout).build(ctx, ctx.pipelineCache().handle);
		return GeometryPipelines.wrap(ctx, compiled, pipeline, layout, set0Layout, modules,
				new GeometryPipelines.Set1(layout, set1, DYNAMIC_OFFSETS));
	}

	private static void createSet1(VulkanContext ctx) {
		ring = new UboRing(ctx, 256);
		set1Layout = new Descriptors.LayoutBuilder()
				.add(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).build(ctx, false);
		DescriptorPool pool = new DescriptorPool(ctx, 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1);
		set1 = pool.allocate(set1Layout);
		Descriptors.writeBuffer(ctx, set1, 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, ring.buffer.handle, 0, TINT_BYTES);
		beginFrame();
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> l = new ArrayList<>(a);
		l.addAll(b);
		return l;
	}
}
