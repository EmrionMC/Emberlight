package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.DirectiveParser;
import dev.emrion.shaders.converter.glsl.Directives;
import dev.emrion.shaders.converter.glsl.IncludeExpander;
import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.Preprocessor;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.SourceLineMap;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.options.IrisSettings;
import dev.emrion.shaders.converter.options.OptionApplier;
import dev.emrion.shaders.converter.options.OptionDiscovery;
import dev.emrion.shaders.converter.options.OptionSet;
import dev.emrion.shaders.converter.options.OptionValues;
import dev.emrion.shaders.converter.options.Profiles;
import dev.emrion.shaders.converter.properties.BooleanExpression;
import dev.emrion.shaders.converter.properties.DimensionMap;
import dev.emrion.shaders.converter.properties.IdMapParser;
import dev.emrion.shaders.converter.properties.OrderedProperties;
import dev.emrion.shaders.converter.properties.PropertiesPreprocessor;
import dev.emrion.shaders.converter.properties.RawProperties;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Iris pack loading (iris-spec §1.1, §1.5, §3, §4, §7): dimension folders, include graph, options, shaders.properties,
 * profiles, program.X.enabled, per-stage preprocessing and directives, id maps.
 *
 * <p>Environment macros: the IRIS_FEATURE_* entries of {@code environmentMacros} are read as the set of usable
 * feature flags and re-added the way Iris does: all of them for shaders.properties, only the flags the pack lists in
 * {@code iris.features.optional} for programs and id maps (plus COLOR_SPACE_* if supportsColorCorrection=true).
 * Iris adds no per-stage or per-program macros; MC_RENDER_STAGE_* are part of the standard macros.
 *
 * <p>Program list per dimension: setup, begin, shadow, shadowcomp, prepare, gbuffers, deferred, composite, final.
 * A .csh is its own COMPUTE-only program named like its file (shadowcomp, shadowcomp_a, setup1); when a pass also has
 * a .vsh/.fsh program of the same name, the compute one is named {@code <pass>_csh}. Computes come before the
 * fullscreen program of their pass (Iris runs them first). Disabled programs are listed with enabled=false and no stages.
 */
public final class DefaultPackLoader implements PackLoader {
	private static final String ROOT = "shaders";
	private static final List<String> COLOR_SPACES = List.of("SRGB", "DCI_P3", "DISPLAY_P3", "REC2020", "ADOBE_RGB");
	private static final String LEGACY_VERTEX = """
			#version 120

			varying vec4 irs_texCoords[3];
			varying vec4 irs_Color;

			void main() {
				gl_Position = ftransform();
				irs_texCoords[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
				irs_texCoords[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;
				irs_texCoords[2] = gl_TextureMatrix[1] * gl_MultiTexCoord2;
				irs_Color = gl_Color;
			}
			""";

	@Override
	public ResolvedPack load(PackSource source, Path settingsFile, Map<String, String> environmentMacros, Diagnostics diagnostics) {
		return new Run(source, environmentMacros, diagnostics).load(settingsFile);
	}

	private static final class Run {
		final PackSource src;
		final Diagnostics diags;
		final Map<String, String> baseEnv = new LinkedHashMap<>();
		final Set<String> usableFeatures = new HashSet<>();
		final Map<String, List<String>> files = new TreeMap<>();
		final Map<String, List<String>> applied = new ConcurrentHashMap<>();
		OptionSet optionSet;
		OptionValues values;

		Run(PackSource src, Map<String, String> env, Diagnostics diags) {
			this.src = src;
			this.diags = diags;
			env.forEach((k, v) -> {
				if (k.startsWith("IRIS_FEATURE_")) usableFeatures.add(k.substring("IRIS_FEATURE_".length()));
				else baseEnv.put(k, v);
			});
		}

		void warn(String file, String msg) {
			diags.add(Diagnostic.warning(file, 0, msg));
		}

