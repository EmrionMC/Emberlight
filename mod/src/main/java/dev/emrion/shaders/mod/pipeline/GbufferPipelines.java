package dev.emrion.shaders.mod.pipeline;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.VertexLayouts;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.Formats;
import dev.emrion.shaders.mod.runtime.PackPipelines;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vk.Blend;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Every gbuffers pipeline of a pack dimension, built at pack load (ARCHITECTURE §5.7): for each draw context and each
 * vanilla pipeline that can draw in a world phase, the pack program Iris would use (GbufferMapping + fallback chain) with the
 * vertex variant of the vanilla vertex format, set 0 patched to the vanilla binding order, and its outputs moved to the
 * shared gbuffers attachment list. Nothing is created in a frame.
 */
public final class GbufferPipelines {
	/**
	 * Draw contexts: the hand phases pick other programs and hide the other half of the hand's draws; SHADOW draws with the
	 * shadow programs into the shadowcolor attachments, without backface culling (Iris ShadowRenderer); BLOCK_ENTITY is the
	 * world context while block-entity render types draw (Iris BLOCK_ENTITIES phase: entity programs become gbuffers_block).
	 */
	public enum Context { WORLD, HAND_SOLID, HAND_TRANSLUCENT, SHADOW, BLOCK_ENTITY }

	/** The attachment list of every gbuffers pass: colortex targets written by any gbuffers program, by index. */
	public final List<String> targets;
	public final List<ColorTargetState> targetStates;
	/** The same for the shadow passes: shadowcolor targets written by any shadow program. */
	public final List<String> shadowTargets;
	public final List<ColorTargetState> shadowTargetStates;
	private final Map<RenderPipeline, FrontendRenderPipeline>[] byContext;
	private final List<FrontendRenderPipeline> all = new ArrayList<>();

	private record Key(String program, String variant, RenderPipeline vanilla, boolean hidden, boolean shadow) {}

