package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.glsl.GlslLexer.Token;
import dev.emrion.shaders.converter.glsl.GlslParser.Declarator;
import dev.emrion.shaders.converter.glsl.GlslParser.Item;
import dev.emrion.shaders.converter.glsl.GlslParser.ItemKind;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.VertexLayouts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OptiFine/Iris compat GLSL → Vulkan GLSL 450 (ARCHITECTURE §4.1). Semantics follow Iris's Common/Vanilla/Sodium/
 * Composite/Compatibility transformers (iris-spec §8), reimplemented on a token stream plus {@link GlslParser}.
 * Stateless: every call re-parses its input.
 *
 * <p>Injected names all start with {@code emr_}: blocks {@code emr_dt} (DynamicTransforms), {@code emr_terrain}
 * (TerrainUniform), {@code emr_chunk} (ChunkSection), {@code emr_phase} (EmrionPhase), {@code emr_draw} (push constants
 * EmrionDraw), vertex inputs {@code emr_in_<Canonical>}, outputs {@code emr_FragData<i>}, renamed keywords
 * {@code emr_kw_<name>}, the pack's {@code main} becomes {@code emr_main} inside a generated {@code main} wrapper.
 */
public final class GlslTranspiler implements Transpiler {

	// ---- fixed tables --------------------------------------------------------------------------------------------

	/** GLSL types of the canonical vertex inputs (VertexLayouts.INPUT_LOCATIONS). */
	static final Map<String, String> CANON_TYPE = Map.ofEntries(
			Map.entry("Position", "vec3"), Map.entry("Color", "vec4"), Map.entry("UV0", "vec2"), Map.entry("UV1", "ivec2"),
			Map.entry("UV2", "ivec2"), Map.entry("Normal", "vec3"), Map.entry("mc_Entity", "ivec2"),
			Map.entry("mc_midTexCoord", "vec2"), Map.entry("at_tangent", "vec4"), Map.entry("at_midBlock", "vec4"),
			Map.entry("ChunkPosition", "ivec3"), Map.entry("ChunkVisibility", "float"), Map.entry("LineWidth", "float"),
			Map.entry("iris_Entity", "uvec3"));

	/** Per-draw ids that Iris reads from the iris_Entity attribute (EntityPatcher): name → component of emr_entityInfo. */
	static final Map<String, String> ENTITY_IDS = Map.of("entityId", "x", "blockEntityId", "y", "currentRenderedItemId", "z");

	/** Pack attribute name → canonical input (core-profile va* names, Iris attributes, MC element names). */
	static final Map<String, String> ATTRIBUTE_ALIAS = Map.ofEntries(
			Map.entry("vaPosition", "Position"), Map.entry("vaColor", "Color"), Map.entry("vaUV0", "UV0"),
			Map.entry("vaUV1", "UV1"), Map.entry("vaUV2", "UV2"), Map.entry("vaNormal", "Normal"),
			Map.entry("Position", "Position"), Map.entry("Color", "Color"), Map.entry("UV0", "UV0"), Map.entry("UV1", "UV1"),
			Map.entry("UV2", "UV2"), Map.entry("Normal", "Normal"),
			Map.entry("mc_Entity", "mc_Entity"), Map.entry("mc_midTexCoord", "mc_midTexCoord"),
			Map.entry("at_tangent", "at_tangent"), Map.entry("at_midBlock", "at_midBlock"));

	/** Types Iris injects for undeclared core-profile attributes (VanillaCoreTransformer.addIfNotExists). */
	static final Map<String, String> CORE_ATTRIBUTE_TYPE = Map.of("vaPosition", "vec3", "vaColor", "vec4", "vaUV0", "vec2",
			"vaUV1", "vec2", "vaUV2", "vec2", "vaNormal", "vec3");

	/** EmrionDraw push constants (FORMAT.md §6). Pack uniforms with these names always go here. */
	static final Manifest.Block PUSH = dev.emrion.shaders.converter.plan.Std140LayoutBuilder.PUSH;
	static final Manifest.Block PHASE = dev.emrion.shaders.converter.plan.Std140LayoutBuilder.PHASE;

	static boolean member(Manifest.Block b, String name) {
		for (Manifest.UniformMember m : b.members()) if (m.name().equals(name)) return true;
		return false;
	}

	static String declareMembers(Manifest.Block b) {
		StringBuilder sb = new StringBuilder();
		for (Manifest.UniformMember m : b.members())
			sb.append(" layout(offset = ").append(m.offset()).append(") ").append(m.type()).append(' ').append(m.name()).append(';');
		return sb.toString();
	}

	/** EmrionUniforms members the transpiler itself references (must be in the layout when used). */
	static final Map<String, String> INJECTED_UNIFORMS = Map.of("emr_CameraBlockPos", "ivec3", "emr_CameraOffset", "vec3",
			"emr_FogStart", "float", "emr_FogEnd", "float", "emr_FogDensity", "float", "emr_FogColor", "vec4");

	static final Set<String> ALBEDO = Set.of("tex", "texture", "gtexture", "gcolor", "u_MainSampler");

	static final String LIGHTMAP_MATRIX = "mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), "
			+ "vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0))";
	static final String COMPOSITE_PROJECTION = "mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))";

	/** Identifiers that are keywords (or built-in types) in GLSL 450 / Vulkan GLSL but legal names in GLSL 130. */
	static final Set<String> KEYWORDS_450 = Set.of("sample", "patch", "buffer", "shared", "coherent", "volatile", "restrict",
			"readonly", "writeonly", "subroutine", "precise", "resource", "filter", "common", "partition", "active",
			"atomic_uint", "sampler", "samplerShadow", "texture", "texture1D", "texture2D", "texture3D", "textureCube",
			"texture1DArray", "texture2DArray", "textureBuffer", "texture2DRect", "texture2DMS", "texture2DMSArray",
			"textureCubeArray", "subpassInput", "subpassInputMS", "image1D", "image2D", "image3D", "imageCube", "image2DRect",
			"image1DArray", "image2DArray", "imageBuffer", "image2DMS", "image2DMSArray", "imageCubeArray", "iimage2D",
			"iimage3D", "uimage2D", "uimage3D", "dvec2", "dvec3", "dvec4", "dmat2", "dmat3", "dmat4", "superp", "input",
			"output", "hvec2", "hvec3", "hvec4", "fvec2", "fvec3", "fvec4", "sampler3DRect", "namespace", "using",
			"samplerBuffer", "sampler2DRect", "sampler2DMS", "samplerCubeArray", "demote", "uniform_buffer", "noinline");

	/** Built-in functions by the GLSL version that added them: a pack written for an older version may define its own. */
	static final Map<String, Integer> LATER_BUILTINS = later(Map.of(
			120, "transpose outerProduct",
			130, "sinh cosh tanh asinh acosh atanh trunc round roundEven isnan isinf modf",
			140, "inverse",
			150, "determinant",
			330, "floatBitsToInt floatBitsToUint intBitsToFloat uintBitsToFloat",
			400, "fma frexp ldexp packUnorm2x16 packUnorm4x8 packSnorm4x8 unpackUnorm2x16 unpackUnorm4x8 unpackSnorm4x8 "
					+ "packDouble2x32 unpackDouble2x32 uaddCarry usubBorrow umulExtended imulExtended bitfieldExtract "
					+ "bitfieldInsert bitfieldReverse bitCount findLSB findMSB",
			420, "packHalf2x16 unpackHalf2x16 packSnorm2x16 unpackSnorm2x16"));

	static Map<String, Integer> later(Map<Integer, String> byVersion) {
		Map<String, Integer> m = new HashMap<>();
		byVersion.forEach((v, names) -> { for (String n : names.split(" ")) m.put(n, v); });
		return Map.copyOf(m);
	}