		ResolvedPack load(Path settingsFile) {
			// 1. dimension folders
			List<Manifest.DimensionMapping> mapping = new ArrayList<>();
			List<String> candidates = new ArrayList<>();
			String dimText = latin1(ROOT + "/dimension.properties");
			SequencedMap<String, String> dimProps = dimText == null ? null
					: OrderedProperties.load(PropertiesPreprocessor.process(dimText, baseEnv));
			if (dimProps != null && !dimProps.isEmpty()) {
				mapping.addAll(DimensionMap.parse(dimProps));
				candidates.addAll(DimensionMap.folders(dimProps));
			} else {
				legacy(mapping, candidates, "world0", "minecraft:overworld", "*:*");
				legacy(mapping, candidates, "world-1", "minecraft:the_nether");
				legacy(mapping, candidates, "world1", "minecraft:the_end");
			}
			List<String> startNames = ProgramCatalog.startFileNames();
			List<String> folders = new ArrayList<>();
			for (String f : candidates) if (!folders.contains(f) && hasStart("/" + f, startNames)) folders.add(f);
			mapping.removeIf(m -> !folders.contains(m.folder()));
			if (mapping.stream().noneMatch(m -> m.match().equals("*:*")) && hasStart("", startNames)) {
				mapping.add(new Manifest.DimensionMapping("*:*", ""));
				folders.add("");
			}

			// 2. include graph: every file reachable from a start file in the root or a dimension folder
			Deque<String> queue = new ArrayDeque<>();
			for (String dir : distinct("", folders)) {
				for (String n : startNames) if (src.exists(ROOT + dir(dir) + "/" + n)) queue.add(dir(dir) + "/" + n);
			}
			readGraph(queue);

			// 3. options
			optionSet = OptionDiscovery.discover(files, msg -> warn(null, msg));
			SequencedMap<String, String> settings;
			try {
				settings = IrisSettings.read(settingsFile);
			} catch (IOException e) {
				diags.add(Diagnostic.error(String.valueOf(settingsFile), 0, "Cannot read settings file: " + e.getMessage()));
				settings = new LinkedHashMap<>();
			}
			values = new OptionValues(optionSet, settings);

			// 4. shaders.properties: preprocessed with option values + all usable features; some keys from the raw text
			String propText = latin1(ROOT + "/shaders.properties");
			Map<String, String> propEnv = new LinkedHashMap<>(baseEnv);
			usableFeatures.forEach(f -> propEnv.put("IRIS_FEATURE_" + f, ""));
			SequencedMap<String, String> properties = new LinkedHashMap<>();
			RawProperties raw = RawProperties.of(Map.of());
			if (propText != null) {
				OrderedProperties.load(PropertiesPreprocessor.process(propText, propertyMacros(propEnv)))
						.forEach((k, v) -> properties.put(k, v.trim()));
				raw = RawProperties.of(OrderedProperties.load(propText));
			}

			// 5. program environment
			Map<String, String> progEnv = new LinkedHashMap<>(baseEnv);
			String cc = properties.get("supportsColorCorrection");
			if ("true".equals(cc) || "1".equals(cc)) {
				for (int i = 0; i < COLOR_SPACES.size(); i++) progEnv.put("COLOR_SPACE_" + COLOR_SPACES.get(i), Integer.toString(i));
			}
			for (String f : raw.optionalFeatures()) {
				if (usableFeatures.contains(f.toUpperCase(Locale.US))) progEnv.put("IRIS_FEATURE_" + f, "");
			}
			for (String f : raw.requiredFeatures()) {
				String u = f.equalsIgnoreCase("TESSELATION_SHADERS") ? "TESSELLATION_SHADERS" : f.toUpperCase(Locale.US);
				if (!usableFeatures.contains(u)) diags.add(Diagnostic.error(ROOT + "/shaders.properties", 0, "Required feature not supported: " + f));
			}

			// 6. profiles and program.X.enabled
			Profiles profiles = Profiles.parse(raw.profiles(), optionSet, msg -> warn(ROOT + "/shaders.properties", msg));
			String active = profiles.detect(values);
			Set<String> disabled = new HashSet<>();
			if (active != null) disabled.addAll(profiles.disabled().get(active));
			properties.forEach((k, v) -> {
				if (!k.startsWith("program.")) return;
				int dot = k.indexOf('.', 8);
				if (dot < 0) {
					warn(ROOT + "/shaders.properties", "Malformed key " + k + " (Iris fails to load the pack)");
					return;
				}
				if (!BooleanExpression.evaluate(v, values)) disabled.add(k.substring(8, dot));
			});

			// 7. programs per dimension folder
			Map<String, List<ResolvedPack.ProgramSource>> dimensions = new LinkedHashMap<>();
			for (String folder : folders) dimensions.put(folder, programs(folder, disabled, progEnv));

			// 8. id maps (Iris uses the program environment plus option values)
			Map<String, String> idEnv = propertyMacros(progEnv);
			Consumer<String> idWarn = msg -> warn(null, msg);
			Manifest.IdMaps idMaps = IdMapParser.parse(idText("block", idEnv), idText("item", idEnv), idText("entity", idEnv), idWarn);

			Manifest.Options options = OptionsManifest.build(settingsFile, values, settings, profiles, raw, ROOT);
			return new ResolvedPack(src, options, properties, idMaps, List.copyOf(mapping), dimensions);
		}