	@SuppressWarnings("unchecked")
	public GbufferPipelines(VulkanContext ctx, Path cacheDir, Manifest manifest, Manifest.Dimension dim, long set1Layout,
			Map<RenderPipeline, FrontendRenderPipeline> vanilla, ExecutorService pool) {
		targets = outputUnion(dim, "gbuffers", "colortex");
		shadowTargets = outputUnion(dim, "shadow", "shadowcolor");
		Map<String, GpuFormat> formats = new HashMap<>();
		for (var t : dim.targets()) if (targets.contains(t.name()) || shadowTargets.contains(t.name())) formats.put(t.name(), Formats.gpu(t.format()));
		targetStates = targets.stream().map(t -> new ColorTargetState(Optional.empty(), formats.get(t), ColorTargetState.WRITE_ALL)).toList();
		shadowTargetStates = shadowTargets.stream().map(t -> new ColorTargetState(Optional.empty(), formats.get(t), ColorTargetState.WRITE_ALL)).toList();

		// Iris program name → the pack program that serves it (the converter resolved file fallbacks into fallbackFor).
		Map<String, String> serving = new HashMap<>();
		for (var e : dim.programs().entrySet()) {
			if (!e.getValue().kind().equals("gbuffers") && !e.getValue().kind().equals("shadow")) continue;
			serving.put(e.getKey(), e.getKey());
			for (String f : e.getValue().fallbackFor()) serving.putIfAbsent(f, e.getKey());
		}

		Map<Key, CompletableFuture<FrontendRenderPipeline>> jobs = new HashMap<>();
		byContext = new Map[Context.values().length];
		Map<RenderPipeline, Key>[] keys = new Map[byContext.length];
		Map<String, byte[]> spv = new ConcurrentHashMap<>();
		int pushSize = manifest.uniforms().pushConstants().size();
		List<String> table = new ArrayList<>();
		for (Context c : Context.values()) {
			keys[c.ordinal()] = new IdentityHashMap<>();
			boolean shadow = c == Context.SHADOW;
			if (shadow && !serving.containsKey("shadow")) continue; // no shadow program: no shadow pass in this dimension
			for (var raw : vanilla.entrySet()) {
				var v = Map.entry(raw.getKey(), extended(raw.getValue()));
				GbufferMapping.Entry e = GbufferMapping.get(v.getKey());
				List<String> variants = variants(v.getValue());
				if (e == null) e = new GbufferMapping.Entry(hasUv0(v.getValue()) ? "gbuffers_textured" : "gbuffers_basic", GbufferMapping.OFF, true);
				String world = e.program();
				if (shadow) e = GbufferMapping.shadow(e);
				if (c == Context.BLOCK_ENTITY) e = GbufferMapping.blockEntity(e);
				String iris = c == Context.WORLD || c == Context.BLOCK_ENTITY || shadow ? e.program() : GbufferMapping.handProgram(e);
				boolean hidden = c == Context.HAND_SOLID && e.translucent() || c == Context.HAND_TRANSLUCENT && !e.translucent();
				String program = null, variant = null, why = variants.isEmpty() ? "no vertex variant for " + v.getValue().vertexFormats() : null;
				search:
				for (String candidate : GbufferMapping.chain(iris)) {
					String p = serving.get(candidate);
					if (p == null) continue;
					for (String var : variants) {
						if (!dim.programs().get(p).stages().vertex().variants().containsKey(var)) continue;
						String misfit = set0Misfit(cacheDir, spv, dim.programs().get(p), var, v.getValue());
						if (misfit == null) {
							// GL draws nothing when the primitive does not fit the program's stages (Iris has no fallback)
							why = primitiveMisfit(cacheDir, spv, dim.programs().get(p), var, raw.getKey());
							if (why == null) {
								program = p;
								variant = var;
							} else why = p + "[" + var + "]: " + why;
							break search;
						}
						if (why == null) why = p + "[" + var + "]: " + misfit;
					}
					if (why == null) why = p + " has none of " + variants;
				}
				Key k = new Key(program, variant, v.getKey(), hidden, shadow);
				keys[c.ordinal()].put(v.getKey(), k);
				final GbufferMapping.Entry entry = e;
				jobs.computeIfAbsent(k, kk -> CompletableFuture.supplyAsync(() ->
						build(ctx, cacheDir, spv, dim, kk, entry, v.getValue(), set1Layout, pushSize), pool));
				if (c == Context.WORLD || shadow || !iris.equals(world))
					table.add(c + " " + v.getKey().getLocation() + " -> " + iris + " => " + (program == null ? "(nothing drawn: " + why + ")" : program + "[" + variant + "]"));
			}
		}
		try {
			CompletableFuture.allOf(jobs.values().toArray(CompletableFuture[]::new)).join();
		} catch (RuntimeException failed) {
			// the pipelines that did build are never used: release them (MC's pipeline close belongs on the render thread)
			List<FrontendRenderPipeline> done = jobs.values().stream().filter(j -> j.state() == java.util.concurrent.Future.State.SUCCESS).map(CompletableFuture::join).toList();
			net.minecraft.client.Minecraft.getInstance().execute(() -> done.forEach(GeometryPipelines::close));
			throw failed;
		}
		for (Context c : Context.values()) {
			Map<RenderPipeline, FrontendRenderPipeline> m = new IdentityHashMap<>();
			keys[c.ordinal()].forEach((p, k) -> m.put(p, jobs.get(k).join()));
			byContext[c.ordinal()] = m;
		}
		jobs.values().forEach(j -> all.add(j.join()));
		table.sort(Comparator.naturalOrder());
		EmrionShadersClient.LOG.info("Gbuffers attachments {}; {} pipelines; mapping:\n  {}", targets, all.size(), String.join("\n  ", table));
	}

	public FrontendRenderPipeline get(Context c, RenderPipeline vanilla) {
		return byContext[c.ordinal()].get(vanilla);
	}

	/** The colour attachments of a pass in context {@code c}. */
	public List<String> targets(Context c) {
		return c == Context.SHADOW ? shadowTargets : targets;
	}

	/** Every target named {@code prefix}N that a program of {@code kind} writes, ordered by N. */
	private static List<String> outputUnion(Manifest.Dimension dim, String kind, String prefix) {
		Map<Integer, String> union = new TreeMap<>();
		for (var p : dim.programs().values()) {
			if (!p.kind().equals(kind)) continue;
			for (var o : p.outputs()) union.put(Integer.parseInt(o.target().replace(prefix, "")), o.target());
		}
		return List.copyOf(union.values());
	}

	public void destroyLater() {
		all.forEach(GeometryPipelines::close);
	}

	// ---- variant and set-0 selection -----------------------------------------------------------------------------------

