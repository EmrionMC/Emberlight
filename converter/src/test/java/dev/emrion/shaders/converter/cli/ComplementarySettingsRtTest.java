package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.emrion.shaders.converter.cli.TargetPackConversionTest.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The mod's full RT request (preset ultra, style RTX) on plain and Euphoria-patched Complementary under many pack settings, one
 * option changed at a time plus a few combinations: every stage compiles and every effect applies, except where the pack really
 * has no code for it. Skipped for each pack folder missing from testpacks/ (reimagined, unbound, reimagined-euphoria,
 * unbound-euphoria); only world0, where every effect applies, to keep the run short.
 */
class ComplementarySettingsRtTest {
	static final Set<String> RT = Set.of("shadows", "shadows:full", "shadows:rays=4", "shadows:entities", "shadows:rtx", "reflections",
			"materials", "shafts", "refraction", "refraction:entities", "refraction:glass=0", "caustics", "ao", "blocklight",
			"blocklight:rays=2", "blocklight:entities", "blocklight:rtx", "gi:rtx", "gi:entities", "gi:cache", "gi", "gi:rays=2", "gi:res=1.0");

	/** Settings name → options written to the settings file (the rest stay at the pack's defaults). */
	static final Map<String, Map<String, String>> SETTINGS = new LinkedHashMap<>();

	static {
		SETTINGS.put("defaults", Map.of());
		String[][] single = {{"DETAIL_QUALITY", "0", "3", "4"}, {"SHADOW_QUALITY", "-1", "0", "1", "5"}, {"COLORED_LIGHTING", "128", "1024"},
				{"WORLD_SPACE_REFLECTIONS", "1"}, {"RP_MODE", "0", "2", "3"}, {"WATER_STYLE_DEFINE", "1", "2", "3"}, {"SSAO_QUALI_DEFINE", "0", "3"},
				{"LIGHTSHAFT_QUALI_DEFINE", "0", "4"}, {"BLOCK_REFLECT_QUALITY", "0", "1"}, {"WATER_REFLECT_QUALITY", "-1", "0"},
				{"TAA_DEFINE", "0", "1"}, {"REFLECTION_RES", "0.5"}};
		for (String[] o : single) for (int i = 1; i < o.length; i++) SETTINGS.put(o[0] + "=" + o[i], Map.of(o[0], o[i]));
		SETTINGS.put("all-low", Map.of("DETAIL_QUALITY", "0", "SHADOW_QUALITY", "0", "SSAO_QUALI_DEFINE", "0", "LIGHTSHAFT_QUALI_DEFINE", "0",
				"BLOCK_REFLECT_QUALITY", "0", "WATER_REFLECT_QUALITY", "-1", "TAA_DEFINE", "0", "RP_MODE", "0"));
		SETTINGS.put("all-max", Map.of("DETAIL_QUALITY", "4", "SHADOW_QUALITY", "5", "COLORED_LIGHTING", "1024", "WORLD_SPACE_REFLECTIONS", "1",
				"SSAO_QUALI_DEFINE", "3", "LIGHTSHAFT_QUALI_DEFINE", "4", "WATER_STYLE_DEFINE", "3", "RP_MODE", "3", "TAA_DEFINE", "1",
				"REFLECTION_RES", "1.0"));
	}

	/**
	 * The effects allowed to be unavailable under {@code s}: the user turned the pack feature off and raising it would change the
	 * look (SHADOW_QUALITY -1 is raised instead, see the table's packSettings: RT replaces the shadows it brings back).
	 */
	static Set<String> accepted(Map<String, String> s) {
		Set<String> ok = new java.util.HashSet<>();
		// LIGHTSHAFT_QUALI_DEFINE 0 turns the pack's light shafts off: no volumetric light to scale
		if ("0".equals(s.get("LIGHTSHAFT_QUALI_DEFINE"))) ok.add("shafts");
		// SSAO_QUALI_DEFINE 0 turns the pack's SSAO off: no AO to replace
		if ("0".equals(s.get("SSAO_QUALI_DEFINE"))) ok.add("ao");
		return ok;
	}

	@Test
	void everyEffectAppliesUnderEverySetting(@TempDir Path tmp) throws Exception {
		// about 10 minutes: only on request (EMRION_RT_MATRIX=1 ./gradlew :converter:test), e.g. after a patch table change
		assumeTrue(System.getenv("EMRION_RT_MATRIX") != null, "set EMRION_RT_MATRIX=1 to run the settings matrix");
		List<String> packs = new ArrayList<>();
		for (String p : List.of("reimagined", "unbound", "reimagined-euphoria", "unbound-euphoria"))
			if (Files.isDirectory(ROOT.resolve(p))) packs.add(p);
		assumeTrue(!packs.isEmpty());
		Map<String, String> failures = new TreeMap<>();
		Map<String, String> table = new TreeMap<>();
		try (var pool = Executors.newSingleThreadExecutor()) { // the converter compiles in parallel already; two at once ran out of test heap
			Map<String, Future<String[]>> jobs = new LinkedHashMap<>();
			for (String pack : packs)
				for (var s : SETTINGS.entrySet()) {
					String key = String.format("%-20s %s", pack, s.getKey());
					jobs.put(key, pool.submit(() -> run(tmp, pack, s.getKey(), s.getValue())));
				}
			for (var j : jobs.entrySet()) {
				String[] r = j.getValue().get(); // {unavailable effects not accepted, all unavailable, errors}
				table.put(j.getKey(), "unavailable: " + r[1]);
				if (!r[0].isEmpty() || !r[2].isEmpty()) failures.put(j.getKey(), r[0] + " " + r[2]);
			}
		}
		table.forEach((k, v) -> System.out.println("RT matrix  " + k + "  " + v));
		assertEquals(Map.of(), failures);
	}

	private static String[] run(Path tmp, String pack, String name, Map<String, String> s) throws Exception {
		Path dir = Files.createTempDirectory(tmp, "m");
		Path settings = dir.resolve("settings.txt");
		StringBuilder txt = new StringBuilder();
		s.forEach((k, v) -> txt.append(k).append('=').append(v).append('\n'));
		Files.writeString(settings, txt);
		Converter.Result r;
		try (var compiler = new ShadercSpirvCompiler()) {
			r = ConverterCli.defaultConverter(compiler).convert(new Converter.Request(ROOT.resolve(pack), settings, dir.resolve("out"),
					List.of("world0"), Converter.ProgressListener.NONE, false, RT, true));
		}
		if (r.manifest() == null) return new String[]{"no manifest", "?", r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR)
				.limit(3).map(Diagnostic::toString).reduce("", (a, b) -> a + b + "; ")};
		var unavailable = r.manifest().rt() == null ? Map.<String, String>of() : r.manifest().rt().unavailable();
		Set<String> ok = accepted(s);
		StringBuilder bad = new StringBuilder();
		unavailable.forEach((e, why) -> { if (!ok.contains(e)) bad.append(e).append(": ").append(why).append("; "); });
		String errors = r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).limit(3).map(Diagnostic::toString)
				.reduce("", (a, b) -> a + b + "; ");
		return new String[]{bad.toString(), unavailable.keySet().toString(), r.stagesFailed() == 0 ? errors : "failed stages: " + r.stagesFailed() + " " + errors};
	}
}