	static final Map<String, String> CALL_RENAMES = Map.ofEntries(
			Map.entry("texture2D", "texture"), Map.entry("texture3D", "texture"), Map.entry("texture1D", "texture"),
			Map.entry("textureCube", "texture"), Map.entry("texture2DLod", "textureLod"), Map.entry("texture3DLod", "textureLod"),
			Map.entry("textureCubeLod", "textureLod"), Map.entry("texture2DProj", "textureProj"),
			Map.entry("texture3DProj", "textureProj"), Map.entry("texture2DProjLod", "textureProjLod"),
			Map.entry("texture2DGrad", "textureGrad"), Map.entry("texture2DGradARB", "textureGrad"),
			Map.entry("texture3DGrad", "textureGrad"), Map.entry("texelFetch2D", "texelFetch"),
			Map.entry("texelFetch3D", "texelFetch"), Map.entry("textureSize2D", "textureSize"));

	static final Map<String, String> SHADOW_CALLS = Map.of("shadow2D", "texture", "shadow2DLod", "textureLod",
			"shadow2DProj", "textureProj", "shadow2DProjLod", "textureProjLod");

	/** Extensions whose functionality is core in GLSL 450 (or meaningless in Vulkan): dropped. */
	static final Set<String> DROP_EXTENSIONS = Set.of("GL_ARB_gpu_shader5", "GL_ARB_shading_language_packing",
			"GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_image_load_store", "GL_ARB_derivative_control",
			"GL_ARB_separate_shader_objects", "GL_ARB_explicit_attrib_location", "GL_ARB_explicit_uniform_location",
			"GL_ARB_texture_gather", "GL_ARB_compute_shader", "GL_ARB_enhanced_layouts", "GL_ARB_shading_language_420pack",
			"GL_ARB_uniform_buffer_object", "GL_ARB_texture_query_levels", "GL_ARB_texture_query_lod",
			"GL_ARB_shader_bit_encoding", "GL_ARB_arrays_of_arrays", "GL_ARB_shader_image_size", "GL_ARB_conservative_depth",
			"GL_ARB_sample_shading", "GL_ARB_texture_cube_map_array", "GL_ARB_shader_texture_lod", "GL_ARB_texture_rectangle",
			"GL_ARB_gpu_shader_fp64", "GL_ARB_texture_multisample", "GL_ARB_fragment_coord_conventions",
			"GL_ARB_shader_atomic_counters", "GL_ARB_shading_language_include", "GL_EXT_gpu_shader4", "GL_EXT_texture_array",
			"GL_ARB_texture_buffer_object", "GL_ARB_cull_distance", "GL_ARB_ES3_1_compatibility", "GL_ARB_shader_precision",
			"GL_ARB_vertex_attrib_64bit", "GL_ARB_tessellation_shader", "GL_ARB_geometry_shader4", "GL_ARB_shader_subroutine",
			"GL_ARB_compute_variable_group_size", "GL_ARB_shader_group_vote");

	// ---- parsed program --------------------------------------------------------------------------------------------

	/** One parsed stage with reachability (unused functions/globals dropped, like Iris's CompatibilityTransformer). */
	static final class Stage {
		final PreprocessedStage src;
		final StageType type;
		final List<Token> t;
		final List<Item> items;
		int version = 110;
		String profile;
		final Set<Item> kept = new HashSet<>();
		final Map<String, Integer> counts = new HashMap<>();
		/** Interface variables by name: in (consumer) and out (producer) varyings, with item and declarator. */
		final Map<String, Var> ins = new LinkedHashMap<>(), outs = new LinkedHashMap<>();
		final List<Var> attributes = new ArrayList<>(), fragOuts = new ArrayList<>(), uniforms = new ArrayList<>();
		final Set<String> globals = new HashSet<>();
		/** Functions the pack defines under a built-in name its #version did not have yet (Bliss's tanh in 120): renamed. */
		final Set<String> shadowed = new HashSet<>();

		Stage(PreprocessedStage src) {
			this.src = src;
			this.type = src.stage();
			this.t = GlslLexer.lex(src.text());
			this.items = GlslParser.parse(t);
			this.lines = new int[t.size()];
			int line = 1;
			for (int i = 0; i < t.size(); i++) {
				line += newlines(t.get(i).ws);
				lines[i] = line;
				line += newlines(t.get(i).text);
			}
			analyse();
		}

		final int[] lines;

		/** 1-based line of token i in the preprocessed text. */
		int line(int i) { return lines[i]; }

		static int newlines(String x) {
			int n = 0;
			for (int i = 0; i < x.length(); i++) if (x.charAt(i) == '\n') n++;
			return n;
		}

		boolean used(String name) { return counts.containsKey(name); }

		/** A scalar variable (global or local, not an interface or uniform) named {@code name} is declared. */
		boolean declaresVariable(String name) {
			for (int i = 1; i + 1 < t.size(); i++) {
				if (!t.get(i).is(name) || !Set.of("int", "uint", "float").contains(t.get(i - 1).text)) continue;
				if (i >= 2 && Set.of("uniform", "in", "out", "varying", "attribute", "flat").contains(t.get(i - 2).text)) continue;
				if (Set.of("=", ";", ",").contains(t.get(i + 1).text)) return true;
			}
			return false;
		}

		int count(String name) { return counts.getOrDefault(name, 0); }

		String type(Item it) { return GlslParser.text(t, it.typeStart, it.typeEnd); }

		private void analyse() {
			Map<String, List<Item>> defs = new HashMap<>();
			ArrayDeque<Item> work = new ArrayDeque<>();
			for (Item it : items) {
				switch (it.kind) {
					case DIRECTIVE -> {
						String d = t.get(it.start).text.trim();
						if (d.startsWith("#version")) {
							String[] p = d.substring(8).trim().split("\\s+");
							try { version = Integer.parseInt(p[0]); } catch (NumberFormatException ignored) {}
							profile = p.length > 1 ? p[1] : null;
						}
					}
					case FUNCTION, PROTOTYPE -> {
						defs.computeIfAbsent(it.name, k -> new ArrayList<>()).add(it);
						if (version < LATER_BUILTINS.getOrDefault(it.name, 0)) shadowed.add(it.name);
					}
					case DECL -> {
						String s = it.storage();
						boolean plain = s == null || s.equals("const") || s.equals("shared");
						if (plain && !it.decls.isEmpty()) {
							for (Declarator d : it.decls) {
								defs.computeIfAbsent(d.name(), k -> new ArrayList<>()).add(it);
								globals.add(d.name());
							}
						} else if (!"uniform".equals(s)) work.add(it);
						classify(it, s);
					}
					default -> work.add(it);
				}
			}
			for (Item f : defs.getOrDefault("main", List.of())) work.add(f);
			Set<Item> queued = new HashSet<>(work);
			while (!work.isEmpty()) {
				Item it = work.poll();
				kept.add(it);
				for (int i = it.start; i < it.end; i++) {
					Token k = t.get(i);
					if (!k.ident() || (i > 0 && t.get(i - 1).is("."))) continue;
					counts.merge(k.text, 1, Integer::sum);
					for (Item d : defs.getOrDefault(k.text, List.of())) if (queued.add(d)) work.add(d);
				}
			}
		}

		private void classify(Item it, String s) {
			if (s == null) return;
			String type = it.typeStart >= 0 ? type(it) : null;
			for (Declarator d : it.decls) {
				Var v = new Var(it, d, type);
				switch (s) {
					case "uniform" -> uniforms.add(v);
					case "attribute" -> attributes.add(v);
					case "varying" -> (this.type == StageType.VERTEX ? outs : ins).put(d.name(), v);
					case "in" -> {
						if (this.type == StageType.VERTEX) attributes.add(v);
						else if (this.type != StageType.COMPUTE) ins.put(d.name(), v);
					}
					case "out" -> {
						if (this.type == StageType.FRAGMENT) fragOuts.add(v);
						else outs.put(d.name(), v);
					}
					default -> {}
				}
			}
		}
	}

	record Var(Item item, Declarator d, String type) {
		String name() { return d.name(); }
	}

	/** All stages of a program plus the program-wide varying location map. */
	static final class Program {
		final String name;
		final ProgramGroup group;
		final List<Stage> stages = new ArrayList<>();
		final Map<String, Integer> locations = new LinkedHashMap<>();
		final boolean geometry, core, fogVarying, entityColorVarying, entityIdVarying;
		/** Entity id names the pack also declares as its own variables (Reimagined: int blockEntityId = mat;). */
		final Set<String> ownIds = new HashSet<>();
		final List<Diagnostic> diagnostics = new ArrayList<>();

