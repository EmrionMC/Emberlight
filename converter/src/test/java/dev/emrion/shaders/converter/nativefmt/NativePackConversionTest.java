package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.DefaultConverter;
import dev.emrion.shaders.converter.expr.CustomUniformParser;
import dev.emrion.shaders.converter.glsl.GlslTranspiler;
import dev.emrion.shaders.converter.pack.DefaultPackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.StandardMacros;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Native packs through the converter facade: compiled directly (NativeCompiler), no Iris-layout pack in between. */
class NativePackConversionTest {
	static final Path TEMPLATE = Path.of(System.getProperty("emberlight.packs", "../packs"), "template");
	static final ShadercSpirvCompiler SHADERC = new ShadercSpirvCompiler();

	@AfterAll
	static void close() { SHADERC.close(); }

	@TempDir Path out;

	Converter.Result convert(Path pack, Set<String> request) {
		var env = StandardMacros.Environment.DEFAULT;
		var c = new DefaultConverter(PackSource::open, new DefaultPackLoader(), CustomUniformParser::parse, new GlslTranspiler(),
				new Std140LayoutBuilder(), new IrisDimensionPlanner(), SHADERC, new Manifest.Environment(env.mcVersion(), StandardMacros.build(env)), 4);
		return c.convert(new Converter.Request(pack, null, out, null, Converter.ProgressListener.NONE, false, request));
	}

	@Test
	void templateCompilesWithoutErrors() {
		var r = convert(TEMPLATE, Set.of());
		var errors = r.diagnostics().stream().filter(d -> d.severity() != Diagnostic.Severity.INFO).toList();
		assertEquals(List.of(), errors);
		assertEquals(0, r.stagesFailed());
		Manifest m = r.manifest();
		assertEquals(List.of(NativeCompiler.RT_EFFECT), m.rt().effects(), "ray_query packs get the TLAS");

		Manifest.Dimension main = m.dimensions().get("default");
		List<String> order = main.frame().stream().map(c -> c.cmd() + ":" + (c.program() != null ? c.program() : c.phase())).toList();
		assertEquals(List.of("clear:null", "clear:null", "geometry:sky", "geometry:opaque", "geometry:hand_solid", "compute:rtshadow",
				"fullscreen:lighting", "geometry:translucent", "geometry:hand_translucent", "fullscreen:tonemap", "final:output"), order);
		// geometry: one program per class, serving the runtime's geometry slot; classes falling back to a pass are drawn by it
		assertEquals(List.of("gbuffers_terrain_solid"), main.programs().get("gbuffer.terrain_solid").fallbackFor());
		assertEquals(List.of("gbuffers_skybasic"), main.programs().get("basic.sky").fallbackFor(), "sky falls back to basic (§5.1)");
		assertTrue(main.flipStates().size() == 1, "nothing samples what it writes: no ping-pong, no alt images");
		// el_depthOpaque/el_depthNoHand are not read: no depthtex1/2 and no depth copies
		assertTrue(main.targets().stream().noneMatch(t -> t.name().equals("depthtex1")));
		// screen images from colortex4 up, with the declared formats
		Map<String, String> formats = new TreeMap<>();
		main.targets().forEach(t -> formats.put(t.name(), t.format()));
		assertEquals("RGBA8", formats.get("colortex4"));
		assertEquals("RG16", formats.get("colortex5"));
		assertEquals("R8", formats.get("colortex7"));
		assertEquals("RGBA16F", formats.get("colortex8"));
		// the rtshadow pass is the one with the acceleration structure
		assertTrue(main.resources().stream().anyMatch(res -> "emrion_tlas".equals(res.builtin())));
		// Nether override: the shadow pass is disabled there
		assertTrue(m.dimensions().get("nether").frame().stream().noneMatch(c -> "compute".equals(c.cmd())));
		assertTrue(m.dimensionMap().contains(new Manifest.DimensionMapping("minecraft:the_nether", "nether")));

		// options: colour as three sliders, enum as an index; profiles and pages
		var o = m.options();
		assertEquals("0", o.values().get("TONEMAP"));
		assertEquals("0.9", o.values().get("SUN_COLOR_G"));
		assertTrue(o.declared().stream().noneMatch(d -> d.name().startsWith("EL_")), "no generated macro may become an option");
		assertEquals(List.of("EXPOSURE", "TONEMAP", "FOG", "SUN_COLOR_R", "SUN_COLOR_G", "SUN_COLOR_B"), o.screens().get("image"));
		assertEquals("4", o.profiles().get("high").get("SHADOW_RAYS"));
		// every frame member of the header is in the block, also the ones no shader reads (stable layout)
		assertTrue(m.uniforms().members().stream().anyMatch(u -> u.name().equals("isSpectator")));
	}

