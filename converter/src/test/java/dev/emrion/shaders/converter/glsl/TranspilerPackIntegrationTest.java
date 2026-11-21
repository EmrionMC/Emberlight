package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Transpiles every active program of the real target pack (glslang-preprocessed fixtures in
 * testpacks/preprocessed-glslang, git-ignored) in all needed vertex variants and validates each stage with
 * glslangValidator for Vulkan 1.2. Skipped when fixtures or the validator are absent.
 */
class TranspilerPackIntegrationTest {

	static Path fixtures() {
		String prop = System.getProperty("emrion.testpacks");
		String env = System.getenv("EMRION_TESTPACKS");
		for (String root : new String[] {env, prop}) {
			if (root == null) continue;
			Path p = Path.of(root, "preprocessed-glslang");
			if (Files.isDirectory(p)) return p;
		}
		return null;
	}

	static ProgramGroup group(String program) {
		if (program.equals("shadowcomp")) return ProgramGroup.SHADOWCOMP;
		if (program.startsWith("shadow")) return ProgramGroup.SHADOW;
		if (program.startsWith("gbuffers_")) return ProgramGroup.GBUFFERS;
		if (program.startsWith("deferred")) return ProgramGroup.DEFERRED;
		if (program.startsWith("composite")) return ProgramGroup.COMPOSITE;
		if (program.startsWith("prepare")) return ProgramGroup.PREPARE;
		return ProgramGroup.FINAL;
	}

	static List<String> variants(String program) {
		return switch (program) {
			case "shadow" -> List.of("terrain_sodium", "entity");
			case "gbuffers_terrain", "gbuffers_water" -> List.of("terrain_sodium");
			case "gbuffers_damagedblock" -> List.of("block");
			case "gbuffers_block", "gbuffers_block_translucent" -> List.of("entity");
			case "gbuffers_entities", "gbuffers_entities_glowing", "gbuffers_entities_translucent", "gbuffers_hand",
			     "gbuffers_hand_water", "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_beaconbeam" -> List.of("entity");
			case "gbuffers_skytextured" -> List.of("position_tex_color", "position_tex");
			case "gbuffers_skybasic", "gbuffers_lightning", "gbuffers_clouds" -> List.of("position_color", "position");
			case "gbuffers_basic" -> List.of("position_color", "lines");
			case "gbuffers_textured", "gbuffers_weather" -> List.of("particle", "position_color_tex_lightmap");
			case "gbuffers_line" -> List.of("lines");
			default -> List.of("fullscreen");
		};
	}

	static String load(Path f) throws Exception {
		StringBuilder sb = new StringBuilder();
		for (String l : Files.readAllLines(f)) {
			String x = l.trim();
			if (x.startsWith("#line") || x.startsWith("#extension GL_GOOGLE")) { sb.append('\n'); continue; }
			sb.append(l).append('\n');
		}
		return sb.toString();
	}

	@Test
	void wholePackValidates() throws Exception {
		Path dir = fixtures();
		assumeTrue(dir != null, "pack fixtures absent");
		assumeTrue(TranspilerTestSupport.validatorAvailable(), "glslangValidator absent");
		Path out = Files.createTempDirectory("emr-transpile");
		GlslTranspiler tr = new GlslTranspiler();
		Map<String, Map<String, List<Path>>> programs = new TreeMap<>();
		try (var files = Files.list(dir)) {
			for (Path f : (Iterable<Path>) files::iterator) {
				String n = f.getFileName().toString();
				if (!n.endsWith(".pp.glsl")) continue;
				String base = n.substring(0, n.length() - ".pp.glsl".length());
				String dim = base.substring(0, base.indexOf('_'));
				String prog = base.substring(dim.length() + 1, base.lastIndexOf('.'));
				if (prog.matches("clrwl_.*|dh_.*|voxy.*|.*_voxels|prepare|composite8|composite9")) continue;
				programs.computeIfAbsent(dim, k -> new TreeMap<>()).computeIfAbsent(prog, k -> new ArrayList<>()).add(f);
			}
		}
		long t0 = System.nanoTime();
		List<Job> jobs = new ArrayList<>();
		for (var d : programs.entrySet()) {
			for (var e : d.getValue().entrySet()) {
				String prog = e.getKey();
				List<PreprocessedStage> stages = new ArrayList<>();
				for (Path f : e.getValue()) {
					String n = f.getFileName().toString();
					String ext = n.substring(0, n.length() - ".pp.glsl".length());
					ext = ext.substring(ext.lastIndexOf('.') + 1);
					StageType st = switch (ext) {
						case "vsh" -> StageType.VERTEX;
						case "gsh" -> StageType.GEOMETRY;
						case "csh" -> StageType.COMPUTE;
						default -> StageType.FRAGMENT;
					};
					stages.add(TranspilerTestSupport.stage(prog, group(prog), st, load(f)));
				}
				Transpiler.ProgramAnalysis a = tr.analyze(stages);
				Manifest.AlphaTest alpha = group(prog).isGeometry() ? new Manifest.AlphaTest("greater", 0.1f) : null;
				Transpiler.EmitContext ctx = TranspilerTestSupport.context(a, alpha);
				boolean compute = stages.stream().anyMatch(s -> s.stage() == StageType.COMPUTE);
				List<String> vs = compute ? java.util.Arrays.asList((String) null) : variants(prog);
				boolean first = true;
				for (String v : vs) {
					Transpiler.EmitResult r = tr.emit(a, ctx, v);
					for (var g : r.glsl().entrySet()) {
						if (!first && g.getKey() != StageType.VERTEX) continue;
						jobs.add(new Job(d.getKey(), d.getKey() + "_" + prog + (g.getKey() == StageType.VERTEX ? "_" + v : ""), g.getKey(), g.getValue()));
					}
					first = false;
				}
			}
		}
		long t1 = System.nanoTime();
		System.out.printf("transpiled %d stages in %d ms%n", jobs.size(), (t1 - t0) / 1_000_000);
		Map<String, AtomicInteger[]> perDim = new ConcurrentHashMap<>();
		Map<String, String> failures = new ConcurrentHashMap<>();
		try (ExecutorService ex = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
			List<Future<?>> fs = new ArrayList<>();
			for (Job j : jobs) fs.add(ex.submit(() -> {
				String err = TranspilerTestSupport.validate(out, j.name, j.stage, j.glsl);
				AtomicInteger[] c = perDim.computeIfAbsent(j.dim, k -> new AtomicInteger[] {new AtomicInteger(), new AtomicInteger()});
				c[1].incrementAndGet();
				if (err == null) c[0].incrementAndGet();
				else failures.put(j.name + "." + j.stage.extension, err);
				return null;
			}));
			for (Future<?> f : fs) f.get();
		}
		perDim.forEach((k, v) -> System.out.printf("%s: %d/%d stages validate%n", k, v[0].get(), v[1].get()));
		new TreeMap<>(failures).forEach((k, v) -> System.out.println("FAIL " + k + "\n" + v.lines().limit(8).reduce("", (x, y) -> x + "  " + y + "\n")));
		if (failures.isEmpty()) {
			try (var w = Files.walk(out)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
		} else System.out.println("output in " + out);
		assertEquals(0, failures.size(), failures.size() + " stages fail validation: " + new TreeMap<>(failures).keySet());
	}

	record Job(String dim, String name, StageType stage, String glsl) {}
}
