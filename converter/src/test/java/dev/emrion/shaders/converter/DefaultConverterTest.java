package dev.emrion.shaders.converter;

import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.PackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.plan.DimensionPlanner;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.converter.spirv.SpirvCompiler;
import dev.emrion.shaders.converter.spirv.TestShaders;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConverterTest {
	static final ShadercSpirvCompiler SHADERC = new ShadercSpirvCompiler();
	static final AtomicInteger COMPILES = new AtomicInteger();
	static final SpirvCompiler COUNTING = new SpirvCompiler() {
		public Result compile(String name, String glsl, StageType stage, LineMap lineMap) {
			COMPILES.incrementAndGet();
			return SHADERC.compile(name, glsl, stage, lineMap);
		}

		public void close() {}
	};

	@AfterAll
	static void close() { SHADERC.close(); }

	static final String COMPOSITE_VSH = """
			#version 450
			layout(location = 0) out vec2 texcoord;
			void main() {
			    texcoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
			    gl_Position = vec4(texcoord * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	static final String COMPOSITE_FSH = """
			#version 450
			layout(set = 1, binding = 2) uniform sampler2D colortex0;
			layout(location = 0) in vec2 texcoord;
			layout(location = 0) out vec4 outColor0;
			void main() { outColor0 = texture(colortex0, texcoord); }
			""";

	@TempDir Path out;
	Path settings;
	boolean includeBroken;

	static PreprocessedStage stage(String program, ProgramGroup group, StageType type, String text) {
		return new PreprocessedStage(program, group, type, "shaders/world0/" + program + "." + type.extension, text, LineMap.IDENTITY_UNKNOWN, null);
	}

	ResolvedPack.ProgramSource composite() {
		return new ResolvedPack.ProgramSource("composite", true, List.of(
				stage("composite", ProgramGroup.COMPOSITE, StageType.VERTEX, COMPOSITE_VSH),
				stage("composite", ProgramGroup.COMPOSITE, StageType.FRAGMENT, COMPOSITE_FSH)));
	}

	ResolvedPack.ProgramSource gbuffers() {
		return new ResolvedPack.ProgramSource("gbuffers_basic", true, List.of(
				stage("gbuffers_basic", ProgramGroup.GBUFFERS, StageType.VERTEX, TestShaders.VERTEX),
				stage("gbuffers_basic", ProgramGroup.GBUFFERS, StageType.FRAGMENT, TestShaders.FRAGMENT)));
	}

	ResolvedPack.ProgramSource broken() {
		return new ResolvedPack.ProgramSource("composite1", true, List.of(
				stage("composite1", ProgramGroup.COMPOSITE, StageType.VERTEX, COMPOSITE_VSH),
				new PreprocessedStage("composite1", ProgramGroup.COMPOSITE, StageType.FRAGMENT, "shaders/world0/composite1.fsh",
						"#version 450\nvoid main() { nope(); }\n", brokenLines, null)));
	}

	LineMap brokenLines = LineMap.IDENTITY_UNKNOWN;

	final PackLoader loader = (source, settingsFile, macros, diagnostics) -> {
		var programs = new ArrayList<>(List.of(composite(), gbuffers(),
				new ResolvedPack.ProgramSource("final", false, List.of())));
		if (includeBroken) programs.add(broken());
		return new ResolvedPack(source, new Manifest.Options(null, Map.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of()),
				new LinkedHashMap<>(), new Manifest.IdMaps(List.of(), List.of(), List.of(), Map.of()),
				List.of(new Manifest.DimensionMapping("*", "world0")), Map.of("world0", programs));
	};

	/** Identity transpiler: the "preprocessed" text already is Vulkan GLSL. */
	static final Transpiler TRANSPILER = new Transpiler() {
		public ProgramAnalysis analyze(List<PreprocessedStage> stages) {
			var first = stages.getFirst();
			return new ProgramAnalysis(first.program(), first.group(), stages, List.of(), List.of(), List.of(), List.of(),
					List.of(), List.of(), List.of(), List.of());
		}

		public EmitResult emit(ProgramAnalysis analysis, EmitContext context, String vertexVariant) {
			Map<StageType, String> glsl = new LinkedHashMap<>();
			analysis.stages().forEach(s -> glsl.put(s.stage(), s.text()));
			return new EmitResult(glsl, List.of());
		}
	};

	static final DimensionPlanner PLANNER = (dimension, programs, analyses, properties, optionValues, uniforms, diagnostics) -> {
		Map<String, Manifest.Program> table = new LinkedHashMap<>();
		Map<String, DimensionPlanner.ProgramEmit> emits = new LinkedHashMap<>();
		List<Manifest.Command> frame = new ArrayList<>();
		for (String name : analyses.keySet()) {
			boolean geometry = analyses.get(name).group().isGeometry();
			table.put(name, new Manifest.Program(geometry ? "gbuffers" : "composite", geometry ? "gbuffers" : "composite", List.of(),
					null, List.of(new Manifest.Output(0, "colortex0")), null, null, List.of(), List.of(2), List.of(), geometry,
					null, null, null, List.of(), 1.0f, null));
			emits.put(name, new DimensionPlanner.ProgramEmit(null, geometry ? List.of("position_color", "position") : List.of("fullscreen")));
			frame.add(new Manifest.Command(geometry ? "geometry" : "fullscreen", geometry ? null : name, 0, geometry ? "opaque" : null,
					null, null, null, null, null, null));
		}
		var dim = new Manifest.Dimension(table, List.of(), List.of(), List.of(new Manifest.FlipState(0, List.of())), frame, List.of(), null);
		var textures = List.of(new Manifest.TextureDef("noisetex", "shaders/lib/textures/noise.png", null, "RGBA8", 2, new int[]{1, 1}, "linear", "repeat", false, null));
		return new DimensionPlanner.DimensionPlan(dim, emits, textures);
	};

	static final class MemoryPack implements PackSource {
		final Map<String, byte[]> files = Map.of("shaders/lib/textures/noise.png", new byte[]{1, 2, 3});

		public String name() { return "fake"; }
		public Optional<byte[]> read(String path) { return Optional.ofNullable(files.get(path)); }
		public Optional<String> readString(String path) { return read(path).map(String::new); }
		public boolean exists(String path) { return files.containsKey(path); }
		public List<String> list() { return List.copyOf(files.keySet()); }
		public String contentHash() { return "fakehash"; }
		public void close() {}
	}

	DefaultConverter converter() {
		return new DefaultConverter(p -> new MemoryPack(), loader, (props, d) -> List.of(), TRANSPILER,
				(analyses, custom, d) -> new Manifest.UniformLayout("EmrionUniforms", 0, List.of(), custom, null, null),
				PLANNER, COUNTING, new Manifest.Environment("26.3", Map.of("MC_VERSION", "260300")), 4);
	}

	Converter.Result run(boolean useCache, List<String> stages) throws IOException {
		if (settings == null) settings = Files.writeString(out.resolve("settings.txt"), "SHADOW_QUALITY=3\n");
		return converter().convert(new Converter.Request(Path.of("fake.zip"), settings, out.resolve("cache"), null,
				(stage, done, total) -> { if (stages != null) stages.add(stage); }, useCache));
	}

	@Test
	void convertsFakePackToValidCacheDir() throws Exception {
		List<String> stages = Collections.synchronizedList(new ArrayList<>());
		var r = run(true, stages);
		assertEquals(List.of(), r.diagnostics());
		assertFalse(r.cacheHit());
		assertEquals(5, r.stagesCompiled()); // composite vsh+fsh, gbuffers_basic 2 vertex variants + fsh
		assertEquals(0, r.stagesFailed());
		assertEquals(List.of("load", "analyse", "plan", "compile", "write"), stages.stream().distinct().toList());

		var programs = r.manifest().dimensions().get("world0").programs();
		assertEquals(Map.of("fullscreen", "spv/world0/composite.vsh.spv"), programs.get("composite").stages().vertex().variants());
		assertEquals("spv/world0/composite.fsh.spv", programs.get("composite").stages().fragment().spv());
		assertEquals(Map.of("position", "spv/world0/gbuffers_basic.vsh.position.spv", "position_color", "spv/world0/gbuffers_basic.vsh.position_color.spv"),
				programs.get("gbuffers_basic").stages().vertex().variants());
		assertNull(programs.get("gbuffers_basic").stages().geometry());

		Path dir = r.outputDir();
		assertEquals(out.resolve("cache").resolve(r.manifest().cacheKey()), dir);
		assertTrue(Files.isRegularFile(dir.resolve("manifest.json")));
		assertTrue(Files.readString(dir.resolve("report.json")).contains("\"ok\": true"));
		assertTrue(Files.isRegularFile(dir.resolve("src/world0/gbuffers_basic.vsh.position.glsl")));
		assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(dir.resolve("textures/noisetex.png")));
		assertEquals("textures/noisetex.png", r.manifest().textures().getFirst().file());
		try (Stream<Path> spv = Files.walk(dir.resolve("spv"))) {
			for (Path p : spv.filter(p -> p.toString().endsWith(".spv")).toList()) TestShaders.validateIfAvailable(p);
		}
		try (Stream<Path> tmp = Files.list(out.resolve("cache"))) {
			assertTrue(tmp.noneMatch(p -> p.toString().endsWith(".tmp")));
		}
	}

	@Test
	void cacheHitCompilesNothingAndNoCacheIsByteIdentical() throws Exception {
		var first = run(true, null);
		byte[] manifest = Files.readAllBytes(first.outputDir().resolve("manifest.json"));
		byte[] spv = Files.readAllBytes(first.outputDir().resolve("spv/world0/gbuffers_basic.fsh.spv"));

		int before = COMPILES.get();
		var hit = run(true, null);
		assertTrue(hit.cacheHit());
		assertEquals(before, COMPILES.get());
		assertEquals(5, hit.stagesCompiled());
		assertEquals(first.manifest().cacheKey(), hit.manifest().cacheKey());

		var again = run(false, null);
		assertFalse(again.cacheHit());
		assertEquals(before + 5, COMPILES.get());
		assertArrayEquals(manifest, Files.readAllBytes(again.outputDir().resolve("manifest.json")));
		assertArrayEquals(spv, Files.readAllBytes(again.outputDir().resolve("spv/world0/gbuffers_basic.fsh.spv")));

		Files.writeString(settings, "SHADOW_QUALITY=2\n");
		assertFalse(run(true, null).cacheHit(), "settings change → new key");
	}

	@Test
	void compileErrorsPointAtThePackFile() throws Exception {
		includeBroken = true;
		brokenLines = new LineMap() {
			public String file(int outputLine) { return "shaders/program/composite1.glsl"; }
			public int line(int outputLine) { return outputLine + 40; }
		};
		Diagnostic error = run(true, null).diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow();
		assertEquals("shaders/program/composite1.glsl", error.file());
		assertEquals(42, error.line());
	}

	@Test
	void cacheWithAMissingSpvIsConvertedAgain() throws Exception {
		var first = run(true, null);
		Files.delete(first.outputDir().resolve("spv/world0/gbuffers_basic.fsh.spv"));
		var again = run(true, null);
		assertFalse(again.cacheHit());
		assertTrue(Files.isRegularFile(again.outputDir().resolve("spv/world0/gbuffers_basic.fsh.spv")));
	}

	@Test
	void failedStageRemovesOnlyItsProgram() throws Exception {
		includeBroken = true;
		var r = run(true, null);
		assertEquals(1, r.stagesFailed());
		assertEquals(6, r.stagesCompiled());
		var dim = r.manifest().dimensions().get("world0");
		assertFalse(dim.programs().containsKey("composite1"));
		assertTrue(dim.programs().containsKey("composite"));
		assertTrue(dim.frame().stream().noneMatch(c -> "composite1".equals(c.program())));
		assertTrue(dim.frame().stream().anyMatch(c -> "composite".equals(c.program())));

		Diagnostic error = r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow();
		assertEquals("composite1", error.program());
		assertEquals("fragment", error.stage());
		assertEquals("src/world0/composite1.fsh.glsl", error.file());
		assertEquals(2, error.line());
		assertTrue(Files.readString(r.outputDir().resolve("report.json")).contains("\"ok\": false"));
	}

	@Test
	void unreadablePackGivesNoManifest() {
		var c = new DefaultConverter(p -> { throw new IOException("no such file"); }, loader, (props, d) -> List.of(), TRANSPILER,
				(a, cu, d) -> null, PLANNER, COUNTING, new Manifest.Environment("26.3", Map.of()), 1);
		var r = c.convert(new Converter.Request(Path.of("missing.zip"), null, out, null, null));
		assertNull(r.manifest());
		assertEquals(Diagnostic.Severity.ERROR, r.diagnostics().getFirst().severity());
	}
}