	/** packs/emberlight-rtx: every stage of every dimension and every quality profile compiles. */
	@Test
	void rtxPackCompilesWithoutErrors() {
		Path rtx = TEMPLATE.resolveSibling("emberlight-rtx");
		for (Set<String> request : List.of(Set.<String>of(), Set.of("upscale"), Set.of("upscale", "rr"))) {
			var r = convert(rtx, request);
			var errors = r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
			assertEquals(List.of(), errors);
			assertEquals(0, r.stagesFailed());
			assertEquals(Set.of("default", "nether", "end"), r.manifest().dimensions().keySet());
			assertEquals(List.of("low", "medium", "high", "ultra", "insane"), List.copyOf(r.manifest().options().profiles().keySet()));
		}
	}

	@Test
	void sameFrameLayoutForEveryNativePack() {
		var a = convert(TEMPLATE, Set.of()).manifest().uniforms().members().stream().filter(u -> !u.source().equals("custom")).toList();
		var frame = NativeCompiler.frameAnalysis(List.of()).uniforms();
		assertTrue(frame.size() > 60);
		for (var u : frame) assertTrue(a.stream().anyMatch(x -> x.name().equals(u.name())), u.name());
	}

	@Test
	void upscalingRequestDefinesTheMacroAndRtRequestsAreUnavailable() throws Exception {
		var r = convert(TEMPLATE, Set.of("upscale", "shadows"));
		assertTrue(r.manifest().rt().effects().contains("upscale"));
		assertEquals(Set.of("shadows"), r.manifest().rt().unavailable().keySet());
		assertTrue(Files.readString(r.outputDir().resolve("src/default/tonemap.fsh.glsl")).contains("#define EL_UPSCALING 1"));
		try (PackSource src = PackSource.open(TEMPLATE)) {
			assertNotNull(NativeCompiler.lang(src).get("de_de").get("value.TONEMAP.2"), "enum value keys become indices");
		}
	}