		Program(List<PreprocessedStage> src) {
			List<PreprocessedStage> sorted = new ArrayList<>(src);
			sorted.sort(Comparator.comparing(PreprocessedStage::stage));
			for (PreprocessedStage p : sorted) stages.add(new Stage(p));
			name = src.isEmpty() ? "?" : src.getFirst().program();
			group = src.isEmpty() ? ProgramGroup.COMPOSITE : src.getFirst().group();
			geometry = group.isGeometry();
			Stage vs = stage(StageType.VERTEX);
			// Iris "core" path: core profile, >=150 without profile, or the lines program (TransformPatcher).
			core = vs != null && ("core".equals(vs.profile) || vs.version >= 150 && vs.profile == null || name.equals("gbuffers_line"));
			Stage fs = stage(StageType.FRAGMENT);
			boolean gs = stages.stream().anyMatch(s -> s.type.intermediate());
			fogVarying = fs != null && vs != null && !gs && fs.used("gl_FogFragCoord");
			entityColorVarying = geometry && fs != null && vs != null && !gs && fs.used("entityColor");
			// Iris EntityPatcher: ids come from iris_Entity via a flat varying (programs with tessellation or geometry stages
			// keep push constants: shortcut: Iris passes them through those stages, upgrade when a pack needs per-vertex ids there)
			boolean usesIds = false;
			for (Stage s : stages) for (String n : ENTITY_IDS.keySet()) if (s.declaresVariable(n)) ownIds.add(n);
			for (Stage s : stages) for (String n : ENTITY_IDS.keySet()) usesIds |= s.used(n);
			entityIdVarying = geometry && vs != null && !gs && usesIds;
			assignLocations();
		}

		Stage stage(StageType type) {
			for (Stage s : stages) if (s.type == type) return s;
			return null;
		}

		private void assignLocations() {
			Map<String, Integer> size = new LinkedHashMap<>();
			for (Stage s : stages) {
				for (Var v : s.outs.values()) size.merge(v.name(), slots(v, perVertex(s.type, v, false)), Math::max);
				for (Var v : s.ins.values()) size.merge(v.name(), slots(v, perVertex(s.type, v, true)), Math::max);
			}
			if (fogVarying) size.put("emr_FogFragCoord", 1);
			if (entityColorVarying) size.put("emr_entityColor", 1);
			if (entityIdVarying) size.put("emr_entityInfo", 1);
			int next = 0;
			for (var e : size.entrySet()) {
				locations.put(e.getKey(), next);
				next += e.getValue();
			}
		}

		/** Per-vertex arrays (outer dimension = vertex of the primitive/patch): GS inputs, TCS in/out and TES inputs, except patch ones. */
		static boolean perVertex(StageType type, Var v, boolean in) {
			if (v.item().has("patch")) return false;
			return type == StageType.TESS_CONTROL || in && (type == StageType.GEOMETRY || type == StageType.TESS_EVAL);
		}

		private int slots(Var v, boolean perVertexArray) {
			Stage s = null;
			for (Stage st : stages) if (st.items.contains(v.item())) s = st;
			int n = typeLocations(s.t, v.type());
			if (v.d().hasArray()) {
				List<Integer> dims = arrayDims(s.t, v.d().arrStart(), v.d().arrEnd());
				for (int i = perVertexArray ? 1 : 0; i < dims.size(); i++) n *= Math.max(1, dims.get(i));
			}
			return n;
		}
	}

	static final Set<String> MEMBER_QUALIFIERS = Set.of("lowp", "mediump", "highp", "flat", "smooth", "noperspective", "centroid");

	/** Interface locations of a type; a struct declared in the stage takes the sum of its members (Photon's fog_params). */
	static int typeLocations(List<Token> t, String type) {
		for (int i = 0; i + 2 < t.size(); i++) {
			if (!t.get(i).is("struct") || !t.get(i + 1).is(type) || !t.get(i + 2).is("{")) continue;
			int close = GlslParser.match(t, i + 2), n = 0;
			for (int j = i + 3; j < close; j++) {
				while (MEMBER_QUALIFIERS.contains(t.get(j).text)) j++;
				int per = typeLocations(t, t.get(j).text);
				while (j < close && !t.get(j).is(";")) {
					int name = ++j, end = name + 1;
					while (end < close && t.get(end).is("[")) end = GlslParser.match(t, end) + 1;
					int m = per;
					for (int d : arrayDims(t, name + 1, end)) m *= Math.max(1, d);
					n += m;
					j = end;
				}
			}
			return n;
		}
		return GlslTypes.locations(type);
	}

	/** Array dimensions in [from, to); unsized or non-literal dimensions are 0. */
	static List<Integer> arrayDims(List<Token> t, int from, int to) {
		List<Integer> dims = new ArrayList<>();
		for (int i = from; i < to; i++) {
			if (!t.get(i).is("[")) continue;
			int close = GlslParser.match(t, i);
			int v = 0;
			if (close == i + 2) {
				try { v = Integer.decode(t.get(i + 1).text.replaceAll("[uU]$", "")); } catch (NumberFormatException ignored) {}
			}
			dims.add(v);
			i = close;
		}
		return dims;
	}

	// ---- analysis --------------------------------------------------------------------------------------------------