		private String idText(String kind, Map<String, String> env) {
			String t = latin1(ROOT + "/" + kind + ".properties");
			return t == null ? null : PropertiesPreprocessor.process(t, env);
		}

		/** Iris PropertiesPreprocessor macros: true booleans as "1", string options by value, empty env values as "1". */
		private Map<String, String> propertyMacros(Map<String, String> env) {
			Map<String, String> m = new LinkedHashMap<>();
			optionSet.booleans().keySet().forEach(n -> { if (values.booleanOrDefault(n)) m.put(n, "1"); });
			env.forEach((k, v) -> m.put(k, v.isEmpty() ? "1" : v));
			optionSet.strings().keySet().forEach(n -> m.put(n, values.stringOrDefault(n)));
			return m;
		}

		private void legacy(List<Manifest.DimensionMapping> mapping, List<String> candidates, String folder, String... ids) {
			String prefix = ROOT + "/" + folder + "/";
			if (src.list().stream().noneMatch(p -> p.startsWith(prefix))) return;
			candidates.add(folder);
			for (String id : ids) mapping.add(new Manifest.DimensionMapping(id, folder));
		}

		private boolean hasStart(String dir, List<String> names) {
			for (String n : names) if (src.exists(ROOT + dir + "/" + n)) return true;
			return false;
		}

		private static String dir(String folder) {
			return folder.isEmpty() ? "" : "/" + folder;
		}

		private static List<String> distinct(String first, List<String> rest) {
			List<String> l = new ArrayList<>();
			l.add(first);
			for (String r : rest) if (!l.contains(r)) l.add(r);
			return l;
		}

		private void readGraph(Deque<String> queue) {
			Set<String> seen = new HashSet<>(queue);
			while (!queue.isEmpty()) {
				String path = queue.poll();
				String text = utf8(ROOT + path);
				if (text == null) continue;
				List<String> lines = OptionDiscovery.splitLines(text);
				files.put(path, lines);
				String dir = path.substring(0, path.lastIndexOf('/'));
				for (int i = 0; i < lines.size(); i++) {
					String target;
					try {
						target = IncludeExpander.includeTarget(lines.get(i));
					} catch (IllegalArgumentException e) {
						continue;
					}
					if (target == null) continue;
					String inc = IncludeExpander.resolve(dir, target);
					if (seen.add(inc) && src.exists(ROOT + inc)) queue.add(inc);
				}
			}
		}

		private List<String> appliedLines(String path) {
			List<String> raw = files.get(path);
			if (raw == null) return null;
			return applied.computeIfAbsent(path, p -> OptionApplier.apply(raw, optionSet.lines().get(p), values));
		}

		// ---- programs ------------------------------------------------------------------------------------------------

		private record Job(String name, ProgramGroup group, StageType stage, String path, boolean synthetic) {}

		private List<ResolvedPack.ProgramSource> programs(String folder, Set<String> disabled, Map<String, String> env) {
			String d = dir(folder);
			List<Object[]> entries = new ArrayList<>(); // {name, enabled, jobs}
			for (ProgramCatalog.Slot slot : ProgramCatalog.slots()) {
				boolean graphicsFiles = slot.graphics() && (exists(d, slot.name(), "vsh") || exists(d, slot.name(), "fsh")
						|| exists(d, slot.name(), "gsh"));
				if (slot.group() == ProgramGroup.SETUP) {
					computeProgram(entries, d, folder, slot.name(), slot.group(), disabled, false);
					continue;
				}
				if (slot.computes()) {
					computeProgram(entries, d, folder, slot.name(), slot.group(), disabled, graphicsFiles);
					for (char c = 'a'; c <= 'z'; c++) {
						if (!computeProgram(entries, d, folder, slot.name() + "_" + c, slot.group(), disabled, false)) break;
					}
				}
				if (graphicsFiles) graphicsProgram(entries, d, folder, slot, disabled);
			}
			List<ResolvedPack.ProgramSource> out = new ArrayList<>();
			List<Object[]> enabledEntries = entries.stream().filter(e -> (boolean) e[1]).toList();
			Map<Job, PreprocessedStage> done = new ConcurrentHashMap<>();
			enabledEntries.stream().flatMap(e -> ((List<?>) e[2]).stream()).map(Job.class::cast).toList()
					.parallelStream().forEach(j -> done.put(j, preprocess(j, env)));
			for (Object[] e : entries) {
				List<PreprocessedStage> stages = new ArrayList<>();
				if ((boolean) e[1]) for (Object j : (List<?>) e[2]) stages.add(done.get((Job) j));
				out.add(new ResolvedPack.ProgramSource((String) e[0], (boolean) e[1], List.copyOf(stages)));
			}
			return out;
		}

