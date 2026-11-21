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
import org.jspecify.annotations.Nullable;

/** Every gbuffers pipeline of a pack dimension, built at pack load (Iris's program choice per vanilla pipeline); nothing is created in a frame. */
public final class GbufferPipelines {
	/** SHADOW draws without backface culling (Iris ShadowRenderer); BLOCK_ENTITY maps entity programs to gbuffers_block, as Iris. */
	public enum Context { WORLD, HAND_SOLID, HAND_TRANSLUCENT, SHADOW, BLOCK_ENTITY, TERRAIN_PREPASS, TERRAIN_MAIN }

	/**
	 * Terrain depth prepass (the converter's "prepass" effect; SodiumWorldRendererMixin draws the opaque layers twice): set to
	 * TERRAIN_PREPASS (depth only, the shader stops after the alpha test) or TERRAIN_MAIN (shading without depth writes, so only the
	 * fragment the prepass kept passes the depth test) around Sodium's opaque terrain draws; null otherwise. Render thread only.
	 */
	public static @Nullable Context terrainMode;
	/**
	 * Push-constant alpha refs: TERRAIN_PREPASS returns after the pack's alpha test (above 1.5); TERRAIN_MAIN (below -1.5) skips
	 * every later discard (the converter's alpha test, Euphoria's snow fade), so it never leaves a texel the prepass kept uncoloured.
	 * The prepass ref carries the draw's own ref on top (2.5 cutout, 2 solid): Complementary r5.10 leaves the cutout test to it.
	 */
	private static final float PREPASS_REF = 2f, MAIN_REF = -2f;
	/** This dimension's conversion has the prepass patch: its Sodium opaque terrain has TERRAIN_PREPASS/MAIN pipelines. */
	public boolean prepass;

	/** The colortex targets written by any gbuffers program, by index. */
	public final List<String> targets;
	/** Attachment groups: one if it fits maxColorAttachments, else a pass switches groups when a pipeline of another is set. */
	public final List<List<String>> groups;
	private final List<List<ColorTargetState>> groupStates;
	private final Map<String, Integer> programGroup = new HashMap<>();
	/** Our pipeline → its attachment group; empty with a single group. */
	private final Map<com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline, Integer> pipelineGroup = new IdentityHashMap<>();
	/** The same for the shadow passes: shadowcolor targets written by any shadow program. */
	public final List<String> shadowTargets;
	public final List<ColorTargetState> shadowTargetStates;
	private final Map<RenderPipeline, FrontendRenderPipeline>[] byContext;
	private final List<FrontendRenderPipeline> all = new ArrayList<>();

	private record Key(String program, String variant, RenderPipeline vanilla, boolean hidden, boolean shadow, @Nullable Context terrain) {}

	/** The program and variant chosen for one vanilla pipeline in one context; {@code line} is its mapping-table entry (null: not logged). */
	private record Resolved(Key key, GbufferMapping.Entry entry, FrontendRenderPipeline compiled, String line) {}