	@Override
	public ProgramAnalysis analyze(List<PreprocessedStage> stages) {
		Program p = new Program(stages);
		Map<String, UniformDecl> uniforms = new LinkedHashMap<>();
		Map<String, SamplerDecl> samplers = new LinkedHashMap<>();
		Map<String, ImageDecl> images = new LinkedHashMap<>();
		Map<String, BufferDecl> buffers = new LinkedHashMap<>();
		Set<String> set0 = new LinkedHashSet<>();
		Set<String> inputs = new LinkedHashSet<>();
		Set<Integer> outputs = new java.util.TreeSet<>();
		Set<String> builtinNames = builtinNames(p);
		for (Stage s : p.stages) {
			for (Var u : s.uniforms) {
				String name = u.name();
				if (!s.used(name) || builtinNames.contains(name)) continue;
				String type = u.type();
				if (GlslTypes.isSampler(type)) {
					String slot = set0Sampler(p, name);
					if (slot != null) set0.add(slot);
					else samplers.putIfAbsent(name, new SamplerDecl(name, type));
				} else if (GlslTypes.isImage(type)) {
					images.putIfAbsent(name, new ImageDecl(name, type, imageFormat(u.item()), access(u.item())));
				} else if (!GlslTypes.isOpaque(type)) {
					int arr = u.d().hasArray() ? arrayDims(s.t, u.d().arrStart(), u.d().arrEnd()).getFirst() : 0;
					String init = u.d().hasInit() ? GlslParser.text(s.t, u.d().initStart(), u.d().initEnd()) : null;
					uniforms.putIfAbsent(name, new UniformDecl(name, type, arr, init));
				}
			}
			for (Item it : s.items) {
				if (it.kind == ItemKind.BLOCK && "buffer".equals(it.storage())) {
					buffers.putIfAbsent(it.name, new BufferDecl(it.name, layoutInt(it, "binding", 0), it.has("readonly")));
				}
			}
			boolean usesFog = s.used("gl_Fog");
			if (usesFog) for (String f : List.of("emr_FogColor", "emr_FogDensity", "emr_FogStart", "emr_FogEnd"))
				uniforms.putIfAbsent(f, new UniformDecl(f, INJECTED_UNIFORMS.get(f), 0, null));
			if (s.type == StageType.FRAGMENT) {
				for (String id : s.counts.keySet()) if (id.equals("gl_FragColor")) outputs.add(0);
				for (int i = 0; i + 3 < s.t.size(); i++) {
					if (s.t.get(i).is("gl_FragData") && s.t.get(i + 1).is("[") && s.t.get(i + 3).is("]")) {
						try { outputs.add(Integer.decode(s.t.get(i + 2).text)); } catch (NumberFormatException ignored) {}
					}
				}
				for (int i = 0; i < s.fragOuts.size(); i++) if (s.used(s.fragOuts.get(i).name())) outputs.add(outputIndex(s.fragOuts, i));
			}
			if (p.geometry && s.type == StageType.VERTEX) {
				boolean position = s.used("gl_Vertex") || s.used("ftransform") || s.used("vaPosition") || s.used("chunkOffset");
				if (position) {
					uniforms.putIfAbsent("emr_CameraBlockPos", new UniformDecl("emr_CameraBlockPos", "ivec3", 0, null));
					uniforms.putIfAbsent("emr_CameraOffset", new UniformDecl("emr_CameraOffset", "vec3", 0, null));
					inputs.add("Position");
					inputs.add("ChunkPosition");
				}
				if (s.used("gl_Color") || s.used("vaColor")) inputs.add("Color");
				if (s.used("gl_MultiTexCoord0") || s.used("vaUV0")) inputs.add("UV0");
				if (s.used("gl_MultiTexCoord1") || s.used("gl_MultiTexCoord2") || s.used("vaUV2")) inputs.add("UV2");
				if (s.used("gl_Normal") || s.used("vaNormal")) inputs.add("Normal");
				if (s.used("gl_MultiTexCoord3")) inputs.add("mc_midTexCoord");
				if (s.used("mc_chunkFade")) inputs.add("ChunkVisibility");
				if (p.entityColorVarying || s.used("vaUV1")) inputs.add("UV1");
				if (p.entityIdVarying) inputs.add("iris_Entity");
				for (Var a : s.attributes) {
					String c = ATTRIBUTE_ALIAS.get(a.name());
					if (c != null && s.used(a.name())) inputs.add(c);
				}
				boolean transforms = position || s.used("gl_ModelViewMatrix") || s.used("gl_Color") || s.used("gl_TextureMatrix")
						|| s.used("gl_NormalMatrix") || s.used("modelViewMatrix") || s.used("vaColor") || p.entityColorVarying;
				if (transforms) set0.addAll(List.of("DynamicTransforms", "TerrainUniform", "ChunkSection"));
				if (p.entityColorVarying) set0.add("Sampler1");
			}
			if (p.geometry && s.type.intermediate()) {
				for (String n : List.of("gl_ModelViewMatrix", "gl_ModelViewMatrixInverse", "gl_ModelViewProjectionMatrix", "gl_NormalMatrix",
						"gl_TextureMatrix", "gl_Color", "modelViewMatrix", "modelViewMatrixInverse", "normalMatrix", "textureMatrix", "chunkOffset"))
					if (s.used(n)) set0.addAll(List.of("DynamicTransforms", "TerrainUniform", "ChunkSection"));
			}
		}
		List<String> orderedSet0 = new ArrayList<>();
		for (String n : VertexLayouts.SET0_CANONICAL) if (set0.contains(n)) orderedSet0.add(n);
		List<String> orderedInputs = new ArrayList<>();
		for (String n : VertexLayouts.INPUT_LOCATIONS) if (inputs.contains(n)) orderedInputs.add(n);
		return new ProgramAnalysis(p.name, p.group, List.copyOf(stages), List.copyOf(uniforms.values()), List.copyOf(samplers.values()),
				List.copyOf(images.values()), List.copyOf(buffers.values()), orderedSet0, orderedInputs, List.copyOf(outputs),
				List.copyOf(p.diagnostics));
	}

	/** Uniform names that are rewritten to built-in expressions and therefore never become block members. */
	static Set<String> builtinNames(Program p) {
		Set<String> s = new HashSet<>(Set.of("modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix",
				"projectionMatrixInverse", "textureMatrix", "normalMatrix", "chunkOffset", "alphaTestRef", "iris_currentAlphaTest"));
		if (p.entityColorVarying) s.add("entityColor");
		return s;
	}

	static String set0Sampler(Program p, String name) {
		if (!p.geometry) return null;
		if (ALBEDO.contains(name)) return "Sampler0";
		if (name.equals("lightmap")) return "Sampler2";
		if (name.equals("iris_overlay")) return "Sampler1";
		// Iris binds the PBR maps of the albedo texture per draw (PBRTextureManager), so they follow Sampler0
		if (name.equals("normals")) return "EmrionNormals";
		if (name.equals("specular")) return "EmrionSpecular";
		return null;
	}

	static String imageFormat(Item it) {
		if (it.layout == null) return null;
		for (String[] kv : it.layout) if (kv[1] == null && !Set.of("std140", "std430", "shared", "packed").contains(kv[0])) return kv[0];
		return null;
	}

	static String access(Item it) {
		if (it.has("readonly")) return "readonly";
		if (it.has("writeonly")) return "writeonly";
		return "readwrite";
	}

	static int layoutInt(Item it, String key, int def) {
		if (it.layout == null) return def;
		for (String[] kv : it.layout) {
			if (kv[0].equals(key) && kv[1] != null) {
				try { return Integer.decode(kv[1].trim()); } catch (NumberFormatException e) { return def; }
			}
		}
		return def;
	}

	/** Fragment output index of a modern out: explicit location, outColorN name, else declaration order. */
	static int outputIndex(List<Var> outs, int ordinal) {
		Var v = outs.get(ordinal);
		int loc = layoutInt(v.item(), "location", -1);
		if (loc >= 0) return loc;
		if (v.name().matches("outColor[0-7]")) return v.name().charAt(8) - '0';
		return ordinal;
	}

	// ---- emission --------------------------------------------------------------------------------------------------

	@Override
	public EmitResult emit(ProgramAnalysis analysis, EmitContext context, String vertexVariant) {
		Program p = new Program(analysis.stages());
		List<Diagnostic> diags = new ArrayList<>(p.diagnostics);
		Map<StageType, String> out = new LinkedHashMap<>();
		for (Stage s : p.stages) {
			StageEmitter e = new StageEmitter(this, p, s, context, s.type == StageType.VERTEX || s.type.intermediate() ? vertexVariant : null);
			out.put(s.type, e.emit());
			for (Diagnostic d : e.diagnostics) diags.add(d.in(p.name, s.type.extension));
		}
		return new EmitResult(out, diags);
	}

	/** Emits one stage. Variant is only used for the vertex stage (other stages must be variant-independent). */
	static final class StageEmitter {
		final Program p;
		final Stage s;
		final List<Token> t;
		final EmitContext ctx;
		final String variant;
		final List<String> variantInputs;
		final boolean terrain, fullscreen, lastVertexStage;
		final Map<String, String> repl = new HashMap<>();
		final Map<String, String> callRepl = new HashMap<>();
		final List<String> prologue = new ArrayList<>(), epilogue = new ArrayList<>();
		final List<String> extraDecls = new ArrayList<>();
		final Map<String, String> providedUniforms = new LinkedHashMap<>(); // name → declared type+array suffix
		final Set<Integer> fragData = new java.util.TreeSet<>();
		final List<Diagnostic> diagnostics = new ArrayList<>();

		StageEmitter(GlslTranspiler owner, Program p, Stage s, EmitContext ctx, String variant) {
			this.p = p;
			this.s = s;
			this.t = s.t;
			this.ctx = ctx;
			this.variant = variant;
			this.variantInputs = variant == null ? List.of() : VertexLayouts.VARIANTS.getOrDefault(variant, List.of());
			if (variant != null && !VertexLayouts.VARIANTS.containsKey(variant)) {
				diagnostics.add(Diagnostic.error(s.src.sourcePath(), 0, "unknown vertex variant " + variant));
			}
			this.terrain = variant != null && variant.startsWith("terrain");
			this.fullscreen = !p.geometry;
			// the depth fix (GL clip z → Vulkan) belongs in the last stage before the rasterizer
			boolean gs = p.stage(StageType.GEOMETRY) != null, tes = p.stage(StageType.TESS_EVAL) != null;
			this.lastVertexStage = s.type == StageType.GEOMETRY || s.type == StageType.TESS_EVAL && !gs || s.type == StageType.VERTEX && !gs && !tes;
		}

