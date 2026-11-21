package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.glsl.GlslTranspiler;
import dev.emrion.shaders.converter.glsl.IncludeExpander;
import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.VertexLayouts;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vulkan GLSL of one native stage (§7.1): generated prelude (defines, the frame block, every resource with its set-1
 * binding, the vertex primitives of the Minecraft vertex layout), the header library, then the pack's source with
 * {@code #include}s expanded under {@code #line "file"} directives, so shaderc reports errors at the pack's file and line.
 * The only rewrites of pack text keep its line count: varyings get {@code layout(location)} by name (one table per pass),
 * and declarations of names the prelude already declares (custom uniforms, frame members, images) are blanked.
 */
final class NativeGlsl {
	/** Every line of a native stage carries a {@code #line "file"}; shaderc names the file itself. */
	static final LineMap NO_LINES = new LineMap() {
		public String file(int outputLine) { return null; }

		public int line(int outputLine) { return outputLine; }
	};

	/** Emberlight's fullscreen quad (§7.5): 6 vertices with MC's quad indices, el_uv (0, 0) bottom-left as in GL. */
	static final String FULLSCREEN_VERTEX = """
			#version 460
			layout(location = 0) out vec2 el_uv;
			invariant gl_Position;
			void main() {
				int v = int[](0, 1, 2, 2, 3, 0)[gl_VertexIndex];
				el_uv = vec2(float(v == 1 || v == 2), float(v >= 2));
				gl_Position = vec4(el_uv * 2.0 - 1.0, 0.0, 1.0);
				gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
			}
			""";
	/** The "shadow" slot when no pass draws that class: every vertex on one point, nothing is rasterised. */
	static final String NOTHING_VERTEX = "#version 460\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }\n";
	static final String NOTHING_FRAGMENT = "#version 460\nvoid main() { discard; }\n";

	static final String HEADER = NativeCompiler.resource("/nativefmt/emberlight.glsl");
	static final String RT_LIBRARY = NativeCompiler.resource("/rt/emrion_rt.glsl");

	final PackSource src;
	final NativeManifest m;
	final NativeOptions options;
	final boolean upscaling;
	/** RT pipelines on the device (request "raygen"): passes with "raygen" compile as ray generation shaders. */
	boolean raygen;
	/** DLSS Ray Reconstruction runs and the pack writes its guides (§14.4): EL_RR_ACTIVE and the el_rr* images. */
	final boolean rr;
	final Diagnostics diagnostics;
	final Bindings bindings;
	final String frameBlock;
	/** Names the prelude declares; a pack's own {@code uniform T name;} of one of them is blanked. */
	final Set<String> declared = new HashSet<>();
	private final Map<String, IncludeExpander.Expanded> expanded = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Integer>> varyings = new ConcurrentHashMap<>();

	NativeGlsl(PackSource src, NativeManifest m, NativeOptions options, Manifest.UniformLayout layout, boolean upscaling, boolean rr,
	          Diagnostics diagnostics) {
		this.src = src;
		this.m = m;
		this.options = options;
		this.upscaling = upscaling;
		this.rr = rr;
		this.diagnostics = diagnostics;
		this.bindings = new Bindings(m, rr);
		StringBuilder f = new StringBuilder("layout(std140, set = 1, binding = " + VertexLayouts.BINDING_UNIFORMS + ") uniform EmrionUniforms {\n");
		for (var u : layout.members()) {
			f.append("\tlayout(offset = ").append(u.offset()).append(") ").append(u.type()).append(' ').append(u.name())
					.append(u.arraySize() > 0 ? "[" + u.arraySize() + "]" : "").append(";\n");
			declared.add(u.name());
		}
		frameBlock = f.append("};\n").toString();
		declared.addAll(bindings.byName.keySet());
	}

	// ---- set-1 resources -----------------------------------------------------------------------------------------------

	/** The pack-wide set-1 table: one binding per (image or texture, view); each dimension keeps the bindings its programs use. */
	static final class Bindings {
		final List<Manifest.Resource> resources = new ArrayList<>();
		/** GLSL name → binding. */
		final Map<String, Integer> byName = new LinkedHashMap<>();
		/** GLSL name → declaration without the layout's binding (filled in by {@link #declarations}). */
		final Map<String, String> decl = new LinkedHashMap<>();
		/** Screen image → colortex index (4-15, then 0-3), shadow image → shadowcolor index. */
		final Map<String, Integer> colortex = new LinkedHashMap<>(), shadowColor = new LinkedHashMap<>();
		private final List<Manifest.TextureDef> textures = new ArrayList<>();
		final Set<String> computeOnly = new HashSet<>(), notGeometry = new HashSet<>(), rtOnly = new HashSet<>();

		Bindings(NativeManifest m, boolean rr) {
			int[] order = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0, 1, 2, 3};
			for (var i : m.images()) if (i.screen()) colortex.put(i.name(), order[colortex.size()]);
			if (m.shadow() != null) for (var i : m.shadow().images()) shadowColor.put(i.name(), shadowColor.size());
			for (var i : m.images()) {
				String target = i.screen() ? "colortex" + colortex.get(i.name()) : i.name();
				boolean nearest = i.integer();
				add(i.name(), "uniform " + sampler(i) + " " + i.name(), new Manifest.Resource(0, "sampled_image", null, target, null, null,
						new Manifest.SamplerDef(nearest ? "nearest" : "linear", i.mipmaps() ? (nearest ? "nearest" : "linear") : "none", "clamp", null)));
			}
			for (var i : m.images()) {
				if (!i.storage()) continue;
				String target = i.screen() ? "colortex" + colortex.get(i.name()) : i.name();
				add(i.name() + "_img", "uniform " + sampler(i).replace("sampler", "image") + " " + i.name() + "_img",
						new Manifest.Resource(0, "storage_image", null, target, null, null, null), qualifier(i.format()));
				computeOnly.add(i.name() + "_img");
			}
			if (rr) for (String g : NativeManifest.RR_IMAGES) { // targets of the same name (NativePlanner), read by the RR guide pass
				add(g, "uniform sampler2D " + g, new Manifest.Resource(0, "sampled_image", null, g, null, null,
						new Manifest.SamplerDef("nearest", "none", "clamp", null)));
				notGeometry.add(g);
				add(g + "_img", "uniform image2D " + g + "_img", new Manifest.Resource(0, "storage_image", null, g, null, null, null), "rgba16f");
				computeOnly.add(g + "_img");
			}
			if (m.writesMotionDistance()) { // target of the same name (NativePlanner), read by Emberlight's motion vectors
				add(NativeManifest.MOTION_IMAGE + "_img", "uniform image2D " + NativeManifest.MOTION_IMAGE + "_img",
						new Manifest.Resource(0, "storage_image", null, NativeManifest.MOTION_IMAGE, null, null, null), "r32f");
				computeOnly.add(NativeManifest.MOTION_IMAGE + "_img");
			}
			boolean ownNoise = false;
			for (var t : m.textures()) {
				boolean three = t.rawSize() != null && t.rawSize().length == 3;
				String glsl = t.name().equals("noise") ? "el_noise" : t.name();
				ownNoise |= t.name().equals("noise");
				String file = t.file();
				int[] size = t.rawSize();
				String format = t.rawFormat() != null ? t.rawFormat() : "RGBA8";
				// integer formats cannot be filtered linearly in Vulkan
				textures.add(new Manifest.TextureDef(t.name(), file, t.resource(), format,
						three ? 3 : 2, size, format.endsWith("I") ? "nearest" : t.filter(), t.wrap(), false, t.rawPixel()));
				add(glsl, "uniform " + samplerPrefix(format) + (three ? "3D " : "2D ") + glsl, new Manifest.Resource(0, "sampled_image", null, null, t.name(), null, null));
			}
			if (!ownNoise) {
				textures.add(new Manifest.TextureDef("noisetex", null, "emrion:noise", "RGBA8", 2, new int[]{256, 256}, "linear", "repeat", false, null));
				add("el_noise", "uniform sampler2D el_noise", new Manifest.Resource(0, "sampled_image", null, null, "noisetex", null,
						new Manifest.SamplerDef("linear", "none", "repeat", null)));
			}
			String[][] depth = {{"el_depth", "depthtex0"}, {"el_depthOpaque", "depthtex1"}, {"el_depthNoHand", "depthtex2"}};
			for (String[] d : depth) {
				add(d[0], "uniform sampler2D " + d[0], new Manifest.Resource(0, "sampled_image", null, d[1], null, null,
						new Manifest.SamplerDef("nearest", "none", "clamp", null)));
				notGeometry.add(d[0]);
			}
			if (m.shadow() != null) {
				String[][] sd = {{"el_shadowDepth", "shadowtex0"}, {"el_shadowDepthOpaque", "shadowtex1"}};
				for (String[] d : sd) {
					add(d[0], "uniform sampler2D " + d[0], new Manifest.Resource(0, "sampled_image", null, d[1], null, null,
							new Manifest.SamplerDef("linear", "none", "clamp", null)));
				}
				for (var e : shadowColor.entrySet()) {
					String n = "el_shadowColor" + e.getValue();
					add(n, "uniform sampler2D " + n, new Manifest.Resource(0, "sampled_image", null, "shadowcolor" + e.getValue(), null, null,
							new Manifest.SamplerDef("linear", "none", "clamp", null)));
				}
			}
			for (var b : m.buffers()) add(b.name(), null, new Manifest.Resource(0, "storage_buffer", null, b.name(), null, null, null));
			if (m.requires().contains("ray_query")) {
				add("emrRtTlas", "uniform accelerationStructureEXT emrRtTlas", new Manifest.Resource(0, "acceleration_structure", null, null, null, "emrion_tlas", null));
				add("emrRtAtlas", "uniform sampler2D emrRtAtlas", new Manifest.Resource(0, "sampled_image", null, null, null, "block_atlas", null));
				add("emrRtEntityAtlas", "uniform sampler2D emrRtEntityAtlas", new Manifest.Resource(0, "sampled_image", null, null, null,
						"emrion_rt_entity_atlas", new Manifest.SamplerDef("nearest", "none", "clamp", null)));
				// the block atlas's LabPBR maps (same layout as emrRtAtlas): materials at ray hits (el_hitSpecular / el_hitNormalMap)
				for (String[] a : new String[][]{{"emrRtNormalAtlas", "block_atlas_normals"}, {"emrRtSpecularAtlas", "block_atlas_specular"}})
					add(a[0], "uniform sampler2D " + a[0], new Manifest.Resource(0, "sampled_image", null, null, null, a[1],
							new Manifest.SamplerDef("nearest", "none", "clamp", null)));
				add("EmrRtSections", "readonly buffer EmrRtSections { uvec4 emrRtSection[]; }",
						new Manifest.Resource(0, "storage_buffer", null, null, null, "emrion_rt_sections", null), "std430");
				add("EmrRtLights", RT_LIGHTS, new Manifest.Resource(0, "storage_buffer", null, null, null, "emrion_rt_lights", null), "std430");
				rtOnly.addAll(List.of("emrRtTlas", "emrRtAtlas", "emrRtEntityAtlas", "emrRtNormalAtlas", "emrRtSpecularAtlas", "EmrRtSections", "EmrRtLights"));
			}
		}

		private void add(String name, String declaration, Manifest.Resource r) { add(name, declaration, r, null); }

		private void add(String name, String declaration, Manifest.Resource r, String layout) {
			int b = VertexLayouts.FIRST_RESOURCE_BINDING + resources.size();
			resources.add(new Manifest.Resource(b, r.kind(), r.name(), r.target(), r.texture(), r.builtin(), r.sampler()));
			byName.put(name, b);
			if (declaration != null)
				decl.put(name, "layout(" + (layout != null && layout.startsWith("std") ? layout + ", " : "") + "set = 1, binding = " + b
						+ (layout != null && !layout.startsWith("std") ? ", " + layout : "") + ") " + declaration + ";\n");
		}

		List<Manifest.TextureDef> textures() { return textures; }

		/** Resource of a binding. */
		Manifest.Resource at(int binding) { return resources.get(binding - VertexLayouts.FIRST_RESOURCE_BINDING); }

		static String sampler(NativeManifest.Image i) {
			return samplerPrefix(i.format()) + (i.size() != null && i.size().length == 3 ? "3D" : "2D");
		}

		static String samplerPrefix(String format) {
			return !format.endsWith("I") ? "sampler" : format.endsWith("UI") ? "usampler" : "isampler";
		}

		static String qualifier(String format) {
			return switch (format) {
				case "R11F_G11F_B10F" -> "r11f_g11f_b10f";
				case "RGB10_A2" -> "rgb10_a2";
				default -> format.toLowerCase(Locale.ROOT);
			};
		}
	}

	/** RT light list block (§7.6); its layout is the mod's RtBlockLights buffer. */
	static final String RT_LIGHTS = """
			readonly buffer EmrRtLights {
				vec4 emrRtLightOrigin;
				ivec4 emrRtLightDims;
				vec4 emrRtLightChange[2];
				uint emrRtLightCell[131072];
				vec4 emrRtLightData[8192]; // not emrRtLight: that is a function of the RT library
				vec4 emrBlPos[16384];
				vec4 emrBlCol[16384];
				uint emrBlRemap[16384];
				uint emrBlCellStart[4097];
				uint emrBlBlock[32768];
				uint emrBlIndex[];
			}""";

	// ---- stage assembly ------------------------------------------------------------------------------------------------

	private static final Pattern UNIFORM_DECL = Pattern.compile("^\\s*uniform\\s+\\w+\\s+(\\w+)\\s*;.*$");
	private static final Pattern EL_UV_DECL = Pattern.compile("^\\s*(?:(?:smooth|noperspective)\\s+)?in\\s+vec2\\s+el_uv\\s*;.*$");
	/** A global varying without layout: qualifiers, in/out, type, name, optional array, ';'. */
	private static final Pattern VARYING = Pattern.compile(
			"^(\\s*)((?:(?:flat|smooth|noperspective|centroid|invariant|sample|highp|mediump|lowp)\\s+)*)(in|out)\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*(\\[\\s*\\w*\\s*])?\\s*;(.*)$");
	/** A global varying with an explicit {@code layout(location = N)}: its locations are kept free of named varyings. */
	private static final Pattern EXPLICIT_VARYING = Pattern.compile(
			"^\\s*layout\\s*\\([^)]*\\blocation\\s*=\\s*(\\w+)[^)]*\\)\\s*(?:(?:flat|smooth|noperspective|centroid|invariant|sample|highp|mediump|lowp)\\s+)*(in|out)\\s+([A-Za-z_]\\w*)\\s+[A-Za-z_]\\w*\\s*(\\[\\s*\\w*\\s*])?\\s*;.*$");
	private static final Pattern INT_DEFINE = Pattern.compile("^\\s*#\\s*define\\s+(\\w+)\\s+(\\d+)\\s*(?://.*)?$", Pattern.MULTILINE);
	private static final Pattern ENTITY_USE = Pattern.compile("\\bel_(entityId|blockEntityId|itemId|entityColor)\\b");

	String file(NativeManifest.Pass p, StageType st) {
		return p.stages().get(switch (st) {
			case VERTEX -> "vertex";
			case GEOMETRY -> "geometryShader";
			case COMPUTE -> "compute";
			default -> "fragment";
		});
	}

	/** Include-expanded pack source (cached per file; expansion errors are reported once). */
	IncludeExpander.Expanded expand(String file) {
		return expanded.computeIfAbsent(file, f -> {
			var e = IncludeExpander.expand("/" + f.substring("shaders/".length()), path -> {
				try {
					return src.readString("shaders" + path).map(t -> List.of(t.split("\n", -1))).orElse(null);
				} catch (java.io.IOException x) {
					throw new java.io.UncheckedIOException(x);
				}
			});
			for (var err : e.errors()) diagnostics.add(Diagnostic.error(err.file() == null ? f : "shaders" + err.file(), err.line(), err.message()));
			return e;
		});
	}

	/**
	 * Varying locations of a pass by name (§7.2), over all its stages in stage order; el_uv is 0 in fullscreen and final passes.
	 * The table is built from the expanded text including inactive #if branches, so every stage and class agrees on it.
	 */
	Map<String, Integer> varyings(NativeManifest.Pass p) {
		return varyings.computeIfAbsent(p.name(), k -> {
			Map<String, Integer> loc = new LinkedHashMap<>();
			List<StageType> stages = new ArrayList<>();
			for (StageType st : List.of(StageType.VERTEX, StageType.GEOMETRY, StageType.FRAGMENT)) if (file(p, st) != null) stages.add(st);
			// integer #defines visible to the pass: symbolic array sizes and explicit locations resolve through them
			Map<String, Integer> ints = new HashMap<>();
			StringBuilder all = new StringBuilder(options.defines());
			p.defines().forEach((n, v) -> all.append("#define ").append(n).append(' ').append(v).append('\n'));
			for (StageType st : stages) all.append(expand(file(p, st)).text()).append('\n');
			for (Matcher d = INT_DEFINE.matcher(all); d.find(); ) ints.putIfAbsent(d.group(1), Integer.parseInt(d.group(2)));
			BitSet used = new BitSet();
			if (!p.kind().equals("geometry") && !p.kind().equals("compute")) {
				loc.put("el_uv", 0);
				used.set(0);
			}
			for (StageType st : stages) for (String line : expand(file(p, st)).text().split("\n")) {
				Matcher x = EXPLICIT_VARYING.matcher(line);
				if (!x.matches() || !carriesVarying(st, x.group(2))) continue;
				Integer at = x.group(1).matches("\\d+") ? Integer.valueOf(x.group(1)) : ints.get(x.group(1));
				if (at == null) diagnostics.add(Diagnostic.error(file(p, st), 0, "varying location \"" + x.group(1) + "\" is not an integer #define"));
				else used.set(at, at + Math.max(1, locations(x.group(3), x.group(4), st == StageType.GEOMETRY && x.group(2).equals("in"), ints)));
			}
			for (StageType st : stages) for (String line : expand(file(p, st)).text().split("\n")) {
				Matcher v = VARYING.matcher(line);
				if (!v.matches() || !carriesVarying(st, v.group(3)) || loc.containsKey(v.group(5))) continue;
				int n = locations(v.group(4), v.group(6), st == StageType.GEOMETRY && v.group(3).equals("in"), ints);
				if (n < 0) {
					diagnostics.add(Diagnostic.error(file(p, st), 0, "varying \"" + v.group(5) + "\": array size " + v.group(6) + " is not an integer #define"));
					n = 1;
				}
				int at = 0;
				while (used.get(at, at + n).cardinality() > 0) at++;
				used.set(at, at + n);
				loc.put(v.group(5), at);
			}
			loc.put("", used.length()); // first location past every varying (Emberlight's own varyings)
			return loc;
		});
	}

	static boolean carriesVarying(StageType st, String dir) {
		return st == StageType.VERTEX && dir.equals("out") || st == StageType.GEOMETRY || st == StageType.FRAGMENT && dir.equals("in");
	}

	/** Interface locations of a varying; per-vertex geometry inputs ({@code in T x[]}) count their element type; -1: unknown size. */
	static int locations(String type, String array, boolean perVertex, Map<String, Integer> ints) {
		int n = switch (type) {
			case "mat2", "dmat2" -> 2;
			case "mat3", "dmat3" -> 3;
			case "mat4", "dmat4" -> 4;
			default -> 1;
		};
		if (array == null || perVertex) return n;
		String size = array.replaceAll("[\\[\\]\\s]", "");
		Integer len = size.matches("\\d+") ? Integer.valueOf(size) : ints.get(size);
		return len == null ? -1 : n * len;
	}

	boolean fragmentUsesEntity(NativeManifest.Pass p) {
		String f = file(p, StageType.FRAGMENT);
		return f != null && ENTITY_USE.matcher(expand(f).text()).find();
	}

	/** The complete GLSL of one stage. {@code variant}: the vertex layout (geometry VS/GS), "fullscreen", or null. */
	String stage(NativeCompiler.Prog prog, NativeManifest.Dimension dim, StageType st, String variant) {
		NativeManifest.Pass p = prog.pass();
		String kind = p.kind();
		boolean geometry = prog.geometry(), raytracing = p.raytracing();
		boolean lastVertex = st == StageType.GEOMETRY || st == StageType.VERTEX && !p.stages().containsKey("geometryShader");
		boolean entity = geometry && fragmentUsesEntity(p) && !p.stages().containsKey("geometryShader");
		Map<String, Integer> loc = varyings(p);

		StringBuilder b = new StringBuilder("#version 460\n#extension GL_GOOGLE_cpp_style_line_directive : require\n");
		if (raytracing) b.append("#extension GL_EXT_ray_query : require\n#extension GL_EXT_buffer_reference : require\n"
				+ "#extension GL_EXT_buffer_reference_uvec2 : require\n");
		// the pass runs as a raygen shader of a raygen-only RT pipeline (ShadercSpirvCompiler reads the pragma; RtRaygen); its
		// traces stay ray queries
		boolean raygen = st == StageType.COMPUTE && this.raygen && p.raygen();
		if (raygen) b.append("#pragma shader_stage(raygen)\n#extension GL_EXT_ray_tracing : require\n");
		b.append("#line 1 \"emberlight-prelude\"\n");
		if (raygen) b.append("#define EL_STAGE_RAYGEN 1\n#define EL_GLOBAL_ID gl_LaunchIDEXT\n");
		else if (st == StageType.COMPUTE) b.append("#define EL_GLOBAL_ID gl_GlobalInvocationID\n");
		if (st == StageType.COMPUTE && !raygen) b.append("layout(local_size_x = ").append(p.localSize()[0]).append(", local_size_y = ").append(p.localSize()[1])
				.append(", local_size_z = ").append(p.localSize()[2]).append(") in;\n");
		// ---- defines (§7.1)
		b.append("#define EL_STAGE_").append(switch (st) {
			case VERTEX -> "VERTEX";
			case GEOMETRY -> "GEOMETRY";
			case COMPUTE -> "COMPUTE";
			default -> "FRAGMENT";
		}).append(" 1\n#define EL_FORMAT 1\n");
		b.append("#define EL_PASS_").append(p.name()).append(" 1\n#define EL_KIND_").append(kind.toUpperCase(Locale.ROOT)).append(" 1\n");
		b.append("#define EL_PHASE_").append(p.phase().toUpperCase(Locale.ROOT)).append(" 1\n");
		b.append("#define EL_DIMENSION_").append(dim == null ? "DEFAULT" : dim.name().toUpperCase(Locale.ROOT)).append(" 1\n");
		if (geometry) {
			b.append("#define EL_CLASS_").append(prog.cls().toUpperCase(Locale.ROOT)).append(" 1\n");
			float cutoff = p.alphaCutoff() != null ? p.alphaCutoff() : NativeManifest.CUTOUT.contains(prog.cls()) ? 0.1f : 0f;
			b.append("#define EL_ALPHA_CUTOFF ").append(NativeManifest.glslFloat(cutoff)).append('\n');
		}
		if (upscaling) b.append("#define EL_UPSCALING 1\n");
		if (rr) b.append("#define EL_RR_ACTIVE 1\n");
		if (raytracing) b.append("#define EL_RAYTRACING 1\n#define EL_LIGHTS 1\n");
		if (m.shadow() != null) b.append("#define EL_SHADOW_MAP 1\n");
		b.append(options.defines());
		if (dim != null) dim.defines().forEach((k, v) -> b.append("#define ").append(k).append(' ').append(v).append('\n'));
		p.defines().forEach((k, v) -> b.append("#define ").append(k).append(' ').append(v).append('\n'));
		if (!kind.equals("compute")) for (int i = 0; i < p.writes().size(); i++) b.append("#define EL_OUT_").append(p.writes().get(i)).append(' ').append(i).append('\n');
		for (var buf : m.buffers()) b.append("#define EL_BUFFER_").append(buf.name()).append(' ').append(bindings.byName.get(buf.name())).append(", set = 1\n");
		bindings.shadowColor.forEach((name, n) -> b.append("#define ").append(name).append(" el_shadowColor").append(n).append('\n'));

		// ---- declarations
		b.append(frameBlock);
		bindings.decl.forEach((name, d) -> {
			if (bindings.computeOnly.contains(name) && st != StageType.COMPUTE) return;
			if (bindings.notGeometry.contains(name) && geometry) return;
			if (bindings.rtOnly.contains(name) && !raytracing || name.equals("EmrRtLights")) return;
			b.append(d);
		});
		if (geometry) {
			b.append("layout(std140, set = 1, binding = ").append(VertexLayouts.BINDING_PHASE).append(") uniform EmrionPhase {");
			for (var u : Std140LayoutBuilder.PHASE.members()) b.append(" layout(offset = ").append(u.offset()).append(") ").append(u.type()).append(' ').append(u.name()).append(';');
			b.append(" } emr_phase;\nlayout(push_constant) uniform EmrionDraw {");
			for (var u : Std140LayoutBuilder.PUSH.members()) b.append(" layout(offset = ").append(u.offset()).append(") ").append(u.type()).append(' ').append(u.name()).append(';');
			if ("terrain_sodium".equals(variant) && st == StageType.VERTEX) { // Sodium's push constants, moved behind EmrionDraw by the mod
				int o = VertexLayouts.SODIUM_PUSH_OFFSET;
				b.append(" layout(offset = ").append(o).append(") vec3 emr_sodRegionOffset; layout(offset = ").append(o + 12)
						.append(") int emr_sodCurrentTime; layout(offset = ").append(o + 16).append(") uint emr_sodRegionID;");
			}
			b.append(" } emr_draw;\n");
			if (st == StageType.VERTEX) b.append(vertexPrimitives(variant, prog.shadow()));
			if (st == StageType.FRAGMENT) b.append("layout(set = 0, binding = 3) uniform sampler2D emr_gtexture;\n"
					+ "layout(set = 0, binding = 5) uniform sampler2D emr_lightmap;\n"
					+ "layout(set = 0, binding = 8) uniform sampler2D emr_normals;\n"
					+ "layout(set = 0, binding = 9) uniform sampler2D emr_specular;\n");
			if (entity) {
				String dir = st == StageType.VERTEX ? "out" : "in";
				int l = loc.get("");
				if (st == StageType.FRAGMENT) b.append("#define EL_ENTITY_VARYINGS 1\n");
				if (st != StageType.GEOMETRY) b.append("layout(location = ").append(l).append(") flat ").append(dir).append(" ivec3 emr_entityInfo;\n")
						.append("layout(location = ").append(l + 1).append(") flat ").append(dir).append(" vec4 emr_entityColor;\n");
			}
		}
		if (!geometry && !kind.equals("compute") && st == StageType.FRAGMENT) b.append("layout(location = 0) in vec2 el_uv;\n");
		// after the library: its member emrRtLight would hide the library's function of that name
		if (raytracing) b.append("#line 1 \"rt/emrion_rt.glsl\"\n").append(rtLibrary()).append(bindings.decl.get("EmrRtLights"));
		b.append("#line 1 \"emberlight.glsl\"\n").append(HEADER).append('\n');

		// ---- the pack's stage
		boolean wrap = lastVertex && st == StageType.VERTEX;
		if (wrap) b.append("#line 1 \"emberlight-prelude\"\ninvariant gl_Position;\n#define main emr_user_main\n");
		if (st == StageType.GEOMETRY) b.append("#line 1 \"emberlight-prelude\"\n"
				+ "void emr_EmitVertex() { gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; EmitVertex(); }\n#define EmitVertex emr_EmitVertex\n");
		body(b, file(p, st), st, !geometry && !kind.equals("compute"), loc);
		if (wrap) {
			b.append("#line 1 \"emberlight-prelude\"\n#undef main\nvoid main() {\n");
			if (entity) b.append("\temr_entityInfo = emr_entityIds();\n\temr_entityColor = emr_entityColorVS();\n");
			b.append("\temr_user_main();\n\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n");
		}
		return b.toString();
	}

	/** The pack's expanded source with {@code #line "file"} directives and the line-preserving rewrites. */
	void body(StringBuilder b, String file, StageType st, boolean fullscreen, Map<String, Integer> loc) {
		var e = expand(file);
		String[] lines = e.text().split("\n", -1);
		int prevFile = -1, prevLine = -1;
		for (int i = 0; i < e.lineCount() && i < lines.length; i++) {
			int f = e.fileOfLine()[i], l = e.lineOfLine()[i];
			if (f != prevFile || l != prevLine + 1) b.append("#line ").append(l).append(" \"shaders").append(e.files().get(f)).append("\"\n");
			prevFile = f;
			prevLine = l;
			String line = lines[i];
			Matcher u = UNIFORM_DECL.matcher(line);
			if (u.matches() && declared.contains(u.group(1)) || line.trim().startsWith("#version")
					|| fullscreen && st == StageType.FRAGMENT && EL_UV_DECL.matcher(line).matches()) {
				b.append('\n');
				continue;
			}
			Matcher v = VARYING.matcher(line);
			if (v.matches() && carriesVarying(st, v.group(3)) && loc.containsKey(v.group(5)))
				line = v.group(1) + "layout(location = " + loc.get(v.group(5)) + ") " + line.substring(v.group(1).length());
			b.append(line).append('\n');
		}
	}

	/** rt/emrion_rt.glsl without its declarations (the prelude declares them with their bindings). */
	static String rtLibrary() {
		String s = RT_LIBRARY;
		for (String d : List.of("layout(std430) readonly buffer EmrRtSections { uvec4 emrRtSection[]; };", "uniform accelerationStructureEXT emrRtTlas;",
				"uniform sampler2D emrRtAtlas;", "uniform sampler2D emrRtEntityAtlas;")) {
			if (!s.contains(d)) throw new IllegalStateException("rt/emrion_rt.glsl changed: " + d);
			s = s.replace(d, "");
		}
		return s + "\n";
	}

	// ---- vertex primitives ---------------------------------------------------------------------------------------------

	/**
	 * Declarations and emr_* primitives of one vertex layout (FORMAT.md §7.1 variants): set-0 blocks at their canonical
	 * placeholder bindings, the inputs the layout has (missing ones get the defaults Iris uses), model-view and texture matrices.
	 */
	static String vertexPrimitives(String variant, boolean shadow) {
		List<String> has = VertexLayouts.VARIANTS.get(variant);
		boolean terrain = variant.equals("terrain_sodium"), clouds = variant.equals("clouds");
		java.util.function.Function<String, String> in = c -> {
			if (clouds && c.equals("Position")) return "emr_cloudPos()";
			if (clouds && c.equals("Color")) return "emr_cloudCol()";
			if (clouds && c.equals("Normal")) return "emr_cloudNormal()";
			if (terrain && c.equals("Position")) return "emr_sodPosition()";
			if (terrain && c.equals("UV0")) return "emr_sodUV0()";
			if (terrain && c.equals("UV2")) return "ivec2(emr_sod_LightAndData.xy)";
			if (terrain && c.equals("at_midBlock")) return "vec4(emr_sod_MidBlock)";
			return "emr_in_" + c;
		};
		StringBuilder b = new StringBuilder();
		if (!terrain) b.append("layout(std140, set = 0, binding = 0) uniform DynamicTransforms { mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset; } emr_dt;\n");
		if (has.contains("UV1")) b.append("layout(set = 0, binding = 4) uniform sampler2D emr_Sampler1;\n");
		if (clouds) b.append("layout(std140, set = 0, binding = ").append(VertexLayouts.SET0_CANONICAL.indexOf("CloudInfo"))
				.append(") uniform CloudInfo { vec4 CloudColor; vec3 CloudOffset; vec3 CellSize; } emr_CloudInfo;\n")
				.append("layout(set = 0, binding = ").append(VertexLayouts.SET0_CANONICAL.indexOf("CloudFaces")).append(") uniform isamplerBuffer emr_CloudFaces;\n")
				.append(GlslTranspiler.HELPER_CLOUD_POS).append('\n').append(GlslTranspiler.HELPER_CLOUD_NORMAL).append('\n')
				.append(GlslTranspiler.HELPER_CLOUD_COL).append('\n');
		if (terrain) {
			b.append("layout(std140, set = 0, binding = ").append(VertexLayouts.SET0_CANONICAL.indexOf("u_Globals"))
					.append(") uniform SodiumGlobals { mat4 u_ProjectionMatrix; mat4 u_ModelViewMatrix; vec4 u_FogColor; vec3 u_CameraOffset; vec2 u_EnvironmentFog;"
							+ " vec2 u_RenderFog; vec2 u_TexelSize; vec2 u_TexCoordShrink; float u_FadePeriodInv; uint u_UseRGSS; } emr_sodGlobals;\n");
			b.append("layout(location = 0) in uvec2 emr_sod_Position;\nlayout(location = 2) in uvec2 emr_sod_TexCoord;\n")
					.append("layout(location = 3) in uvec4 emr_sod_LightAndData;\nlayout(location = ").append(VertexLayouts.location("at_midBlock"))
					.append(") in ivec4 emr_sod_MidBlock;\n");
			b.append(GlslTranspiler.HELPER_SOD_CHUNK_OFFSET).append('\n').append(GlslTranspiler.HELPER_SOD_POSITION).append('\n')
					.append(GlslTranspiler.HELPER_SOD_UV0).append('\n');
		}
		for (String c : VertexLayouts.INPUT_LOCATIONS)
			if (has.contains(c) && in.apply(c).equals("emr_in_" + c))
				b.append("layout(location = ").append(VertexLayouts.location(c)).append(") in ").append(GlslTranspiler.CANON_TYPE.get(c))
						.append(" emr_in_").append(c).append(";\n");

		String pos = has.contains("Position") ? in.apply("Position") : "vec3(0.0)";
		// Sodium writes its globals once per frame: the frame block's matrices serve the shadow pass and the main pass
		String mvRaw = terrain ? (shadow ? "shadowModelView" : "gbufferModelView") : "emr_dt.ModelViewMat";
		String mv = terrain ? mvRaw : "(emr_dt.ModelViewMat * mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, emr_dt.ModelOffset, 1.0))";
		String color = has.contains("Color") ? (terrain || clouds ? in.apply("Color") : "(" + in.apply("Color") + " * emr_dt.ColorModulator)")
				: terrain ? "vec4(1.0)" : "emr_dt.ColorModulator";
		String uv = has.contains("UV0") ? (terrain ? in.apply("UV0") : "(emr_dt.TextureMat * vec4(" + in.apply("UV0") + ", 0.0, 1.0)).xy") : "vec2(0.5)";
		String light = has.contains("UV2") ? "clamp(((" + GlslTranspiler.LIGHTMAP_MATRIX + " * vec4(" + in.apply("UV2") + ", 0.0, 1.0)).xy - 1.0 / 32.0) * 16.0 / 15.0, 0.0, 1.0)" : "vec2(1.0)";
		String ids = has.contains("iris_Entity") ? "((ivec3(emr_in_iris_Entity) << 16) >> 16)" // RGBA16_UINT: sign-extend, -1 = none
				: "ivec3(emr_draw.entityId, emr_draw.blockEntityId, emr_draw.currentRenderedItemId)";
		b.append("vec3 emr_position() { return ").append(pos).append("; }\n");
		b.append("mat4 emr_modelView() { return ").append(mv).append("; }\n");
		b.append("mat3 emr_normalMatrix() { return mat3(transpose(inverse(mat3(").append(mvRaw).append(")))); }\n");
		b.append("vec4 emr_color() { return ").append(color).append("; }\n");
		b.append("vec2 emr_uv0() { return ").append(uv).append("; }\n");
		b.append("vec2 emr_light() { return ").append(light).append("; }\n");
		b.append("vec3 emr_normal() { return ").append(has.contains("Normal") ? in.apply("Normal") : "vec3(0.0, 0.0, 1.0)").append("; }\n");
		b.append("vec4 emr_tangent() { return ").append(has.contains("at_tangent") ? "emr_in_at_tangent" : "vec4(0.0, 0.0, 0.0, 1.0)").append("; }\n");
		b.append("int emr_blockId() { return ").append(has.contains("mc_Entity") ? "emr_in_mc_Entity.x" : "-1").append("; }\n");
		b.append("vec2 emr_midUV() { return ").append(has.contains("mc_midTexCoord") ? "emr_in_mc_midTexCoord" : "vec2(0.0)").append("; }\n");
		b.append("vec4 emr_midBlock() { return ").append(has.contains("at_midBlock") ? in.apply("at_midBlock") : "vec4(0.0)").append("; }\n");
		b.append("ivec3 emr_entityIds() { return ").append(ids).append("; }\n");
		if (has.contains("UV1")) b.append("vec4 emr_entityColorVS() { vec4 o = texelFetch(emr_Sampler1, emr_in_UV1, 0); vec4 c = vec4(o.rgb, 1.0 - o.a);"
				+ " c.rgb *= float(c.a != 0.0); return c; }\n");
		else b.append("vec4 emr_entityColorVS() { return emr_draw.entityColor; }\n");
		return b.toString();
	}
}