	private final VulkanContext ctx;
	private final Path cacheDir;
	private final Manifest.Dimension dim;
	private final long set1Layout;
	private final int pushSize;
	private final Map<String, String> serving;
	private final Map<String, byte[]> spv = new ConcurrentHashMap<>();
	private final Map<Key, FrontendRenderPipeline> byKey = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public GbufferPipelines(VulkanContext ctx, Path cacheDir, Manifest manifest, Manifest.Dimension dim, long set1Layout,
			Map<RenderPipeline, FrontendRenderPipeline> vanilla, ExecutorService pool, PackPipelines owner) {
		targets = outputUnion(dim, "gbuffers", "colortex");
		shadowTargets = outputUnion(dim, "shadow", "shadowcolor");
		Map<String, GpuFormat> formats = new HashMap<>();
		for (var t : dim.targets()) if (targets.contains(t.name()) || shadowTargets.contains(t.name())) formats.put(t.name(), Formats.gpu(t.format()));
		Map<String, List<String>> outputs = new TreeMap<>();
		for (var e : dim.programs().entrySet())
			if (e.getValue().kind().equals("gbuffers")) outputs.put(e.getKey(), e.getValue().outputs().stream().map(Manifest.Output::target).toList());
		groups = partition(outputs, com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments(), programGroup);
		groupStates = groups.stream().map(g -> g.stream().map(t -> new ColorTargetState(Optional.empty(), formats.get(t), ColorTargetState.WRITE_ALL)).toList()).toList();
		shadowTargetStates = shadowTargets.stream().map(t -> new ColorTargetState(Optional.empty(), formats.get(t), ColorTargetState.WRITE_ALL)).toList();

		// Iris program name → the pack program that serves it (the converter resolved file fallbacks into fallbackFor).
		Map<String, String> serving = new HashMap<>();
		for (var e : dim.programs().entrySet()) {
			if (!e.getValue().kind().equals("gbuffers") && !e.getValue().kind().equals("shadow")) continue;
			serving.put(e.getKey(), e.getKey());
			for (String f : e.getValue().fallbackFor()) serving.putIfAbsent(f, e.getKey());
		}

		this.ctx = ctx;
		this.cacheDir = cacheDir;
		this.dim = dim;
		this.set1Layout = set1Layout;
		this.pushSize = manifest.uniforms().pushConstants().size();
		this.serving = serving;
		// programs of this dimension the converter's prepass effect patched (it may skip a dimension or a fallback program)
		String folder = manifest.dimensions().entrySet().stream().filter(x -> x.getValue() == dim).map(Map.Entry::getKey).findFirst().orElse("");
		List<String> patched = manifest.rt() == null ? List.of() : manifest.rt().patched().getOrDefault("prepass", List.of());
		Map<Key, CompletableFuture<FrontendRenderPipeline>> jobs = new HashMap<>();
		byContext = new Map[Context.values().length];
		Map<RenderPipeline, Key>[] keys = new Map[byContext.length];
		List<String> table = new ArrayList<>();
		for (Context c : Context.values()) {
			keys[c.ordinal()] = new IdentityHashMap<>();
			if (c == Context.SHADOW && !serving.containsKey("shadow")) continue; // no shadow program: no shadow pass in this dimension
			for (var raw : vanilla.entrySet()) {
				if (terrain(c) && !sodiumOpaque(raw.getKey())) continue;
				Resolved r = resolve(c, raw.getKey(), raw.getValue());
				if (terrain(c) && (r.key().program() == null || !patched.contains(folder + "/" + r.key().program() + ".fsh"))) continue;
				keys[c.ordinal()].put(raw.getKey(), r.key());
				jobs.computeIfAbsent(r.key(), kk -> {
					owner.counted(PackPipelines.TOTAL);
					return CompletableFuture.supplyAsync(() -> {
						owner.checkCancelled();
						var f = build(kk, r.entry(), r.compiled());
						owner.counted(PackPipelines.BUILT);
						return f;
					}, pool);
				});
				if (r.line() != null) table.add(r.line());
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
		jobs.forEach((k, j) -> {
			byKey.put(k, j.join());
			all.add(j.join());
			grouped(k, j.join());
		});
		// both opaque layers need both variants, else the prepass would draw with a colour-writing pipeline. Sodium's pipelines
		// are cached statically, so the ones known now are the ones it draws with; a late one gets the plain pipeline on both draws.
		prepass = byContext[Context.TERRAIN_PREPASS.ordinal()].size() == 2 && byContext[Context.TERRAIN_MAIN.ordinal()].size() == 2;
		table.sort(Comparator.naturalOrder());
		EmrionShadersClient.LOG.info("Gbuffers attachments {}; {} pipelines; mapping:\n  {}", groups.size() == 1 ? targets : "in groups " + groups + " " + programGroup,
				all.size(), String.join("\n  ", table));
	}

	public FrontendRenderPipeline get(Context c, RenderPipeline vanilla) {
		if (c == Context.WORLD && terrainMode != null) {
			FrontendRenderPipeline t = byContext[terrainMode.ordinal()].get(vanilla);
			if (t != null) return t;
			// Sodium recreated its pipelines (a changed option): the plain one draws this frame, no prepass from the next
			prepass = false;
		}
		FrontendRenderPipeline f = byContext[c.ordinal()].get(vanilla);
		// Sodium and other mods (particlerain, ...) create pipelines after pack load; a vanilla pipeline in a pack pass would crash (attachment count)
		if (f == null && !byContext[c.ordinal()].containsKey(vanilla)) {
			long start = System.nanoTime();
			f = buildLate(c, vanilla);
			dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.LATE_PIPELINE, start);
		}
		return f;
	}

	/** True while {@link #buildLate} lets MC compile the original pipeline (the swap must not answer then). */
	public static boolean compilingOriginal;

	/** Sodium and other mods create pipelines after pack load: built here on first draw (null and remembered if it fails). */
	private FrontendRenderPipeline buildLate(Context c, RenderPipeline p) {
		FrontendRenderPipeline f = null;
		try {
			if (c == Context.SHADOW && !serving.containsKey("shadow")) return null;
			compilingOriginal = true;
			FrontendRenderPipeline compiled;
			try {
				compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipelineNullable(p) instanceof FrontendRenderPipeline x ? x : null;
			} finally {
				compilingOriginal = false;
			}
			if (compiled == null) return null;
			Resolved r = resolve(c, p, compiled);
			f = byKey.get(r.key());
			if (f == null) {
				byKey.put(r.key(), f = build(r.key(), r.entry(), r.compiled()));
				all.add(f);
				grouped(r.key(), f);
			}
			EmrionShadersClient.LOG.warn("Gbuffers pipeline built in a frame (not known at pack load): {}", r.line() != null ? r.line() : c + " " + p.getLocation());
			return f;
		} catch (RuntimeException e) {
			EmrionShadersClient.LOG.error("Could not build the pack pipeline for {} in {}", p.getLocation(), c, e);
			return null;
		} finally {
			byContext[c.ordinal()].put(p, f);
		}
	}

	private static boolean terrain(Context c) {
		return c == Context.TERRAIN_PREPASS || c == Context.TERRAIN_MAIN;
	}

	/** Sodium's solid and cutout terrain pipelines (the ones SodiumWorldRendererMixin draws twice). */
	private static boolean sodiumOpaque(RenderPipeline p) {
		GbufferMapping.Entry e = GbufferMapping.get(p);
		return p.getLocation().getNamespace().equals("sodium") && e != null
				&& (e.program().equals("gbuffers_terrain_solid") || e.program().equals("gbuffers_terrain_cutout"));
	}

	private Resolved resolve(Context c, RenderPipeline p, FrontendRenderPipeline raw) {
		boolean shadow = c == Context.SHADOW;
		FrontendRenderPipeline compiled = extended(raw);
		GbufferMapping.Entry e = GbufferMapping.get(p);
		List<String> variants = variants(compiled);
		if (e == null) e = new GbufferMapping.Entry(hasUv0(compiled) ? "gbuffers_textured" : "gbuffers_basic", GbufferMapping.OFF, true);
		String world = e.program();
		if (shadow) e = GbufferMapping.shadow(e);
		if (c == Context.BLOCK_ENTITY) e = GbufferMapping.blockEntity(e);
		String iris = c == Context.WORLD || c == Context.BLOCK_ENTITY || shadow || terrain(c) ? e.program() : GbufferMapping.handProgram(e);
		boolean hidden = c == Context.HAND_SOLID && e.translucent() || c == Context.HAND_TRANSLUCENT && !e.translucent();
		String program = null, variant = null, why = variants.isEmpty() ? "no vertex variant for " + compiled.vertexFormats() : null;
		search:
		for (String candidate : GbufferMapping.chain(iris)) {
			String prog = serving.get(candidate);
			if (prog == null) continue;
			for (String var : variants) {
				if (!dim.programs().get(prog).stages().vertex().variants().containsKey(var)) continue;
				String misfit = set0Misfit(cacheDir, spv, dim.programs().get(prog), var, compiled);
				if (misfit == null) {
					// GL draws nothing when the primitive does not fit the program's stages (Iris has no fallback)
					why = primitiveMisfit(cacheDir, spv, dim.programs().get(prog), var, p);
					if (why == null) {
						program = prog;
						variant = var;
					} else why = prog + "[" + var + "]: " + why;
					break search;
				}
				if (why == null) why = prog + "[" + var + "]: " + misfit;
			}
			if (why == null) why = prog + " has none of " + variants;
		}
		String line = c == Context.WORLD || shadow || terrain(c) || !iris.equals(world)
				? c + " " + p.getLocation() + " -> " + iris + " => " + (program == null ? "(nothing drawn: " + why + ")" : program + "[" + variant + "]") : null;
		return new Resolved(new Key(program, variant, p, hidden, shadow, terrain(c) ? c : null), e, compiled, line);
	}

	/** The colour attachments a pass in context {@code c} opens with. */
	public List<String> targets(Context c) {
		return c == Context.SHADOW ? shadowTargets : groups.getFirst();
	}

	/** The attachment group of one of our gbuffers pipelines; -1 with a single group (or not ours). */
	public int group(com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline p) {
		Integer g = pipelineGroup.get(p);
		return g == null ? -1 : g;
	}

	/** Remembers the attachment group of a built gbuffers pipeline (render thread only). */
	private void grouped(Key k, FrontendRenderPipeline f) {
		if (groups.size() > 1 && !k.shadow()) pipelineGroup.put(f, group(k));
	}

	/** Programs that draw nothing (no pack program) use group 0. */
	private int group(Key k) {
		return k.program() == null ? 0 : programGroup.get(k.program());
	}

	/** Splits programs into attachment groups of at most {@code max} targets: one group if it fits, else first fit, most outputs first. */
	static List<List<String>> partition(Map<String, List<String>> outputs, int max, Map<String, Integer> groupOf) {
		Comparator<String> byIndex = Comparator.comparingInt(t -> Integer.parseInt(t.replace("colortex", "")));
		List<java.util.SortedSet<String>> groups = new ArrayList<>();
		groups.add(new java.util.TreeSet<>(byIndex));
		outputs.values().forEach(groups.getFirst()::addAll);
		if (groups.getFirst().size() <= max) {
			outputs.keySet().forEach(p -> groupOf.put(p, 0));
			return List.of(List.copyOf(groups.getFirst()));
		}
		groups.clear();
		List<String> order = new ArrayList<>(outputs.keySet());
		order.sort(Comparator.comparingInt((String p) -> -outputs.get(p).size()).thenComparing(Comparator.naturalOrder()));
		for (String p : order) {
			if (outputs.get(p).size() > max) throw new IllegalStateException(p + " writes " + outputs.get(p).size() + " targets; the device allows " + max);
			int g = 0;
			for (; g < groups.size(); g++) {
				var u = new java.util.HashSet<>(groups.get(g));
				u.addAll(outputs.get(p));
				if (u.size() <= max) break;
			}
			if (g == groups.size()) groups.add(new java.util.TreeSet<>(byIndex));
			groups.get(g).addAll(outputs.get(p));
			groupOf.put(p, g);
		}
		return groups.stream().map(List::copyOf).toList();
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

	/** Vertex variants a vanilla pipeline can feed, best first. */
	static List<String> variants(FrontendRenderPipeline vanilla) {
		List<VertexFormat> formats = vanilla.vertexFormats();
		if (set0Order(vanilla).contains("CloudFaces")) return List.of("clouds"); // MC 26.3 clouds: no vertex buffer, pulled from CloudFaces
		VertexFormat f = formats.isEmpty() ? null : formats.getFirst();
		if (f == null) return List.of();
		if (f.getElements().stream().anyMatch(e -> e.name().equals("a_LightAndData"))) return List.of("terrain_sodium");
		var names = f.getElements().stream().map(VertexFormatElement::name).collect(java.util.stream.Collectors.toSet());
		return VertexLayouts.VARIANTS.entrySet().stream()
				.filter(v -> !v.getKey().startsWith("terrain") && !v.getValue().isEmpty() && names.containsAll(v.getValue()))
				.filter(v -> !v.getKey().equals("block") || f == DefaultVertexFormat.BLOCK)
				.sorted(Comparator.comparingInt((Map.Entry<String, List<String>> v) -> -v.getValue().size()).thenComparing(Map.Entry::getKey))
				.map(Map.Entry::getKey).toList();
	}

	/** The vanilla pipeline with the extended formats a pack draws with (Sodium terrain, EMRION_ENTITY, GLYPH). */
	static FrontendRenderPipeline extended(FrontendRenderPipeline p) {
		List<VertexFormat> formats = new ArrayList<>(p.vertexFormats());
		if (formats.isEmpty() || formats.getFirst() == null) return p;
		VertexFormat f = formats.getFirst();
		if (f.getElements().stream().anyMatch(e -> e.name().equals("a_LightAndData"))) formats.set(0, EmrionVertexFormats.SODIUM_TERRAIN);
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

	/** Sodium's names for what the pack reads as Sampler0 (albedo atlas) and Sampler2 (lightmap). */
	private static final Map<String, String> SODIUM_ALIAS = Map.of("u_BlockTex", "Sampler0", "u_LightTex", "Sampler2");

	/** Set-0 binding order: the vanilla list (Sodium's samplers under our names), then every extra sampler it lacks. */
	private static List<String> set0Order(FrontendRenderPipeline vanilla) {
		List<String> order = new ArrayList<>(uniformOrder(vanilla).stream().map(n -> SODIUM_ALIAS.getOrDefault(n, n)).toList());
		for (String x : EXTRA_SAMPLERS) if (!order.contains(x)) order.add(x);
		return order;
	}

	/** The tessellation control, tessellation evaluation and geometry stages, in pipeline order (null: absent). */
	static Manifest.VertexStage[] intermediates(Manifest.Stages s) {
		return new Manifest.VertexStage[] {s.tessControl(), s.tessEval(), s.geometry()};
	}

	private static final int[] INTERMEDIATE_BITS = {VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT, VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT,
			VK_SHADER_STAGE_GEOMETRY_BIT};

	/** Null if MC's primitives fit the program: tessellation takes triangle lists as 3-vertex patches (as Iris), and Vulkan needs both tess stages. */
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

	/** Vertex inputs the vanilla format lacks get a stride-0 constant instead of garbage; returns whether any was needed. */
	private static boolean defaultMissingInputs(Key k, byte[] vs, FrontendRenderPipeline vanilla, dev.emrion.shaders.mod.vk.GraphicsPipelineBuilder b) {
		java.util.Set<Integer> fed = new java.util.HashSet<>();
		for (VertexFormat f : vanilla.vertexFormats()) {
			if (f == null) continue;
			for (VertexFormatElement e : f.getElements()) if (VertexLayouts.inputLocation(e.name()) >= 0) fed.add(VertexLayouts.inputLocation(e.name()));
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

	private FrontendRenderPipeline build(Key k, GbufferMapping.Entry entry, FrontendRenderPipeline vanilla) {
		var bound = ((VulkanRenderPipeline) vanilla.backendRenderPipeline()).uniforms();
		List<String> order = set0Order(vanilla);
		int[] extras = new int[order.size() - bound.size()]; // extra binding i → EXTRA_SAMPLERS index
		for (int i = 0; i < extras.length; i++) extras[i] = EXTRA_SAMPLERS.indexOf(order.get(bound.size() + i));
		long set0Layout = GeometryPipelines.set0Layout(ctx, bound, extras.length);
		boolean sodium = "terrain_sodium".equals(k.variant());
		long layout = Descriptors.pipelineLayout(ctx, sodium ? Math.max(pushSize, VertexLayouts.SODIUM_PUSH_OFFSET + VertexLayouts.SODIUM_PUSH_SIZE) : pushSize,
				set0Layout, set1Layout);
		List<String> attachments = k.shadow() ? shadowTargets : groups.get(group(k));
		List<ColorTargetState> states = k.shadow() ? shadowTargetStates : groupStates.get(group(k));
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
				modules.add(ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/emrion_null.vert.spv")));
				b.stage(VK_SHADER_STAGE_VERTEX_BIT, modules.getFirst());
			} else {
				Manifest.Program p = dim.programs().get(k.program());
				byte[] vs = read(cacheDir, spv, p.stages().vertex().variants().get(k.variant()));
				byte[] fs = read(cacheDir, spv, p.stages().fragment().spv());
				Set0Patcher.patch(vs, VertexLayouts.SET0_CANONICAL, order);
				Set0Patcher.patch(fs, VertexLayouts.SET0_CANONICAL, order);
				int[] remap = new int[p.outputs().stream().mapToInt(Manifest.Output::location).max().orElse(-1) + 1];
				Blend vanillaBlend = GeometryPipelines.vanillaBlend(vanilla);
				// vanilla depth-only draws (boat water mask) stay depth-only, as under Iris which keeps the GL colour mask
				boolean noColor = k.hidden() || k.terrain() == Context.TERRAIN_PREPASS || !vanilla.colorTargetStates().isEmpty() && vanilla.colorTargetStates().getFirst().writeMask() == 0;
				for (var o : p.outputs()) {
					int i = attachments.indexOf(o.target());
					remap[o.location()] = i;
					masks[i] = noColor ? 0 : 0xF;
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
				if (k.terrain() != null) alphaRef = k.terrain() == Context.TERRAIN_PREPASS ? PREPASS_REF + Math.max(alphaRef, 0f) : MAIN_REF;
				if (k.terrain() == Context.TERRAIN_MAIN) b.depthWrite(false);
			}
			if (k.hidden() || k.program() == null) b.depth(VK_FORMAT_D32_SFLOAT, true, false, VK_COMPARE_OP_LESS_OR_EQUAL);
			long pipeline;
			try {
				pipeline = b.colors(formats, blends).writeMasks(masks).layout(layout).label(k.program() + "[" + k.variant() + "] " + k.vanilla().getLocation() + (k.hidden() ? " hidden" : "") + (k.terrain() != null ? " " + k.terrain() : "")).build(ctx, ctx.pipelineCache().handle);
			} catch (RuntimeException failed) { // the wrapped pipeline would own these
				org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(ctx.vk, layout, null);
				org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(ctx.vk, set0Layout, null);
				throw failed;
			}
			boolean atlas = k.variant() != null && k.variant().startsWith("terrain") || k.vanilla() == net.minecraft.client.renderer.RenderPipelines.CRUMBLING;
			return GeometryPipelines.wrap(ctx, vanilla, pipeline, layout, set0Layout, states, new GeometryPipelines.Draw(alphaRef, atlas, bound.size(), extras, defaults, sodium));
		} finally {
			for (long m : modules) ShaderModules.destroy(ctx, m);
		}
	}
}