		boolean has(String input) { return variantInputs.contains(input); }

		String in(String canon) { return "emr_in_" + canon; }

		String kw(String name) {
			// shared has always been the compute storage qualifier; only older stages may use it as a name
			return KEYWORDS_450.contains(name) && !(name.equals("shared") && s.type == StageType.COMPUTE)
					&& !(name.equals("patch") && (s.type == StageType.TESS_CONTROL || s.type == StageType.TESS_EVAL)) || s.shadowed.contains(name)
					? "emr_kw_" + name : name;
		}

		String emit() {
			buildReplacements();
			StringBuilder body = new StringBuilder();
			StringBuilder item = new StringBuilder();
			for (Item it : s.items) {
				item.setLength(0);
				emitItem(it, item);
				// strip leading blank lines, then resync line numbers to the preprocessed source (#line N = next line is N)
				int start = 0, lastNl = -1;
				while (start < item.length() && Character.isWhitespace(item.charAt(start))) {
					if (item.charAt(start) == '\n') lastNl = start;
					start++;
				}
				if (start == item.length()) continue;
				body.append("\n#line ").append(s.line(it.start)).append('\n').append(item, lastNl + 1, item.length());
			}
			if (s.type != StageType.COMPUTE) body.append(wrapper());
			return header(body.toString()) + body;
		}

		// -- replacement tables --

		void buildReplacements() {
			StageType st = s.type;
			boolean vs = st == StageType.VERTEX;
			for (var e : CALL_RENAMES.entrySet()) callRepl.put(e.getKey(), e.getValue());
			if (st != StageType.COMPUTE) callRepl.put("main", "emr_main");
			repl.put("gl_VertexID", "gl_VertexIndex");
			repl.put("gl_InstanceID", "gl_InstanceIndex");
			repl.put("gl_Fog", "emr_FogParameters(emr_FogColor, emr_FogDensity, emr_FogStart, emr_FogEnd, 1.0 / (emr_FogEnd - emr_FogStart))");
			if (st == StageType.FRAGMENT) {
				repl.put("gl_Color", "vec4(0.0)"); // Iris: irs_Color input without producer → zero
				repl.put("gl_TexCoord", "vec4[3](vec4(0.0), vec4(0.0), vec4(0.0))");
				repl.put("gl_FogFragCoord", p.fogVarying ? "emr_FogFragCoord" : "0.0");
			}
			if (vs) {
				repl.put("gl_FrontColor", "emr_FrontColor");
				repl.put("gl_FogFragCoord", "emr_FogFragCoord");
				if (!p.fogVarying && s.used("gl_FogFragCoord")) extraDecls.add("float emr_FogFragCoord;");
				if (p.fogVarying) prologue.add("emr_FogFragCoord = 0.0;");
				callRepl.put("ftransform", "emr_ftransform");
			}
			if (p.geometry) geometryTable(vs);
			else compositeTable(vs);
			// Iris VanillaTransformer: gl_Color outside the vertex stage is the colour modulator
			if (p.geometry && st.intermediate()) repl.put("gl_Color", terrain ? "vec4(1.0)" : "emr_dt.ColorModulator");
			if (p.entityIdVarying && (st == StageType.FRAGMENT || vs)) {
				for (var e : ENTITY_IDS.entrySet()) repl.put(e.getKey(), "emr_entityInfo." + e.getValue());
				if (vs) prologue.add("emr_entityInfo = " + (has("iris_Entity") ? "ivec3(" + in("iris_Entity") + ")"
						: "ivec3(emr_draw.entityId, emr_draw.blockEntityId, emr_draw.currentRenderedItemId)") + ";");
			}
			// pack-declared uniforms that map to push constants / phase block
			for (Var u : s.uniforms) {
				String n = u.name();
				if (repl.containsKey(n)) continue;
				if (member(PUSH, n)) repl.put(n, "emr_draw." + n);
				else if (member(PHASE, n)) repl.put(n, "emr_phase." + n);
			}
			// a textual replacement would also hit the pack's own declarator; a global copy lets GLSL scoping shadow it
			for (String n : p.ownIds) {
				String v = repl.remove(n);
				if (v == null || !s.used(n)) continue;
				extraDecls.add("int " + n + ";");
				prologue.add(n + " = " + v + ";");
			}
			if (p.entityColorVarying && (st == StageType.FRAGMENT || vs)) repl.put("entityColor", "emr_entityColor");
			if (p.entityColorVarying && vs) {
				if (has("UV1")) {
					prologue.add("vec4 emr_overlay = texelFetch(emr_Sampler1, " + in("UV1") + ", 0);");
					prologue.add("emr_entityColor = vec4(emr_overlay.rgb, 1.0 - emr_overlay.a);");
					prologue.add("emr_entityColor.rgb *= float(emr_entityColor.a != 0.0);");
				} else prologue.add("emr_entityColor = emr_draw.entityColor;");
			}
			// vertex attributes declared by the pack
			for (Var a : s.attributes) repl.put(a.name(), "(" + attribute(a.name(), a.type()) + ")");
			// core-profile names used without declaration (gbuffers_line in the target)
			if (vs) {
				for (var e : CORE_ATTRIBUTE_TYPE.entrySet()) {
					String n = e.getKey();
					if (!repl.containsKey(n) && s.used(n) && !declaredAnywhere(n)) repl.put(n, "(" + attribute(n, e.getValue()) + ")");
				}
			}
			if (s.used("mc_chunkFade") && !declaredAnywhere("mc_chunkFade")) {
				String fade = "-1.0";
				if (vs && terrain && p.group == ProgramGroup.GBUFFERS)
					fade = has("ChunkVisibility") ? in("ChunkVisibility") : "emr_chunk.ChunkVisibility";
				repl.put("mc_chunkFade", fade);
			}
			if (lastVertexStage && st == StageType.GEOMETRY) callRepl.put("EmitVertex", "emr_EmitVertex");
		}

		/** True if the name occurs in a declaration position (global or local), so it must not be replaced. */
		boolean declaredAnywhere(String name) {
			if (s.globals.contains(name)) return true;
			for (int i = 1; i < t.size(); i++) {
				if (!t.get(i).is(name)) continue;
				Token prev = t.get(i - 1);
				if (prev.ident() && !Set.of("return", "else", "case").contains(prev.text) && !prev.is("uniform")) {
					// "uniform T name" / "in T name" are handled declarations; anything else is a variable declaration
					return !(i >= 2 && (t.get(i - 2).is("uniform") || t.get(i - 2).is("in") || t.get(i - 2).is("attribute")));
				}
			}
			return false;
		}

		String chunkPosition() { return has("ChunkPosition") ? in("ChunkPosition") : "emr_chunk.ChunkPosition"; }

		String chunkOffset() { return "(vec3(" + chunkPosition() + " - emr_CameraBlockPos) + emr_CameraOffset)"; }