	/**
	 * The FORMAT §7.1 vertex variants a vanilla pipeline's vertex buffers can feed, best first: the terrain variants for
	 * chunk pipelines, else every variant whose attributes the format has, most attributes first.
	 */
	static List<String> variants(FrontendRenderPipeline vanilla) {
		List<VertexFormat> formats = vanilla.vertexFormats();
		VertexFormat f = formats.isEmpty() ? null : formats.getFirst();
		if (f == null) return List.of();
		if (formats.size() > 1 && formats.get(1) != null && formats.get(1).getElements().stream().anyMatch(e -> e.name().equals("ChunkPosition")))
			return List.of("terrain_mdi");
		if (f == EmrionVertexFormats.terrain()) return List.of("terrain_ubo");
		var names = f.getElements().stream().map(VertexFormatElement::name).collect(java.util.stream.Collectors.toSet());
		return VertexLayouts.VARIANTS.entrySet().stream()
				.filter(v -> !v.getKey().startsWith("terrain") && !v.getValue().isEmpty() && names.containsAll(v.getValue()))
				.filter(v -> !v.getKey().equals("block") || f == DefaultVertexFormat.BLOCK)
				.sorted(Comparator.comparingInt((Map.Entry<String, List<String>> v) -> -v.getValue().size()).thenComparing(Map.Entry::getKey))
				.map(Map.Entry::getKey).toList();
	}

	/**
	 * The vanilla pipeline as it draws while a pack is active: chunk meshes are EMRION_BLOCK and world/hand ENTITY draws are
	 * EMRION_ENTITY and world text GLYPH (agent g2's formats), so our copy reads those vertex buffers.
	 */
	static FrontendRenderPipeline extended(FrontendRenderPipeline p) {
		List<VertexFormat> formats = new ArrayList<>(p.vertexFormats());
		if (formats.isEmpty() || formats.getFirst() == null) return p;
		VertexFormat f = formats.getFirst();
		if (f == DefaultVertexFormat.BLOCK && uniformOrder(p).contains("TerrainUniform")) formats.set(0, EmrionVertexFormats.terrain());
		else if (f == DefaultVertexFormat.ENTITY) formats.set(0, EmrionVertexFormats.entity());
		else if (f == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR) formats.set(0, EmrionVertexFormats.GLYPH);
		else return p;
		return new FrontendRenderPipeline(p.name(), p.backendRenderPipeline(), formats, p.uniformIndices(), p.uniforms(), p.colorTargetStates(),
				p.wantsDepthTexture(), p.pushConstantSize());
	}

	private static boolean hasUv0(FrontendRenderPipeline p) {
		return !p.vertexFormats().isEmpty() && p.vertexFormats().getFirst() != null
				&& p.vertexFormats().getFirst().getElements().stream().anyMatch(e -> e.name().equals("UV0"));
	}

	private static List<String> uniformOrder(FrontendRenderPipeline vanilla) {
		return ((VulkanRenderPipeline) vanilla.backendRenderPipeline()).uniforms().stream().map(BindGroupLayout.UniformDescription::name).toList();
	}

	/** Samplers a pack stage may read although the vanilla pipeline does not push them; we push them (GeometryPipelines.EXTRA_*). */
	static final List<String> EXTRA_SAMPLERS = List.of("Sampler0", "Sampler1", "Sampler2", "EmrionNormals", "EmrionSpecular");

	/** Set-0 binding order: the vanilla list, then every extra sampler it lacks. */
	private static List<String> set0Order(FrontendRenderPipeline vanilla) {
		List<String> order = new ArrayList<>(uniformOrder(vanilla));
		for (String x : EXTRA_SAMPLERS) if (!order.contains(x)) order.add(x);
		return order;
	}

	/** The tessellation control, tessellation evaluation and geometry stages, in pipeline order (null: absent). */
	static Manifest.VertexStage[] intermediates(Manifest.Stages s) {
		return new Manifest.VertexStage[] {s.tessControl(), s.tessEval(), s.geometry()};
	}

	private static final int[] INTERMEDIATE_BITS = {VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT, VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT,
			VK_SHADER_STAGE_GEOMETRY_BIT};

