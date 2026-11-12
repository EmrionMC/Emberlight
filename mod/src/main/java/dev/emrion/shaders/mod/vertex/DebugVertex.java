package dev.emrion.shaders.mod.vertex;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;
import dev.emrion.shaders.format.VertexLayouts;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.pipeline.Set0Patcher;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only check of the vertex data ({@code -Demrion.debug.vertex=true}), until real gbuffers exist: switches the extended
 * formats on without a pack, loads the id maps from a converted manifest ({@code -Demrion.debug.vertex.manifest=<path>},
 * default: the newest one in the pack cache) and draws all terrain layers with a shader that shows one attribute per screen
 * quadrant (see debug_vertex.frag).
 */
public final class DebugVertex {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.vertex");
	/** Also extend and visualise entity vertices (needs every ENTITY pipeline swapped, see {@link #replacement}). */
	public static final boolean ENTITIES = false;
	private static final List<String> PLACEHOLDERS = concat(VertexLayouts.SET0_CANONICAL, List.of("Projection", "Globals"));
	private static final List<RenderPipeline> TERRAIN = List.of(RenderPipelines.SOLID_TERRAIN, RenderPipelines.SOLID_TERRAIN_MULTIDRAW,
			RenderPipelines.CUTOUT_TERRAIN, RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW, RenderPipelines.TRANSLUCENT_TERRAIN,
			RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW);
	private static final Map<RenderPipeline, CompiledRenderPipeline> BUILT = new IdentityHashMap<>();
	private static boolean resolvingVanilla;

	private DebugVertex() {}

	/** Client start (render thread, Vulkan active). */
	public static void init() {
		Path manifest = manifestPath();
		if (manifest != null) {
			try {
				Manifest m = ManifestJson.readManifest(manifest);
				long t0 = System.nanoTime();
				BlockIdTable table = BlockIdTable.build(m.idMaps());
				EmrionShadersClient.LOG.info("DebugVertex: ids from {} ({} block states, built in {} ms)", manifest, table.blockCount(),
						(System.nanoTime() - t0) / 1_000_000);
				EmrionVertexFormats.setIds(table);
			} catch (Exception e) {
				EmrionShadersClient.LOG.error("DebugVertex: cannot read {}", manifest, e);
			}
		} else {
			EmrionShadersClient.LOG.warn("DebugVertex: no converted manifest found, all ids are -1");
		}
		EmrionVertexFormats.setActive(true);
	}

	public static @Nullable CompiledRenderPipeline replacement(RenderPipeline pipeline) {
		if (resolvingVanilla || !TERRAIN.contains(pipeline)) return null;
		return BUILT.computeIfAbsent(pipeline, DebugVertex::build);
	}

	private static CompiledRenderPipeline build(RenderPipeline vanilla) {
		VulkanContext ctx = VulkanContext.get();
		FrontendRenderPipeline compiled;
		resolvingVanilla = true;
		try {
			compiled = (FrontendRenderPipeline) RenderSystem.getCompiledPipelineNullable(vanilla);
		} finally {
			resolvingVanilla = false;
		}
		boolean multidraw = vanilla.getLocation().getPath().contains("multidraw");
		List<VertexFormat> formats = new ArrayList<>(compiled.vertexFormats());
		formats.set(0, EmrionVertexFormats.TERRAIN);
		var extended = new FrontendRenderPipeline("emrion/debug_vertex/" + compiled.name(), compiled.backendRenderPipeline(), formats,
				compiled.uniformIndices(), compiled.uniforms(), compiled.colorTargetStates(), compiled.wantsDepthTexture(), compiled.pushConstantSize());
		List<BindGroupLayout.UniformDescription> bound = ((VulkanRenderPipeline) compiled.backendRenderPipeline()).uniforms();
		List<String> order = bound.stream().map(BindGroupLayout.UniformDescription::name).toList();
		byte[] vs = ShaderModules.resource("/assets/emrionshaders/spv/debug_vertex_" + (multidraw ? "mdi" : "ubo") + ".vert.spv");
		byte[] fs = ShaderModules.resource("/assets/emrionshaders/spv/debug_vertex.frag.spv");
		Set0Patcher.patch(vs, PLACEHOLDERS, order);
		Set0Patcher.patch(fs, PLACEHOLDERS, order);
		LongArrayList modules = LongArrayList.of(ShaderModules.create(ctx, vs), ShaderModules.create(ctx, fs));
		long set0Layout = GeometryPipelines.set0Layout(ctx, bound, 0);
		long layout = dev.emrion.shaders.mod.vk.Descriptors.pipelineLayout(ctx, compiled.pushConstantSize(), set0Layout);
		long pipeline = GeometryPipelines.builderFor(vanilla, extended, false)
				.stage(VK_SHADER_STAGE_VERTEX_BIT, modules.getLong(0)).stage(VK_SHADER_STAGE_FRAGMENT_BIT, modules.getLong(1))
				.layout(layout).build(ctx, ctx.pipelineCache().handle);
		EmrionShadersClient.LOG.info("DebugVertex: replacing {} (uniforms {})", vanilla.getLocation(), order);
		return new FrontendRenderPipeline(extended.name(), new VulkanRenderPipeline(ctx.device, pipeline, 0L, layout, set0Layout, modules, bound),
				formats, compiled.uniformIndices(), compiled.uniforms(), compiled.colorTargetStates(), compiled.wantsDepthTexture(),
				compiled.pushConstantSize());
	}

	private static @Nullable Path manifestPath() {
		String p = System.getProperty("emrion.debug.vertex.manifest");
		if (p != null) return Path.of(p);
		Path cache = FabricLoader.getInstance().getGameDir().resolve("emrionshaders").resolve("cache");
		try (Stream<Path> s = Files.list(cache)) {
			return s.map(d -> d.resolve("manifest.json")).filter(Files::isRegularFile)
					.max(Comparator.comparingLong(f -> f.toFile().lastModified())).orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> l = new ArrayList<>(a);
		l.addAll(b);
		return l;
	}
}