		void geometryTable(boolean vs) {
			String mvRaw = terrain ? "emr_terrain.ModelViewMat" : "emr_dt.ModelViewMat";
			String mv = terrain || p.core ? mvRaw : "(emr_dt.ModelViewMat * emr_translate(emr_dt.ModelOffset))";
			String proj = "emr_phase.emr_ProjMat";
			repl.put("gl_ModelViewMatrix", mv);
			repl.put("gl_ProjectionMatrix", proj);
			repl.put("gl_ModelViewProjectionMatrix", "(" + proj + " * " + mv + ")");
			repl.put("gl_ModelViewMatrixInverse", "inverse(" + mv + ")");
			repl.put("gl_ProjectionMatrixInverse", "emr_phase.emr_ProjMatInverse");
			String normal = "mat3(transpose(inverse(mat3(" + mvRaw + "))))";
			repl.put("gl_NormalMatrix", normal);
			repl.put("textureMatrix", terrain ? "mat4(1.0)" : "emr_dt.TextureMat");
			// core-profile uniforms (VanillaCoreTransformer), only if the pack does not declare its own variables
			Map<String, String> core = Map.of("modelViewMatrix", mvRaw, "modelViewMatrixInverse", "inverse(" + mvRaw + ")",
					"projectionMatrix", proj, "projectionMatrixInverse", "emr_phase.emr_ProjMatInverse", "normalMatrix", normal,
					"chunkOffset", terrain ? chunkOffset() : "emr_dt.ModelOffset", "alphaTestRef", "emr_draw.emr_alphaTestRef",
					"iris_currentAlphaTest", "emr_draw.emr_alphaTestRef");
			for (var e : core.entrySet()) if (!s.globals.contains(e.getKey()) && !declaredAnywhere(e.getKey())) repl.put(e.getKey(), e.getValue());
			if (!vs) return;
			String pos = has("Position") ? in("Position") : "vec3(0.0)";
			if (terrain) pos = "(" + pos + " + " + chunkOffset() + ")";
			repl.put("gl_Vertex", "vec4(" + pos + ", 1.0)");
			if (has("Color")) repl.put("gl_Color", terrain ? in("Color") : "(" + in("Color") + " * emr_dt.ColorModulator)");
			else repl.put("gl_Color", terrain ? "vec4(1.0)" : "emr_dt.ColorModulator");
			repl.put("gl_Normal", has("Normal") ? in("Normal") : "vec3(0.0, 0.0, 1.0)");
			repl.put("gl_MultiTexCoord0", has("UV0") ? "vec4(" + in("UV0") + ", 0.0, 1.0)" : "vec4(0.5, 0.5, 0.0, 1.0)");
			String light = has("UV2") ? "vec4(" + in("UV2") + ", 0.0, 1.0)" : "vec4(240.0, 240.0, 0.0, 1.0)";
			repl.put("gl_MultiTexCoord1", light);
			repl.put("gl_MultiTexCoord2", light);
			repl.put("gl_MultiTexCoord3", has("mc_midTexCoord") ? "vec4(" + in("mc_midTexCoord") + ", 0.0, 1.0)" : "vec4(0.0, 0.0, 0.0, 1.0)");
			for (int i = 4; i < 8; i++) repl.put("gl_MultiTexCoord" + i, "vec4(0.0, 0.0, 0.0, 1.0)");
		}

		void compositeTable(boolean vs) {
			repl.put("gl_Color", s.type == StageType.FRAGMENT ? "vec4(0.0)" : "vec4(1.0)");
			repl.put("gl_NormalMatrix", "mat3(1.0)");
			repl.put("gl_ModelViewMatrix", "mat4(1.0)");
			repl.put("gl_ModelViewMatrixInverse", "mat4(1.0)");
			repl.put("gl_ProjectionMatrix", COMPOSITE_PROJECTION);
			repl.put("gl_ProjectionMatrixInverse", "inverse(" + COMPOSITE_PROJECTION + ")");
			repl.put("gl_ModelViewProjectionMatrix", COMPOSITE_PROJECTION);
			if (!vs) return;
			repl.put("gl_Vertex", "vec4(emr_fsUV(), 0.0, 1.0)");
			repl.put("gl_MultiTexCoord0", "vec4(emr_fsUV(), 0.0, 1.0)");
			for (int i = 1; i < 8; i++) repl.put("gl_MultiTexCoord" + i, "vec4(0.0, 0.0, 0.0, 1.0)");
			repl.put("gl_Normal", "vec3(0.0, 0.0, 1.0)");
		}

		/** Expression of the pack-declared attribute {@code name} converted to its declared {@code type}. */
		String attribute(String name, String type) {
			String c = ATTRIBUTE_ALIAS.get(name);
			String generic = GlslTypes.convert("vec4(0.0, 0.0, 0.0, 1.0)", "vec4", type, true);
			if (fullscreen) {
				if ("Position".equals(c)) return GlslTypes.convert("vec3(emr_fsUV(), 0.0)", "vec3", type, true);
				if ("UV0".equals(c)) return GlslTypes.convert("emr_fsUV()", "vec2", type, true);
				return generic;
			}
			if (c == null) {
				diagnostics.add(Diagnostic.warning(s.src.sourcePath(), 0, "unknown vertex attribute " + name + ", using (0,0,0,1)"));
				return generic;
			}
			if (!has(c)) return generic;
			String expr = in(c);
			String canonType = CANON_TYPE.get(c);
			if (name.equals("vaColor") && !terrain) expr = "(" + expr + " * emr_dt.ColorModulator)";
			return GlslTypes.convert(expr, canonType, type, true);
		}

		// -- items --

		void emitItem(Item it, StringBuilder out) {
			switch (it.kind) {
				case DIRECTIVE, EMPTY -> {}
				case FUNCTION, PROTOTYPE -> { if (s.kept.contains(it)) rw(it.start, it.end, out); }
				case BLOCK -> emitBlock(it, out);
				case DECL -> emitDecl(it, out);
				default -> rw(it.start, it.end, out);
			}
		}

		void emitDecl(Item it, StringBuilder out) {
			String storage = it.storage();
			if (storage == null || storage.equals("const") || storage.equals("shared") || it.decls.isEmpty()) {
				if (it.decls.isEmpty() || s.kept.contains(it)) rw(it.start, it.end, out);
				return;
			}
			String type = s.type(it);
			for (Declarator d : it.decls) {
				String arr = d.hasArray() ? rws(d.arrStart(), d.arrEnd()) : "";
				switch (storage) {
					case "uniform" -> emitUniform(it, d, type, arr, out);
					case "attribute" -> {}
					case "in" -> {
						if (s.type == StageType.VERTEX) break;
						if (s.type == StageType.COMPUTE) { rw(it.start, it.end, out); return; }
						emitVarying(it, d, type, arr, "in", out);
					}
					case "varying" -> emitVarying(it, d, type, arr, s.type == StageType.VERTEX ? "out" : "in", out);
					case "out" -> {
						if (s.type == StageType.FRAGMENT) emitFragOut(it, d, type, arr, out);
						else emitVarying(it, d, type, arr, "out", out);
					}
					default -> { rw(it.start, it.end, out); return; }
				}
			}
		}

		void emitUniform(Item it, Declarator d, String type, String arr, StringBuilder out) {
			String name = d.name();
			if (!s.used(name) || repl.containsKey(name) || p.ownIds.contains(name)) return;
			if (GlslTypes.isOpaque(type)) {
				String slot = set0Sampler(p, name);
				String layout;
				if (slot != null) layout = "set = 0, binding = " + VertexLayouts.SET0_CANONICAL.indexOf(slot);
				else layout = "set = 1, binding = " + binding(name);
				String fmt = imageFormat(it);
				if (fmt != null && GlslTypes.isImage(type)) layout += ", " + fmt;
				StringBuilder q = new StringBuilder();
				for (String x : it.quals) if (Set.of("coherent", "volatile", "restrict", "readonly", "writeonly", "highp", "mediump", "lowp").contains(x)) q.append(x).append(' ');
				out.append("\nlayout(").append(layout).append(") uniform ").append(q).append(type).append(' ').append(kw(name)).append(arr).append(';');
				return;
			}
			Manifest.UniformMember m = ctx.uniformMembers() == null ? null : ctx.uniformMembers().get(name);
			if (m != null) {
				if (!m.type().equals(type)) {
					diagnostics.add(Diagnostic.warning(s.src.sourcePath(), 0, "uniform " + name + " declared as " + type + " but laid out as " + m.type()));
				}
				providedUniforms.put(name, type + " " + kw(name) + arr);
				return;
			}
			String init;
			if (d.hasInit()) init = rws(d.initStart(), d.initEnd());
			else if (d.hasArray()) {
				int n = arrayDims(t, d.arrStart(), d.arrEnd()).getFirst();
				if (n <= 0) { out.append('\n').append(type).append(' ').append(kw(name)).append(arr).append(';'); return; }
				init = type + "[" + n + "](" + String.join(", ", java.util.Collections.nCopies(n, GlslTypes.zero(type))) + ")";
			} else init = GlslTypes.zero(type);
			if (init == null) { out.append('\n').append(type).append(' ').append(kw(name)).append(arr).append(';'); return; }
			out.append("\nconst ").append(type).append(' ').append(kw(name)).append(arr).append(" = ").append(init).append(';');
		}