		private boolean exists(String d, String name, String ext) {
			return src.exists(ROOT + d + "/" + name + "." + ext);
		}

		private static String programKey(String folder, String name) {
			return folder.isEmpty() ? name : folder + "/" + name;
		}

		/** Returns false if the .csh is missing or disabled (Iris stops the _a.._z letter scan there). */
		private boolean computeProgram(List<Object[]> entries, String d, String folder, String file, ProgramGroup g,
		                               Set<String> disabled, boolean clashesWithGraphics) {
			if (!exists(d, file, "csh")) return false;
			String name = file;
			if (clashesWithGraphics) {
				name = file + "_csh";
				warn(ROOT + d + "/" + file + ".csh", "compute alongside fullscreen pass not yet supported");
			}
			boolean enabled = !disabled.contains(programKey(folder, file));
			entries.add(new Object[]{name, enabled, List.of(new Job(name, g, StageType.COMPUTE, d + "/" + file + ".csh", false))});
			return enabled;
		}

		private void graphicsProgram(List<Object[]> entries, String d, String folder, ProgramCatalog.Slot slot, Set<String> disabled) {
			String n = slot.name();
			boolean enabled = !disabled.contains(programKey(folder, n));
			boolean vsh = exists(d, n, "vsh"), fsh = exists(d, n, "fsh");
			if (!fsh) {
				if (enabled) warn(ROOT + d + "/" + n, "Program has no .fsh and is ignored (Iris needs .vsh and .fsh)");
				return;
			}
			List<Job> jobs = new ArrayList<>();
			jobs.add(new Job(n, slot.group(), StageType.VERTEX, d + "/" + n + ".vsh", !vsh));
			if (exists(d, n, "gsh")) jobs.add(new Job(n, slot.group(), StageType.GEOMETRY, d + "/" + n + ".gsh", false));
			jobs.add(new Job(n, slot.group(), StageType.FRAGMENT, d + "/" + n + ".fsh", false));
			if (!vsh && enabled) warn(ROOT + d + "/" + n + ".fsh", "No .vsh; using Iris's legacy fallback vertex shader");
			entries.add(new Object[]{n, enabled, List.copyOf(jobs)});
		}

		private PreprocessedStage preprocess(Job j, Map<String, String> env) {
			IncludeExpander.Expanded ex = IncludeExpander.expand(j.path(), p -> j.synthetic() && p.equals(j.path())
					? OptionDiscovery.splitLines(LEGACY_VERTEX) : appliedLines(p));
			String stage = j.stage().extension;
			for (IncludeExpander.Error e : ex.errors()) {
				diags.add(new Diagnostic(Diagnostic.Severity.ERROR, e.file() == null ? ROOT + j.path() : ROOT + e.file(),
						e.line(), j.name(), stage, e.message()));
			}
			Preprocessor.Result r = Preprocessor.run(ex.text(), env, Preprocessor.Mode.GLSL);
			SourceLineMap map = new SourceLineMap(ex, r.hoistedLines(), ROOT);
			for (Preprocessor.Message m : r.warnings()) {
				diags.add(new Diagnostic(Diagnostic.Severity.WARNING, map.fileOfInput(m.line()), map.lineOfInput(m.line()),
						j.name(), stage, m.text()));
			}
			String head = r.hoisted().isEmpty() ? "" : String.join("\n", r.hoisted()) + "\n";
			Directives directives = DirectiveParser.parse(j.stage(), r.text(), head + r.textWithComments(),
					msg -> diags.add(new Diagnostic(Diagnostic.Severity.WARNING, ROOT + j.path(), 0, j.name(), stage, msg)));
			return new PreprocessedStage(j.name(), j.group(), j.stage(), ROOT + j.path(), head + r.text(), map, directives);
		}

		// ---- file access -----------------------------------------------------------------------------------------------

		private String latin1(String path) {
			try {
				return src.read(path).map(b -> new String(b, StandardCharsets.ISO_8859_1)).orElse(null);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private String utf8(String path) {
			try {
				return src.readString(path).orElse(null);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