	/**
	 * null if MC's primitives can feed the program's stages: with tessellation, MC's triangle lists become 3-vertex patches
	 * (Iris turns GL_TRIANGLES into GL_PATCHES and leaves the patch size at GL's default 3), other topologies are a GL error;
	 * a geometry shader must accept the drawn primitive. Vulkan also needs both tessellation stages (GL allows a lone .tes).
	 */
	private static String primitiveMisfit(Path cacheDir, Map<String, byte[]> spv, Manifest.Program p, String variant, RenderPipeline vanilla) {
		Manifest.Stages s = p.stages();
		int topology = VulkanConst.toVk(vanilla.getPrimitiveTopology());
		if (s.tessControl() != null || s.tessEval() != null) {
			if (s.tessControl() == null || s.tessEval() == null) return "Vulkan needs both a .tcs and a .tes";
			return topology == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST ? null : "tessellation draws only triangle lists, not topology " + topology;
		}
		if (s.geometry() == null) return null;
		int want = switch (topology) {
			case VK_PRIMITIVE_TOPOLOGY_POINT_LIST -> OutputPatcher.INPUT_POINTS;
			case VK_PRIMITIVE_TOPOLOGY_LINE_LIST, VK_PRIMITIVE_TOPOLOGY_LINE_STRIP -> OutputPatcher.INPUT_LINES;
			default -> OutputPatcher.INPUT_TRIANGLES;
		};
		int got = OutputPatcher.geometryInput(read(cacheDir, spv, s.geometry().variants().get(variant)));
		return got == want ? null : "geometry shader input primitive (mode " + got + ") does not match topology " + topology;
	}