		int binding(String name) {
			Integer b = ctx.resourceBinding() == null ? null : ctx.resourceBinding().apply(name);
			if (b == null) {
				diagnostics.add(Diagnostic.error(s.src.sourcePath(), 0, "no set-1 binding for " + name));
				return 999;
			}
			return b;
		}

		void emitVarying(Item it, Declarator d, String type, String arr, String dir, StringBuilder out) {
			String name = d.name();
			Integer loc = p.locations.get(name);
			StringBuilder q = new StringBuilder();
			boolean flat = it.has("flat") || GlslTypes.isInteger(type);
			if (flat) q.append("flat ");
			for (String x : it.quals) if (Set.of("smooth", "noperspective", "centroid", "invariant", "sample", "patch").contains(x)) q.append(x).append(' ');
			if (dir.equals("in")) {
				Stage prev = previous();
				if (prev != null && !prev.outs.containsKey(name)) {
					// Iris: an input without producer reads zero
					if (Program.perVertex(s.type, new Var(it, d, type), true) && arr.equals("[]")) arr = "[32]"; // ≥ any primitive/patch size
					String zero = zeroOf(type, d, arr);
					out.append('\n').append(type).append(' ').append(kw(name)).append(arr).append(zero == null ? "" : " = " + zero).append(';');
					return;
				}
			} else {
				Stage next = next();
				Var consumer = next == null ? null : next.ins.get(name);
				if (consumer != null && next.type == StageType.FRAGMENT && !consumer.type().equals(type) && !d.hasArray()) {
					String ct = consumer.type();
					if (GlslTypes.components(ct) > 0 && GlslTypes.components(type) > 0) {
						// Iris CompatibilityTransformer: out takes the consumer's type, the old one becomes a global
						repl.put(name, "emr_mm_" + name);
						out.append('\n').append(type).append(" emr_mm_").append(name).append(';');
						epilogue.add(kw(name) + " = " + GlslTypes.convert("emr_mm_" + name, type, ct, false) + ";");
						if (GlslTypes.isInteger(ct) && !flat) q.insert(0, "flat ");
						type = ct;
					} else {
						diagnostics.add(Diagnostic.warning(s.src.sourcePath(), 0, "varying " + name + " type mismatch " + type + " vs " + ct));
					}
				} else if (consumer != null && s.count(name) <= 1 && s.type != StageType.TESS_CONTROL) { // TCS may only write its own vertex
					String zero = zeroOf(type, d);
					if (zero != null) prologue.add(kw(name) + " = " + zero + ";");
				}
			}
			out.append("\nlayout(location = ").append(loc).append(") ").append(q).append(dir).append(' ').append(type).append(' ')
					.append(kw(name)).append(arr).append(';');
		}

		String zeroOf(String type, Declarator d) {
			return zeroOf(type, d, null);
		}

		/** {@code arr}: the emitted array suffix when it differs from the declaration (sized unsized arrays), else null. */
		String zeroOf(String type, Declarator d, String arr) {
			if (!d.hasArray()) return GlslTypes.zero(type);
			List<Integer> dims = arr == null || !arr.matches("\\[\\d+]") ? arrayDims(t, d.arrStart(), d.arrEnd())
					: List.of(Integer.parseInt(arr.substring(1, arr.length() - 1)));
			if (dims.size() != 1 || dims.getFirst() <= 0 || GlslTypes.zero(type) == null) return null;
			return type + "[" + dims.getFirst() + "](" + String.join(", ", java.util.Collections.nCopies(dims.getFirst(), GlslTypes.zero(type))) + ")";
		}

		Stage previous() {
			Stage prev = null;
			for (Stage x : p.stages) {
				if (x == s) return prev;
				if (x.type != StageType.COMPUTE) prev = x;
			}
			return null;
		}

		Stage next() {
			boolean found = false;
			for (Stage x : p.stages) {
				if (found) return x;
				if (x == s) found = true;
			}
			return null;
		}

		void emitFragOut(Item it, Declarator d, String type, String arr, StringBuilder out) {
			int index = outputIndex(s.fragOuts, indexOf(d));
			Integer loc = ctx.outputLocations() == null ? null : ctx.outputLocations().get(index);
			if (loc == null) out.append('\n').append(type).append(' ').append(kw(d.name())).append(arr).append(';');
			else out.append("\nlayout(location = ").append(loc).append(") out ").append(type).append(' ').append(kw(d.name())).append(arr).append(';');
		}

		int indexOf(Declarator d) {
			for (int i = 0; i < s.fragOuts.size(); i++) if (s.fragOuts.get(i).d() == d) return i;
			return 0;
		}

		void emitBlock(Item it, StringBuilder out) {
			String storage = it.storage();
			List<String> layout = new ArrayList<>();
			String packing = null;
			if (it.layout != null) for (String[] kv : it.layout) {
				if (Set.of("binding", "set", "location").contains(kv[0])) continue;
				if (Set.of("std140", "std430", "shared", "packed").contains(kv[0])) { packing = kv[0]; continue; }
				layout.add(kv[1] == null ? kv[0] : kv[0] + " = " + kv[1]);
			}
			int b;
			if ("buffer".equals(storage)) {
				b = binding("bufferObject" + layoutInt(it, "binding", 0));
				packing = "std140".equals(packing) ? "std140" : "std430";
			} else if ("uniform".equals(storage)) {
				b = binding(it.name);
				packing = "std140";
			} else {
				diagnostics.add(Diagnostic.warning(s.src.sourcePath(), 0, "interface block " + it.name + " passed through unchanged"));
				rw(it.start, it.end, out);
				return;
			}
			layout.addFirst("binding = " + b);
			layout.addFirst("set = 1");
			layout.addFirst(packing);
			StringBuilder q = new StringBuilder();
			for (String x : it.quals) q.append(x).append(' ');
			out.append("\nlayout(").append(String.join(", ", layout)).append(") ").append(q).append(it.name).append(' ');
			rw(it.bodyStart, it.end, out);
		}

		// -- token rewriting --

		String rws(int from, int to) {
			StringBuilder sb = new StringBuilder();
			rw(from, to, sb);
			return sb.toString().trim();
		}

		void rw(int from, int to, StringBuilder out) {
			for (int i = from; i < to; i++) {
				Token k = t.get(i);
				if (!k.ident()) { out.append(k.ws).append(k.text); continue; }
				String name = k.text;
				out.append(k.ws);
				if (i > 0 && t.get(i - 1).is(".")) { out.append(kw(name)); continue; }
				boolean indexed = i + 3 < to && t.get(i + 1).is("[") && t.get(i + 2).kind == GlslLexer.Kind.NUMBER && t.get(i + 3).is("]");
				if (name.equals("gl_FragData") || name.equals("gl_FragColor")) {
					int idx = 0;
					if (name.equals("gl_FragData")) {
						if (!indexed) {
							diagnostics.add(Diagnostic.error(s.src.sourcePath(), 0, "gl_FragData with a non-literal index"));
							out.append(name);
							continue;
						}
						idx = Integer.decode(t.get(i + 2).text);
						i += 3;
					}
					fragData.add(idx);
					out.append("emr_FragData").append(idx);
					continue;
				}
				if (name.equals("gl_TextureMatrix") && indexed) {
					int idx = Integer.decode(t.get(i + 2).text);
					i += 3;
					if (!p.geometry) out.append("mat4(1.0)");
					else if (idx == 0) out.append(terrain ? "mat4(1.0)" : "emr_dt.TextureMat");
					else if (idx <= 2) out.append(LIGHTMAP_MATRIX);
					else out.append("mat4(1.0)");
					continue;
				}
				boolean call = i + 1 < to && t.get(i + 1).is("(");
				if (call) {
					String inner = SHADOW_CALLS.get(name);
					if (inner != null) {
						int close = GlslParser.match(t, i + 1);
						out.append("vec4(").append(inner);
						rw(i + 1, close + 1, out);
						out.append(')');
						i = close;
						continue;
					}
					String r = callRepl.get(name);
					if (r != null) { out.append(r); continue; }
					if (repl.containsKey(name)) { out.append(repl.get(name)); continue; }
					out.append(name.equals("texture") ? name : kw(name));
					continue;
				}
				String r = repl.get(name);
				out.append(r != null ? r : kw(name));
			}
		}