	@Test
	void manifestErrorsStopBeforeCompiling(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), "{\n  \"format\": 1, \"id\": \"x\", \"name\": \"X\", \"version\": \"1\",\n  \"passes\": [ { \"name\": \"out\", \"kind\": \"final\", \"fragment\": \"shaders/nope.glsl\" } ]\n}\n");
		var r = convert(pack, Set.of());
		assertNull(r.manifest());
		assertTrue(r.diagnostics().stream().anyMatch(d -> d.file().equals("emberlight.json") && d.line() == 3 && d.message().contains("shaders/nope.glsl")), r.diagnostics().toString());
	}

	@Test
	void shaderErrorsPointAtThePackFile(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1",
				  "passes": [ { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() {\n\tc = vec4(el_nope);\n}\n");
		var r = convert(pack, Set.of());
		assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR && "shaders/final.frag.glsl".equals(d.file()) && d.line() == 3),
				r.diagnostics().toString());
	}

	@Test
	void includedFileErrorsPointAtTheIncludedFile(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders/lib"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1",
				  "passes": [ { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/lib/a.glsl"), "// helper\nfloat helper() {\n\treturn nope;\n}\n");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "#include \"/lib/a.glsl\"\nlayout(location = 0) out vec4 c;\nvoid main() { c = vec4(helper()); }\n");
		var r = convert(pack, Set.of());
		assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR && "shaders/lib/a.glsl".equals(d.file()) && d.line() == 3
				&& "out".equals(d.program())), r.diagnostics().toString());
	}

	/** Varyings match by name, not order; a pass that samples what it writes ping-pongs; history survives the frame. */
	@Test
	void varyingsByNameAndPingPong(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1",
				  "images": { "col": { "format": "RGBA16F" }, "acc": { "format": "RGBA16F", "history": true } },
				  "uniforms": { "pulse": { "type": "float", "expr": "sin(frameTimeCounter)" } },
				  "passes": [
				    { "name": "geo", "kind": "geometry", "classes": ["basic"], "vertex": "shaders/g.vert.glsl", "fragment": "shaders/g.frag.glsl", "writes": ["col"] },
				    { "name": "taa", "kind": "fullscreen", "phase": "composite", "fragment": "shaders/taa.frag.glsl", "writes": ["acc"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/g.vert.glsl"), "out vec4 a;\nout vec2 b;\nvoid main() { a = el_vertexColor(); b = vec2(0.0); gl_Position = el_project(el_vertexViewPosition()); }\n");
		Files.writeString(pack.resolve("shaders/g.frag.glsl"), "in vec2 b;\nin vec4 a;\nuniform float pulse;\nlayout(location = EL_OUT_col) out vec4 o;\nvoid main() { o = a * pulse + vec4(b, 0.0, 0.0); }\n");
		Files.writeString(pack.resolve("shaders/taa.frag.glsl"), "layout(location = EL_OUT_acc) out vec4 o;\nvoid main() { o = mix(texture(acc, el_uv), texture(col, el_uv), 0.1); }\n");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "in vec2 el_uv;\nlayout(location = 0) out vec4 c;\nvoid main() { c = texture(acc, el_uv); }\n");
		var r = convert(pack, Set.of());
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		String vs = Files.readString(r.outputDir().resolve("src/default/geo.basic.vsh.position_color.glsl"));
		String fs = Files.readString(r.outputDir().resolve("src/default/geo.basic.fsh.glsl"));
		assertTrue(vs.contains("layout(location = 0) out vec4 a;") && vs.contains("layout(location = 1) out vec2 b;"), vs);
		assertTrue(fs.contains("layout(location = 1) in vec2 b;") && fs.contains("layout(location = 0) in vec4 a;"), fs);
		var d = r.manifest().dimensions().get("default");
		var taa = d.frame().stream().filter(c -> "taa".equals(c.program())).findFirst().orElseThrow();
		assertEquals("alt", taa.writes().getFirst().side(), "taa samples acc: it writes the other side");
		var fin = d.frame().stream().filter(c -> "final".equals(c.cmd())).findFirst().orElseThrow();
		int accN = Integer.parseInt(taa.writes().getFirst().target().substring(8));
		assertTrue(d.flipStates().get(fin.flipState()).alt().contains(accN), "final reads the new value");
		assertEquals("copy", d.frame().getLast().cmd(), "history: the latest value goes back to main for the next frame");
		assertTrue(d.targets().stream().anyMatch(t -> t.name().equals("colortex" + accN) && t.hasAlt() && !t.clear()));
	}

	/** Integer raw textures get integer samplers; explicit and symbolic-size varyings never share a location. */
	@Test
	void integerTexturesAndVaryingLocations(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.write(pack.resolve("data.bin"), new byte[16]);
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1",
				  "images": { "col": { "format": "RGBA16F" } },
				  "textures": { "data": { "file": "data.bin", "raw": { "format": "R32UI", "size": [2, 2], "pixel": "RED_INTEGER UNSIGNED_INT" } } },
				  "passes": [
				    { "name": "geo", "kind": "geometry", "classes": ["basic"], "vertex": "shaders/g.vert.glsl", "fragment": "shaders/g.frag.glsl", "writes": ["col"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/g.vert.glsl"), """
				#define N 2
				out vec4 a[N];
				layout(location = 1) out vec4 e;
				out vec4 b;
				void main() { a[0] = a[1] = e = b = vec4(texelFetch(data, ivec2(0), 0).r); gl_Position = el_project(el_vertexViewPosition()); }
				""");
		Files.writeString(pack.resolve("shaders/g.frag.glsl"), """
				#define N 2
				in vec4 a[N];
				layout(location = 1) in vec4 e;
				in vec4 b;
				layout(location = EL_OUT_col) out vec4 o;
				void main() { o = a[0] + a[1] + e + b; }
				""");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() { c = texture(col, el_uv); }\n");
		var r = convert(pack, Set.of());
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		String vs = Files.readString(r.outputDir().resolve("src/default/geo.basic.vsh.position_color.glsl"));
		assertTrue(vs.contains("uniform usampler2D data;"), vs);
		// el_uv is not a geometry varying: a takes 0-1 except the explicit 1 -> a at 2-3, b at 0
		assertTrue(vs.contains("layout(location = 2) out vec4 a[N];") && vs.contains("layout(location = 0) out vec4 b;"), vs);
		assertEquals("nearest", r.manifest().textures().stream().filter(t -> t.name().equals("data")).findFirst().orElseThrow().filter(),
				"integer formats are never filtered linearly");
	}

	/**
	 * DLSS Ray Reconstruction (§14.4): with "rr" requested the guide pass compiles, EL_RR_ACTIVE holds in defines and
	 * conditions and the el_rr* images become targets; without it the guide pass is gone and "!EL_RR_ACTIVE" passes run.
	 */
	@Test
	void rayReconstructionGuides(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1", "requires": ["ray_query"], "settings": { "rayReconstruction": %s },
				  "images": { "col": { "format": "RGBA16F" } },
				  "passes": [
				    { "name": "rt", "kind": "compute", "phase": "deferred", "raytracing": true, "compute": "shaders/rt.comp.glsl", "writes": ["col"] },
				    { "name": "denoise", "kind": "compute", "phase": "deferred", "enabled": "!EL_RR_ACTIVE", "compute": "shaders/rt.comp.glsl", "writes": ["col"] },
				    { "name": "guides", "kind": "compute", "phase": "composite", "compute": "shaders/guides.comp.glsl",
				      "writes": ["el_rrDiffuse", "el_rrSpecular", "el_rrNormal"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""".formatted("true"));
		Files.writeString(pack.resolve("shaders/rt.comp.glsl"), "void main() { imageStore(col_img, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }\n");
		Files.writeString(pack.resolve("shaders/guides.comp.glsl"), """
				void main() {
					ivec2 p = ivec2(gl_GlobalInvocationID.xy);
					imageStore(el_rrDiffuse_img, p, texelFetch(col, p, 0));
					imageStore(el_rrSpecular_img, p, vec4(0.04));
					imageStore(el_rrNormal_img, p, vec4(0.0, 1.0, 0.0, 0.5));
				}
				""");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() { c = texture(col, el_uv); }\n");

		var on = convert(pack, Set.of("upscale", "rr"));
		assertEquals(List.of(), on.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertTrue(on.manifest().rt().effects().contains("rr"));
		var dim = on.manifest().dimensions().get("default");
		assertTrue(dim.programs().containsKey("guides") && !dim.programs().containsKey("denoise"));
		for (String g : NativeManifest.RR_IMAGES)
			assertTrue(dim.targets().stream().anyMatch(t -> t.name().equals(g) && t.kind().equals("image") && t.format().equals("RGBA16F")), g);
		assertTrue(Files.readString(on.outputDir().resolve("src/default/guides.csh.glsl")).contains("#define EL_RR_ACTIVE 1"));

		var off = convert(pack, Set.of("upscale"));
		assertEquals(List.of(), off.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		var offDim = off.manifest().dimensions().get("default");
		assertTrue(!offDim.programs().containsKey("guides") && offDim.programs().containsKey("denoise"));
		assertTrue(offDim.targets().stream().noneMatch(t -> t.name().startsWith("el_rr")));

		// a pack that does not declare support: RR requested but unavailable (the runtime runs DLSS SR)
		String json = Files.readString(pack.resolve("emberlight.json")).replace("\"rayReconstruction\": true", "\"rayReconstruction\": false");
		Files.writeString(pack.resolve("emberlight.json"), json.substring(0, json.indexOf("{ \"name\": \"guides\"")) + json.substring(json.indexOf("{ \"name\": \"out\"")));
		var unsupported = convert(pack, Set.of("upscale", "rr"));
		assertEquals(List.of(), unsupported.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertTrue(unsupported.manifest().rt().unavailable().containsKey("rr"));
		assertTrue(unsupported.manifest().dimensions().get("default").programs().containsKey("denoise"));
	}

	/** The TLAS is built only when an enabled pass traces rays. */
	@Test
	void noRtSceneWithoutEnabledRayPasses(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		String json = """
				{ "format": 1, "id": "x", "name": "X", "version": "1", "requires": ["ray_query"],
				  "images": { "vis": { "format": "R8" } },
				  "options": [ { "name": "RT", "type": "bool", "default": %s } ],
				  "passes": [
				    { "name": "rt", "kind": "compute", "phase": "deferred", "raytracing": true, "enabled": "RT", "compute": "shaders/rt.comp.glsl", "writes": ["vis"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""";
		Files.writeString(pack.resolve("shaders/rt.comp.glsl"), "void main() { imageStore(vis_img, ivec2(gl_GlobalInvocationID.xy), vec4(1.0)); }\n");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() { c = texture(vis, el_uv); }\n");
		Files.writeString(pack.resolve("emberlight.json"), json.formatted("false"));
		var off = convert(pack, Set.of());
		assertEquals(List.of(), off.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertNull(off.manifest().rt());
		Files.writeString(pack.resolve("emberlight.json"), json.formatted("true"));
		assertEquals(List.of(NativeCompiler.RT_EFFECT), convert(pack, Set.of()).manifest().rt().effects());
	}

	/** el_hitSpecular / el_hitNormalMap / el_traceSurface (§7.6): ray hits read the block atlas's LabPBR maps through their own builtins. */
	@Test
	void materialMapsAtRayHits(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1", "requires": ["ray_query"],
				  "images": { "vis": { "format": "RGBA16F" } },
				  "passes": [
				    { "name": "rt", "kind": "compute", "phase": "deferred", "raytracing": true, "compute": "shaders/rt.comp.glsl", "writes": ["vis"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/rt.comp.glsl"), """
				void main() {
					ElHit h = el_traceClosest(vec3(0.0), vec3(0.0, -1.0, 0.0), 16.0, EL_MASK_ALL);
					ElMaterial m = el_hitMaterial(h);
					ElHit glass = el_traceSurface(vec3(0.0), vec3(0.0, 1.0, 0.0), 16.0, EL_MASK_TRANSLUCENT); // header 3: no alpha test
					imageStore(vis_img, ivec2(gl_GlobalInvocationID.xy), vec4(m.emission, m.roughness + glass.t, el_hitNormalMap(h).xy));
				}
				""");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() { c = texture(vis, el_uv); }\n");
		var r = convert(pack, Set.of());
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		var builtins = r.manifest().dimensions().get("default").resources().stream().map(Manifest.Resource::builtin).toList();
		assertTrue(builtins.contains("block_atlas_specular") && builtins.contains("block_atlas_normals"), builtins.toString());
	}

	/** el_lightFromPrev (§7.6, header 4): last frame's light index through the remap table compiles in a ray-tracing pass. */
	@Test
	void lightRemapForTemporalReuse(@TempDir Path pack) throws Exception {
		Files.createDirectories(pack.resolve("shaders"));
		Files.writeString(pack.resolve("emberlight.json"), """
				{ "format": 1, "id": "x", "name": "X", "version": "1", "requires": ["ray_query"],
				  "images": { "vis": { "format": "RGBA16F" } },
				  "passes": [
				    { "name": "rt", "kind": "compute", "phase": "deferred", "raytracing": true, "compute": "shaders/rt.comp.glsl", "writes": ["vis"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/final.frag.glsl" } ] }
				""");
		Files.writeString(pack.resolve("shaders/rt.comp.glsl"), """
				#if EL_HEADER_VERSION < 4
				#error header too old
				#endif
				void main() {
					int i = el_lightFromPrev(int(gl_GlobalInvocationID.x));
					imageStore(vis_img, ivec2(gl_GlobalInvocationID.xy), vec4(i >= 0 ? el_light(i).position : vec3(0.0), 1.0));
				}
				""");
		Files.writeString(pack.resolve("shaders/final.frag.glsl"), "layout(location = 0) out vec4 c;\nvoid main() { c = texture(vis, el_uv); }\n");
		var r = convert(pack, Set.of());
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
	}
}