	/** null if every set-0 resource the program's stages use is pushed by the vanilla pipeline, else what is missing. */
	private static String set0Misfit(Path cacheDir, Map<String, byte[]> spv, Manifest.Program p, String variant, FrontendRenderPipeline vanilla) {
		try {
			Set0Patcher.patch(read(cacheDir, spv, p.stages().vertex().variants().get(variant)), VertexLayouts.SET0_CANONICAL, set0Order(vanilla));
			for (var mid : intermediates(p.stages()))
				if (mid != null) Set0Patcher.patch(read(cacheDir, spv, mid.variants().get(variant)), VertexLayouts.SET0_CANONICAL, set0Order(vanilla));
			Set0Patcher.patch(read(cacheDir, spv, p.stages().fragment().spv()), VertexLayouts.SET0_CANONICAL, set0Order(vanilla));
			return null;
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	/** A private copy of the SPIR-V file (patching works in place). */
	private static byte[] read(Path cacheDir, Map<String, byte[]> cache, String path) {
		return cache.computeIfAbsent(path, x -> {
			try {
				return Files.readAllBytes(cacheDir.resolve(x));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}).clone();
	}

	private static final java.util.Set<String> WARNED = ConcurrentHashMap.newKeySet();

	/**
	 * Vertex inputs the vanilla format does not feed (until the extended vertex formats land): logged once and fed a constant
	 * from {@link GeometryPipelines#DEFAULTS_BINDING} (stride 0), so they read defined values instead of garbage. Returns
	 * whether any default is used.
	 */
	private static boolean defaultMissingInputs(Key k, byte[] vs, FrontendRenderPipeline vanilla, dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder b) {
		java.util.Set<Integer> fed = new java.util.HashSet<>();
		for (VertexFormat f : vanilla.vertexFormats()) {
			if (f == null) continue;
			for (VertexFormatElement e : f.getElements()) if (VertexLayouts.INPUT_LOCATIONS.contains(e.name())) fed.add(VertexLayouts.location(e.name()));
		}
		List<String> missing = OutputPatcher.inputLocations(vs).stream().filter(l -> !fed.contains(l)).sorted()
				.map(l -> l < VertexLayouts.INPUT_LOCATIONS.size() ? VertexLayouts.INPUT_LOCATIONS.get(l) : "location " + l).toList();
		if (!missing.isEmpty() && WARNED.add(k.program() + "/" + k.variant() + missing))
			EmrionShadersClient.LOG.warn("{}[{}] reads vertex inputs {} that {} does not provide; they get constant defaults", k.program(), k.variant(),
					missing, vanilla.vertexFormats().getFirst());
		boolean any = false;
		for (String name : missing) {
			int[] d = GeometryPipelines.DEFAULTS.get(name);
			if (d == null) continue; // no default known: stays undefined
			if (!any) b.vertexBinding(GeometryPipelines.DEFAULTS_BINDING, 0, 0);
			b.attribute(VertexLayouts.location(name), GeometryPipelines.DEFAULTS_BINDING, d[0], d[1]);
			any = true;
		}
		return any;
	}

	// ---- building ------------------------------------------------------------------------------------------------------

	private FrontendRenderPipeline build(VulkanContext ctx, Path cacheDir, Map<String, byte[]> spv, Manifest.Dimension dim, Key k,
			GbufferMapping.Entry entry, FrontendRenderPipeline vanilla, long set1Layout, int pushSize) {
		var bound = ((VulkanRenderPipeline) vanilla.backendRenderPipeline()).uniforms();
		List<String> order = set0Order(vanilla);
		int[] extras = new int[order.size() - bound.size()]; // extra binding i → EXTRA_SAMPLERS index
		for (int i = 0; i < extras.length; i++) extras[i] = EXTRA_SAMPLERS.indexOf(order.get(bound.size() + i));
		long set0Layout = GeometryPipelines.set0Layout(ctx, bound, extras.length);
		long layout = Descriptors.pipelineLayout(ctx, pushSize, set0Layout, set1Layout);
		List<String> attachments = k.shadow() ? shadowTargets : targets;
		List<ColorTargetState> states = k.shadow() ? shadowTargetStates : targetStates;
		int n = attachments.size();
		int[] formats = new int[n], masks = new int[n];
		Blend[] blends = new Blend[n];
		for (int i = 0; i < n; i++) formats[i] = VulkanConst.toVk(states.get(i).format());
		var b = GeometryPipelines.builderFor(k.vanilla(), vanilla);
		if (k.shadow()) b.cull(VK_CULL_MODE_NONE); // Iris disables backface culling in the shadow pass
		List<Long> modules = new ArrayList<>();
		float alphaRef = entry.alphaRef();
		boolean defaults = false;
		try {
			if (k.program() == null) {
				modules.add(ShaderModules.create(ctx, ShaderModules.resource("/assets/emrionshaders/spv/emrion_null.vert.spv")));
				b.stage(VK_SHADER_STAGE_VERTEX_BIT, modules.getFirst());
			} else {
				Manifest.Program p = dim.programs().get(k.program());
				byte[] vs = read(cacheDir, spv, p.stages().vertex().variants().get(k.variant()));
				byte[] fs = read(cacheDir, spv, p.stages().fragment().spv());
				Set0Patcher.patch(vs, VertexLayouts.SET0_CANONICAL, order);
				Set0Patcher.patch(fs, VertexLayouts.SET0_CANONICAL, order);
				int[] remap = new int[p.outputs().stream().mapToInt(Manifest.Output::location).max().orElse(-1) + 1];
				Blend vanillaBlend = GeometryPipelines.vanillaBlend(vanilla);
				for (var o : p.outputs()) {
					int i = attachments.indexOf(o.target());
					remap[o.location()] = i;
					masks[i] = k.hidden() ? 0 : 0xF;
					var mode = p.blend() == null ? null : p.blend().perTarget().getOrDefault(o.target(), p.blend().defaultMode());
					blends[i] = mode != null && mode.mode().equals("vanilla") ? vanillaBlend : PackPipelines.blend(mode);
				}
				OutputPatcher.patch(fs, remap);
				defaults = defaultMissingInputs(k, vs, vanilla, b);
				modules.add(ShaderModules.create(ctx, vs));
				b.stage(VK_SHADER_STAGE_VERTEX_BIT, modules.getLast());
				var mids = intermediates(p.stages());
				for (int i = 0; i < mids.length; i++) {
					if (mids[i] == null) continue;
					byte[] m = read(cacheDir, spv, mids[i].variants().get(k.variant()));
					Set0Patcher.patch(m, VertexLayouts.SET0_CANONICAL, order);
					modules.add(ShaderModules.create(ctx, m));
					b.stage(INTERMEDIATE_BITS[i], modules.getLast());
				}
				if (p.stages().tessControl() != null) b.patches(3); // primitiveMisfit: MC's triangle lists
				modules.add(ShaderModules.create(ctx, fs));
				b.stage(VK_SHADER_STAGE_FRAGMENT_BIT, modules.getLast());
				if (p.alphaTest() != null) alphaRef = p.alphaTest().ref();
			}
			if (k.hidden() || k.program() == null) b.depth(VK_FORMAT_D32_SFLOAT, true, false, VK_COMPARE_OP_LESS_OR_EQUAL);
			long pipeline;
			try {
				pipeline = b.colors(formats, blends).writeMasks(masks).layout(layout).build(ctx, ctx.pipelineCache().handle);
			} catch (RuntimeException failed) { // the wrapped pipeline would own these
				org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(ctx.vk, layout, null);
				org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(ctx.vk, set0Layout, null);
				throw failed;
			}
			boolean atlas = k.variant() != null && k.variant().startsWith("terrain") || k.vanilla() == net.minecraft.client.renderer.RenderPipelines.CRUMBLING;
			return GeometryPipelines.wrap(ctx, vanilla, pipeline, layout, set0Layout, states, new GeometryPipelines.Draw(alphaRef, atlas, bound.size(), extras, defaults));
		} finally {
			for (long m : modules) ShaderModules.destroy(ctx, m);
		}
	}
}
