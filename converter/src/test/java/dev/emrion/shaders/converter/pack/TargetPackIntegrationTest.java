package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the real target pack (Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5) with its settings file and checks
 * the stages and options it uses. Skipped when testpacks/ is absent.
 */
class TargetPackIntegrationTest {
	private static final Path FALLBACK = Path.of(System.getProperty("emrion.testpacks", "../testpacks"));
	private static ResolvedPack pack;
	private static Diagnostics diags;
	private static Path testpacks;

	@BeforeAll
	static void load() throws Exception {
		String prop = System.getProperty("emrion.testpacks");
		testpacks = prop != null && Files.isDirectory(Path.of(prop, "unbound-euphoria")) ? Path.of(prop) : FALLBACK;
		if (!Files.isDirectory(testpacks.resolve("unbound-euphoria"))) return;
		diags = new Diagnostics();
		long t = System.nanoTime();
		pack = new DefaultPackLoader().load(PackSource.open(testpacks.resolve("unbound-euphoria")),
				testpacks.resolve("ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5.txt"),
				StandardMacros.build(StandardMacros.Environment.DEFAULT), diags);
		System.out.printf("target pack loaded in %d ms, %d diagnostics%n", (System.nanoTime() - t) / 1_000_000, diags.all().size());
	}

	private static List<ResolvedPack.ProgramSource> programs(String dim) {
		assumeTrue(pack != null, "testpacks/unbound-euphoria not present");
		return pack.dimensions().get(dim);
	}

	private static PreprocessedStage stage(String dim, String program, StageType type) {
		return programs(dim).stream().filter(p -> p.name().equals(program)).flatMap(p -> p.stages().stream())
				.filter(s -> s.stage() == type).findFirst().orElseThrow(() -> new AssertionError(program + "." + type));
	}

	@Test
	void noErrors() {
		assumeTrue(pack != null, "testpacks/unbound-euphoria not present");
		List<Diagnostic> errors = diags.all().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList();
		assertEquals(List.of(), errors);
		assertEquals(Set.of("world0", "world-1", "world1"), pack.dimensions().keySet());
	}

	@Test
	void enabledProgramsPerDimension() {
		Set<String> expected = new TreeSet<>(List.of("shadow", "shadowcomp", "deferred1", "final",
				"composite", "composite1", "composite2", "composite3", "composite4", "composite5", "composite6", "composite7"));
		for (String g : List.of("skybasic", "skytextured", "terrain", "block", "block_translucent", "entities", "entities_glowing",
				"entities_translucent", "hand", "hand_water", "water", "textured", "basic", "line", "beaconbeam", "spidereyes",
				"armor_glint", "damagedblock", "lightning", "weather", "clouds")) expected.add("gbuffers_" + g);
		assertEquals(33, expected.size());
		for (String dim : List.of("world0", "world-1", "world1")) {
			Set<String> enabled = new TreeSet<>(), disabled = new TreeSet<>();
			for (var p : programs(dim)) (p.enabled() ? enabled : disabled).add(p.name());
			assertEquals(expected, enabled, dim);
			assertEquals(dim.equals("world-1") ? Set.of("composite8", "composite9") : Set.of("prepare", "composite8", "composite9"), disabled, dim);
		}
	}

	@Test
	void drawBuffersMatchFeatureTable() {
		Map<String, List<Integer>> table = Map.ofEntries(
				Map.entry("shadow", List.of(0, 1)), Map.entry("gbuffers_skybasic", List.of(0)),
				Map.entry("gbuffers_skytextured", List.of(0)), Map.entry("gbuffers_terrain", List.of(0, 6, 4)),
				Map.entry("gbuffers_block", List.of(0, 3, 6, 4)), Map.entry("gbuffers_entities", List.of(0, 3, 6, 4)),
				Map.entry("gbuffers_entities_glowing", List.of(0, 3, 6, 4)), Map.entry("gbuffers_beaconbeam", List.of(0, 6)),
				Map.entry("gbuffers_spidereyes", List.of(0, 6)), Map.entry("gbuffers_armor_glint", List.of(0, 6)),
				Map.entry("gbuffers_basic", List.of(0, 6, 4)), Map.entry("gbuffers_line", List.of(0, 6, 4)),
				Map.entry("gbuffers_lightning", List.of(0, 6, 4)), Map.entry("gbuffers_damagedblock", List.of(0)),
				Map.entry("deferred1", List.of(0, 5, 4)), Map.entry("gbuffers_water", List.of(0, 3, 6, 4, 8)),
				Map.entry("gbuffers_block_translucent", List.of(0, 3, 6, 4)),
				Map.entry("gbuffers_entities_translucent", List.of(0, 3, 6, 4, 13)),
				Map.entry("gbuffers_textured", List.of(0, 6, 3, 4)), Map.entry("gbuffers_weather", List.of(12)),
				Map.entry("gbuffers_hand", List.of(0, 3, 6, 4)), Map.entry("gbuffers_hand_water", List.of(0, 3, 6, 4)),
				Map.entry("composite", List.of(7, 1)), Map.entry("composite1", List.of(0, 5)), Map.entry("composite2", List.of(0)),
				Map.entry("composite3", List.of(0)), Map.entry("composite4", List.of(3)), Map.entry("composite5", List.of(3, 5)),
				Map.entry("composite6", List.of(3, 2)), Map.entry("composite7", List.of(3)), Map.entry("final", List.of(0)));
		List<String> wrong = new ArrayList<>();
		table.forEach((program, buffers) -> {
			List<Integer> actual = stage("world0", program, StageType.FRAGMENT).directives().drawBuffers();
			if (!buffers.equals(actual)) wrong.add(program + ": " + actual + " != " + buffers);
		});
		assertEquals(List.of(), wrong);
		assertTrue(stage("world0", "gbuffers_entities_translucent", StageType.FRAGMENT).directives().renderTargetsSyntax());
	}