		// -- wrapper, header --

		String wrapper() {
			StringBuilder w = new StringBuilder("\n\nvoid main() {\n");
			for (String l : prologue) w.append('\t').append(l).append('\n');
			w.append("\temr_main();\n");
			for (String l : epilogue) w.append('\t').append(l).append('\n');
			if (s.type == StageType.FRAGMENT && fragData.contains(0)) w.append(alphaTest());
			if (lastVertexStage && s.type != StageType.GEOMETRY && s.used("gl_Position")) {
				w.append("\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n");
			}
			return w.append("}\n").toString();
		}

		String alphaTest() {
			Manifest.AlphaTest a = ctx.alphaTest();
			if (!p.geometry || p.core) return "";
			// no alphaTest.<program>: Iris's per-geometry default (ShaderKey) is always "greater"; the mod pushes its ref (-1 = off)
			if (a == null) a = new Manifest.AlphaTest("greater", 0);
			String op = switch (a.func()) {
				case "never" -> null;
				case "less" -> "<";
				case "equal" -> "==";
				case "lequal" -> "<=";
				case "greater" -> ">";
				case "notequal" -> "!=";
				case "gequal" -> ">=";
				default -> "always";
			};
			if ("always".equals(op)) return "";
			if (op == null) return "\tdiscard;\n";
			return "\tif (!(emr_FragData0.a " + op + " emr_draw.emr_alphaTestRef)) discard;\n";
		}

		Map<String, String> helpers() {
			Map<String, String> h = new LinkedHashMap<>();
			h.put("emr_FogParameters", "struct emr_FogParameters { vec4 color; float density; float start; float end; float scale; };");
			h.put("emr_translate", "mat4 emr_translate(vec3 o) { return mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, o.x, o.y, o.z, 1.0); }");
			// fullscreen triangle: vertices 0,1,2 → UV (0,0), (2,0), (0,2); UV (0,0) is the bottom-left as in GL
			h.put("emr_fsUV", "vec2 emr_fsUV() { return vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)); }");
			h.put("emr_ftransform", "vec4 emr_ftransform() { return " + repl.getOrDefault("gl_ModelViewProjectionMatrix", "mat4(1.0)")
					+ " * " + repl.getOrDefault("gl_Vertex", "vec4(0.0, 0.0, 0.0, 1.0)") + "; }");
			h.put("emr_EmitVertex", "void emr_EmitVertex() { gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; EmitVertex(); }");
			h.put("emr_FrontColor", "vec4 emr_FrontColor;");
			return h;
		}

		String header(String body) {
			Set<String> ids = idents(body + String.join("\n", prologue) + String.join("\n", epilogue));
			if (s.type == StageType.FRAGMENT && fragData.contains(0) && !alphaTest().isEmpty()) ids.add("emr_draw");
			Map<String, String> helpers = helpers();
			List<String> usedHelpers = new ArrayList<>();
			boolean changed = true;
			while (changed) {
				changed = false;
				for (var e : helpers.entrySet()) {
					if (ids.contains(e.getKey()) && !usedHelpers.contains(e.getKey())) {
						usedHelpers.add(e.getKey());
						ids.addAll(idents(e.getValue()));
						changed = true;
					}
				}
			}
			StringBuilder h = new StringBuilder("#version 450\n");
			for (Item it : s.items) {
				if (it.kind != ItemKind.DIRECTIVE) continue;
				String d = t.get(it.start).text.trim();
				if (d.startsWith("#extension")) {
					String ext = d.substring(10).split(":")[0].trim();
					if (DROP_EXTENSIONS.contains(ext) || ext.startsWith("GL_GOOGLE_")) continue;
					h.append(d).append('\n');
				} else if (d.startsWith("#pragma")) h.append(d).append('\n');
			}
			// EmrionUniforms: only members this stage references, at their fixed offsets
			Map<String, Manifest.UniformMember> members = ctx.uniformMembers() == null ? Map.of() : ctx.uniformMembers();
			TreeMap<Integer, String> block = new TreeMap<>();
			List<String> consts = new ArrayList<>();
			for (var e : providedUniforms.entrySet()) block.put(members.get(e.getKey()).offset(), e.getValue());
			for (var e : INJECTED_UNIFORMS.entrySet()) {
				if (!ids.contains(e.getKey())) continue;
				Manifest.UniformMember m = members.get(e.getKey());
				if (m != null) block.put(m.offset(), m.type() + " " + e.getKey());
				else consts.add("const " + e.getValue() + " " + e.getKey() + " = " + GlslTypes.zero(e.getValue()) + ";");
			}
			if (!block.isEmpty()) {
				h.append("layout(std140, set = 1, binding = ").append(VertexLayouts.BINDING_UNIFORMS).append(") uniform EmrionUniforms {\n");
				for (var e : block.entrySet()) h.append("\tlayout(offset = ").append(e.getKey()).append(") ").append(e.getValue()).append(";\n");
				h.append("};\n");
			}
			for (String c : consts) h.append(c).append('\n');
			if (ids.contains("emr_phase")) {
				h.append("layout(std140, set = 1, binding = ").append(VertexLayouts.BINDING_PHASE)
						.append(") uniform EmrionPhase {").append(declareMembers(PHASE)).append(" } emr_phase;\n");
			}
			if (ids.contains("emr_draw")) {
				h.append("layout(push_constant) uniform EmrionDraw {");
				h.append(declareMembers(PUSH)).append(" } emr_draw;\n");
			}
			if (ids.contains("emr_dt")) h.append("layout(std140, set = 0, binding = 0) uniform DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; } emr_dt;\n");
			if (ids.contains("emr_terrain")) h.append("layout(std140, set = 0, binding = 1) uniform TerrainUniform { mat4 ModelViewMat; ivec2 TextureSize; } emr_terrain;\n");
			if (ids.contains("emr_chunk")) h.append("layout(std140, set = 0, binding = 2) uniform ChunkSection { ivec3 ChunkPosition; float ChunkVisibility; } emr_chunk;\n");
			if (ids.contains("emr_Sampler1")) h.append("layout(set = 0, binding = 4) uniform sampler2D emr_Sampler1;\n");
			for (String c : VertexLayouts.INPUT_LOCATIONS) {
				if (ids.contains(in(c))) h.append("layout(location = ").append(VertexLayouts.location(c)).append(") in ")
						.append(CANON_TYPE.get(c)).append(' ').append(in(c)).append(";\n");
			}
			for (String[] v : List.of(new String[] {"emr_FogFragCoord", "", "float"}, new String[] {"emr_entityColor", "", "vec4"},
					new String[] {"emr_entityInfo", "flat ", "ivec3"})) {
				Integer loc = p.locations.get(v[0]);
				if (loc == null || !ids.contains(v[0]) || s.type == StageType.GEOMETRY) continue;
				String dir = s.type == StageType.VERTEX ? "out" : "in";
				h.append("layout(location = ").append(loc).append(") ").append(v[1]).append(dir).append(' ').append(v[2]).append(' ')
						.append(v[0]).append(";\n");
			}
			for (int i : fragData) {
				Integer loc = ctx.outputLocations() == null ? null : ctx.outputLocations().get(i);
				if (loc == null) h.append("vec4 emr_FragData").append(i).append(";\n");
				else h.append("layout(location = ").append(loc).append(") out vec4 emr_FragData").append(i).append(";\n");
			}
			for (String d : extraDecls) h.append(d).append('\n');
			for (String name : helpers.keySet()) if (usedHelpers.contains(name)) h.append(helpers.get(name)).append('\n');
			return h.toString();
		}

		static Set<String> idents(String text) {
			Set<String> ids = new HashSet<>();
			for (Token k : GlslLexer.lex(text)) if (k.ident()) ids.add(k.text);
			return ids;
		}
	}
}