	@Test
	void colortexFormatsClearsAndShadowSettings() {
		for (String dim : List.of("world0", "world-1", "world1")) {
			Map<String, String> c = PackConsts.merge(programs(dim));
			Map<Integer, String> formats = Map.ofEntries(Map.entry(0, "R11F_G11F_B10F"), Map.entry(1, "RGBA8_SNORM"),
					Map.entry(2, "RGBA16F"), Map.entry(3, "RGBA8"), Map.entry(4, "RGBA8_SNORM"), Map.entry(5, "RGBA8"),
					Map.entry(6, "RGBA8"), Map.entry(7, "RGBA16F"), Map.entry(8, "RGBA16F"), Map.entry(12, "RGBA8"),
					Map.entry(13, "RGBA8"));
			formats.forEach((i, f) -> assertEquals(f, c.get("colortex" + i + "Format"), dim + " colortex" + i));
			for (int i : List.of(1, 2, 4, 5, 7)) assertEquals("false", c.get("colortex" + i + "Clear"), dim + " colortex" + i);
			for (int i : List.of(0, 3, 6, 8, 12, 13)) assertNotEquals("false", c.get("colortex" + i + "Clear"), dim + " colortex" + i);
			assertEquals("2048", c.get("shadowMapResolution"), dim);
			assertEquals("256.0", c.get("shadowDistance"), dim);
			assertArrayEquals(new int[]{64, 32, 64}, stage(dim, "shadowcomp", StageType.COMPUTE).directives().workGroups(), dim);
			assertArrayEquals(new int[]{8, 8, 8}, stage(dim, "shadowcomp", StageType.COMPUTE).directives().localSize(), dim);
		}
	}

	@Test
	void matchesGlslangPreprocessorOutput() throws Exception {
		Path ref = testpacks.resolve("preprocessed-glslang");
		assumeTrue(pack != null && Files.isDirectory(ref), "reference output not present");
		int compared = 0;
		for (String name : List.of("composite1.fsh", "composite.fsh", "gbuffers_terrain.vsh", "gbuffers_terrain.fsh",
				"gbuffers_water.fsh", "deferred1.fsh", "shadow.vsh", "shadowcomp.csh", "final.fsh")) {
			Path file = ref.resolve("world0_" + name + ".pp.glsl");
			if (!Files.exists(file)) continue;
			String program = name.substring(0, name.indexOf('.'));
			StageType type = StageType.valueOf(switch (name.substring(name.indexOf('.') + 1)) {
				case "vsh" -> "VERTEX";
				case "fsh" -> "FRAGMENT";
				default -> "COMPUTE";
			});
			List<String> expected = tokens(Files.readString(file).lines()
					.filter(l -> !l.startsWith("#line") && !l.contains("GL_GOOGLE_cpp_style_line_directive")).toList());
			List<String> actual = tokens(stage("world0", program, type).text().lines().toList());
			assertEquals(expected, actual, name);
			compared++;
		}
		assertTrue(compared >= 5, "compared " + compared);
	}

	/** Tokens of non-directive code plus #version/#extension lines (glslang leaves them in place, Iris hoists them). */
	private static List<String> tokens(List<String> lines) {
		List<String> directives = new ArrayList<>(), code = new ArrayList<>();
		Pattern tok = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[0-9.][A-Za-z0-9_.]*|\\S");
		for (String l : lines) {
			if (l.trim().startsWith("#")) {
				directives.add(l.trim().replaceAll("\\s+", " "));
				continue;
			}
			Matcher m = tok.matcher(l);
			while (m.find()) code.add(m.group());
		}
		List<String> out = new ArrayList<>(directives);
		out.addAll(code);
		return out;
	}
}
