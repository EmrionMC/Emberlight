package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.RawTexels;
import dev.emrion.shaders.converter.cache.CacheDir;
import dev.emrion.shaders.converter.cache.CacheKey;
import dev.emrion.shaders.converter.expr.CustomUniformParser;
import dev.emrion.shaders.converter.glsl.ProgramGroup;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.options.IrisSettings;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.plan.IrisPrograms;
import dev.emrion.shaders.converter.plan.PackProperties;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.converter.report.Report;
import dev.emrion.shaders.converter.spirv.SpirvCompiler;
import dev.emrion.shaders.converter.spirv.SpirvReflection;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native packs (docs/NATIVE_FORMAT.md): emberlight.json and the pack's GLSL 460 sources compile directly to the internal
 * manifest (docs/FORMAT.md) and SPIR-V. No Iris-layout files are generated and nothing goes through the Iris converter;
 * both feed the same runtime. Steps: parse + validate → options → frame layout → per dimension: assemble every stage
 * (NativeGlsl) → shaderc → reflect the bindings each program uses → plan the frame (phases, ping-pong, clears) → write.
 */
public final class NativeCompiler {
	private NativeCompiler() {}

	/** Effect name in {@link Manifest.Rt#effects()} of a native pack that traces rays (the mod builds the TLAS for it). */
	public static final String RT_EFFECT = "native";
	/** Part of the cache key: bump on every change to native compiler output. */
	static final String VERSION = "direct-1";
	static final String DEFAULT_DIM = "default";

	public static boolean isNative(PackSource source) { return source.exists(NativeManifest.FILE); }

	/** Cache key of a native pack (the converter's key plus the native compiler version). */
	public static String cacheKey(PackSource src, Converter.Request req, Manifest.Environment env) throws IOException {
		Map<String, String> macros = new TreeMap<>(env.macros());
		macros.put("emberlight.native", VERSION);
		return CacheKey.compute(src.contentHash(), req.settings(), env.mcVersion(), macros, req.dimensions(), req.rt());
	}

	/**
	 * Compiles a native pack into {@code req.cacheRoot()/key}. Manifest errors stop before anything is compiled; a pass
	 * whose shaders fail is left out and the rest still runs.
	 */
	public static Converter.Result compile(PackSource src, String key, Converter.Request req, Manifest.Environment env, SpirvCompiler compiler,
	                                       ExecutorService pool, Converter.ProgressListener progress, Diagnostics diagnostics) throws IOException {
		String json = src.readString(NativeManifest.FILE).orElseThrow();
		List<Diagnostic> parse = new ArrayList<>();
		NativeManifest m = NativeManifest.parse(json, src::exists, parse);
		diagnostics.addAll(parse);
		if (m == null || parse.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR))
			return new Converter.Result(null, null, diagnostics.all(), false, 0, 0);
		return new Run(src, m, key, req, env, compiler, pool, progress, diagnostics).execute();
	}

	/** Translations for the option screen (keys as the screen reads them: option.NAME, value.NAME.i, screen.page, profile.x). */
	public static Map<String, Map<String, String>> lang(PackSource src) throws IOException {
		var m = NativeManifest.parse(src.readString(NativeManifest.FILE).orElse(""), src::exists, new ArrayList<>());
		return m == null ? Map.of() : NativeOptions.lang(src, m);
	}

	static String resource(String name) {
		try (InputStream in = NativeCompiler.class.getResourceAsStream(name)) {
			if (in == null) throw new IllegalStateException("missing resource " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The frame members of the header (§8) plus the pack's custom uniforms, as one synthetic analysis for the layout builder. */
	static Transpiler.ProgramAnalysis frameAnalysis(List<Manifest.CustomUniform> custom) {
		List<Transpiler.UniformDecl> u = new ArrayList<>();
		boolean in = false;
		for (String line : resource("/nativefmt/emberlight.glsl").split("\n")) {
			if (line.contains("BEGIN FRAME")) in = true;
			else if (line.contains("END FRAME")) break;
			else if (in && line.startsWith("uniform ")) {
				String[] p = line.substring(8).replace(";", "").trim().split("\\s+");
				u.add(new Transpiler.UniformDecl(p[1], p[0], 0, null));
			}
		}
		for (var c : custom) if (c.uniform()) u.add(new Transpiler.UniformDecl(c.name(), c.type(), 0, null));
		return new Transpiler.ProgramAnalysis("emberlight frame", ProgramGroup.COMPOSITE, List.of(), u, List.of(), List.of(), List.of(),
				List.of(), List.of());
	}

	// ---- one compile -------------------------------------------------------------------------------------------------

	/** One program of one dimension: a pass (fullscreen, compute, final) or one geometry class of a geometry pass. */
	record Prog(String key, NativeManifest.Pass pass, String cls, String slot, List<String> variants) {
		boolean geometry() { return cls != null; }

		boolean shadow() { return cls != null && cls.startsWith("shadow"); }
	}

	record Job(String dim, Prog prog, StageType stage, String variant, String glsl) {
		String base() { return dim + "/" + prog.key() + "." + stage.extension + (variant == null || variant.equals("fullscreen") ? "" : "." + variant); }

		String spv() { return "spv/" + base() + ".spv"; }

		String src() { return "src/" + base() + ".glsl"; }
	}

	record Compiled(Job job, byte[] spv, SpirvReflection reflection, List<Diagnostic> diagnostics) {}

	private static final class Run {
		final PackSource src;
		final NativeManifest m;
		final String key;
		final Converter.Request req;
		final Manifest.Environment env;
		final SpirvCompiler compiler;
		final ExecutorService pool;
		final Converter.ProgressListener progress;
		final Diagnostics diagnostics;
		NativeOptions options;
		NativeGlsl glsl;

		Run(PackSource src, NativeManifest m, String key, Converter.Request req, Manifest.Environment env, SpirvCompiler compiler,
		    ExecutorService pool, Converter.ProgressListener progress, Diagnostics diagnostics) {
			this.src = src;
			this.m = m;
			this.key = key;
			this.req = req;
			this.env = env;
			this.compiler = compiler;
			this.pool = pool;
			this.progress = progress;
			this.diagnostics = diagnostics;
		}

		Converter.Result execute() throws IOException {
			progress.progress("load", 0, 1);
			options = new NativeOptions(m, IrisSettings.read(req.settings()), req.settings(), diagnostics);
			List<Manifest.CustomUniform> custom = customUniforms();
			Manifest.UniformLayout layout = new Std140LayoutBuilder().build(List.of(frameAnalysis(custom)), custom, diagnostics);
			if (req.rt().contains("upscale")) options.engine.add("EL_UPSCALING");
			if (rr()) options.engine.add("EL_RR_ACTIVE");
			glsl = new NativeGlsl(src, m, options, layout, req.rt().contains("upscale"), rr(), diagnostics);
			glsl.raygen = req.rt().contains("raygen");
			progress.progress("load", 1, 1);

			// dimensions: the default plus one per override, filtered by the request
			Map<String, NativeManifest.Dimension> dims = new LinkedHashMap<>();
			dims.put(DEFAULT_DIM, null);
			for (var d : m.dimensions()) dims.put(d.name(), d);
			if (req.dimensions() != null && !req.dimensions().isEmpty()) {
				for (String d : req.dimensions()) if (!dims.containsKey(d)) diagnostics.add(Diagnostic.error(null, 0, "pack has no dimension " + d));
				dims.keySet().retainAll(req.dimensions());
			}

			Map<String, List<Prog>> programs = new LinkedHashMap<>();
			List<Job> jobs = new ArrayList<>();
			progress.progress("emit", 0, dims.size());
			for (var d : dims.entrySet()) {
				List<Prog> progs = programs(d.getValue());
				programs.put(d.getKey(), progs);
				for (Prog p : progs) jobs.addAll(jobs(d.getKey(), d.getValue(), p));
				progress.progress("emit", programs.size(), dims.size());
			}

			Path tmp = CacheDir.begin(req.cacheRoot(), key);
			try {
				List<Compiled> compiled = compileAll(jobs, tmp);
				progress.progress("write", 0, 1);
				Map<String, Manifest.Dimension> planned = new TreeMap<>();
				Map<String, Manifest.TextureDef> textures = new TreeMap<>();
				int programCount = 0;
				for (var d : dims.entrySet()) {
					var plan = new NativePlanner(m, glsl, d.getKey(), programs.get(d.getKey()),
							compiled.stream().filter(c -> c.job().dim().equals(d.getKey())).toList(), diagnostics).plan();
					planned.put(d.getKey(), plan);
					programCount += plan.programs().size();
				}
				for (var t : glsl.bindings.textures()) textures.put(t.name(), t);
				List<Manifest.TextureDef> copied = copyTextures(textures.values(), tmp);
				int ok = (int) compiled.stream().filter(c -> c.spv() != null).count(), failed = compiled.size() - ok;

				List<Manifest.DimensionMapping> dimensionMap = new ArrayList<>();
				for (var d : m.dimensions()) for (String match : d.match()) dimensionMap.add(new Manifest.DimensionMapping(match, d.name()));
				dimensionMap.add(new Manifest.DimensionMapping("*:*", DEFAULT_DIM));

				Manifest manifest = new Manifest(Manifest.FORMAT_VERSION, Converter.VERSION, key, new Manifest.PackInfo(src.name(), src.contentHash()),
						env, options.manifest(), properties(), layout, idMaps(), copied, dimensionMap, planned, rt(programs));
				ManifestJson.write(tmp.resolve(CacheDir.MANIFEST), manifest);
				List<Diagnostic> all = diagnostics.all();
				ManifestJson.write(tmp.resolve(CacheDir.REPORT), new Report(!diagnostics.hasErrors(), programCount, ok, failed, all, false));
				Path out = CacheDir.commit(tmp, req.cacheRoot(), key);
				progress.progress("write", 1, 1);
				return new Converter.Result(out, manifest, all, false, ok, failed);
			} catch (IOException | RuntimeException e) {
				CacheDir.deleteRecursively(tmp);
				throw e;
			}
		}

		/** DLSS Ray Reconstruction runs (request "rr", with "upscale") and the pack provides its guides (§14.4). */
		boolean rr() {
			return req.rt().contains("rr") && req.rt().contains("upscale") && Boolean.TRUE.equals(m.settings().get("rayReconstruction"));
		}

		/** Custom uniforms (§9) through the shared expression parser; int/float/enum option values are substituted first. */
		List<Manifest.CustomUniform> customUniforms() {
			var props = new LinkedHashMap<String, String>();
			for (var u : m.uniforms()) props.put((u.variable() ? "variable." : "uniform.") + u.type() + "." + u.name(), options.substitute(u.expr()));
			Diagnostics d = new Diagnostics();
			var out = CustomUniformParser.parse(props, d);
			for (var x : d.all()) diagnostics.add(new Diagnostic(x.severity(), NativeManifest.FILE, 0, null, null, x.message()));
			return out;
		}

		/** The programs of one dimension: enabled passes, and one program per geometry class that its fallback chain draws. */
		List<Prog> programs(NativeManifest.Dimension dim) {
			Set<String> disabled = dim == null ? Set.of() : Set.copyOf(dim.disable());
			List<NativeManifest.Pass> passes = new ArrayList<>();
			for (var p : m.passes())
				if (!disabled.contains(p.name()) && options.enabled(p) && (rr() || !NativeManifest.writesRrGuides(p))) passes.add(p);
			List<Prog> out = new ArrayList<>();
			Map<String, NativeManifest.Pass> byClass = new HashMap<>();
			for (var p : passes) for (String c : p.classes()) byClass.put(c, p);
			boolean shadow = false;
			for (String cls : NativeManifest.CLASSES.keySet()) {
				if (cls.startsWith("shadow") && m.shadow() == null) continue;
				NativeManifest.Pass owner = null;
				for (String c = cls; c != null && owner == null; c = NativeManifest.FALLBACK.get(c)) owner = byClass.get(c);
				String slot = NativeManifest.CLASSES.get(cls);
				List<String> variants = new ArrayList<>(IrisPrograms.variants(slot));
				// a class drawn by no pass is not drawn (the runtime finds no program for its slot); empty: no MC geometry uses the slot
				if (owner == null || variants.isEmpty()) continue;
				out.add(new Prog(owner.name() + "." + cls, owner, cls, slot, variants));
				shadow |= cls.startsWith("shadow");
			}
			// the shadow pass runs only with a program for the "shadow" slot: one that draws nothing when no pass draws that class
			if (shadow && out.stream().noneMatch(p -> p.slot().equals("shadow")))
				out.add(new Prog("el_none.shadow", null, "shadow", "shadow", IrisPrograms.variants("shadow")));
			for (var p : passes) if (!p.kind().equals("geometry")) out.add(new Prog(p.name(), p, null, null, List.of()));
			return out;
		}

		List<Job> jobs(String dim, NativeManifest.Dimension override, Prog p) {
			List<Job> out = new ArrayList<>();
			if (p.pass() == null) { // el_none.shadow
				for (String v : p.variants()) out.add(new Job(dim, p, StageType.VERTEX, v, NativeGlsl.NOTHING_VERTEX));
				out.add(new Job(dim, p, StageType.FRAGMENT, null, NativeGlsl.NOTHING_FRAGMENT));
				return out;
			}
			var stages = p.pass().stages();
			switch (p.pass().kind()) {
				case "compute" -> out.add(new Job(dim, p, StageType.COMPUTE, null, glsl.stage(p, override, StageType.COMPUTE, null)));
				case "geometry" -> {
					for (String v : p.variants()) {
						out.add(new Job(dim, p, StageType.VERTEX, v, glsl.stage(p, override, StageType.VERTEX, v)));
						if (stages.containsKey("geometryShader")) out.add(new Job(dim, p, StageType.GEOMETRY, v, glsl.stage(p, override, StageType.GEOMETRY, v)));
					}
					out.add(new Job(dim, p, StageType.FRAGMENT, null, glsl.stage(p, override, StageType.FRAGMENT, null)));
				}
				default -> {
					out.add(new Job(dim, p, StageType.VERTEX, "fullscreen", stages.containsKey("vertex")
							? glsl.stage(p, override, StageType.VERTEX, "fullscreen") : NativeGlsl.FULLSCREEN_VERTEX));
					out.add(new Job(dim, p, StageType.FRAGMENT, null, glsl.stage(p, override, StageType.FRAGMENT, null)));
				}
			}
			return out;
		}

		/** shaderc in parallel; identical (stage, source) compiles once. Writes src/ (always) and spv/. */
		List<Compiled> compileAll(List<Job> jobs, Path tmp) throws IOException {
			Map<String, Future<Compiled>> unique = new HashMap<>();
			AtomicInteger done = new AtomicInteger();
			progress.progress("compile", 0, jobs.size());
			List<Future<Compiled>> futures = new ArrayList<>();
			for (Job j : jobs) {
				futures.add(unique.computeIfAbsent(j.stage() + "\0" + j.glsl(), k -> pool.submit(() -> {
					try {
						var r = compiler.compile(j.base(), j.glsl(), j.stage(), NativeGlsl.NO_LINES);
						SpirvReflection refl = r.spv() == null ? null : SpirvReflection.reflect(r.spv());
						return new Compiled(j, r.spv(), refl, r.diagnostics());
					} catch (RuntimeException e) {
						return new Compiled(j, null, null, List.of(Diagnostic.error(j.src(), 0, "compiler crashed: " + e)));
					} finally {
						progress.progress("compile", done.incrementAndGet(), jobs.size());
					}
				})));
			}
			List<Compiled> out = new ArrayList<>();
			Set<Future<Compiled>> reported = new HashSet<>();
			for (int i = 0; i < jobs.size(); i++) {
				Job j = jobs.get(i);
				Compiled c = get(futures.get(i));
				// a copy keeps its own job; diagnostics are reported once (by the first job of that source)
				if (reported.add(futures.get(i))) for (var d : c.diagnostics()) diagnostics.add(d.in(j.prog().key(), stageName(j.stage())));
				c = new Compiled(j, c.spv(), c.reflection(), c.diagnostics());
				write(tmp, j.src(), j.glsl().getBytes(StandardCharsets.UTF_8));
				if (c.spv() != null) write(tmp, j.spv(), c.spv());
				out.add(c);
			}
			return out;
		}

		static <T> T get(Future<T> f) {
			try {
				return f.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("compile interrupted", e);
			} catch (ExecutionException e) {
				throw new IllegalStateException(e.getCause());
			}
		}

		Map<String, Object> properties() {
			var s = m.settings();
			Map<String, Object> p = new TreeMap<>(PackProperties.from(new LinkedHashMap<>()));
			p.put("oldLighting", Boolean.TRUE.equals(s.get("vanillaDirectionalShade")));
			p.put("separateAo", !Boolean.FALSE.equals(s.get("separateAo")));
			p.put("clouds", s.getOrDefault("clouds", "fancy"));
			for (String k : List.of("sun", "moon", "stars")) p.put(k, !Boolean.FALSE.equals(s.get(k)));
			p.put("underwaterOverlay", Boolean.TRUE.equals(s.get("underwaterOverlay")));
			p.put("vignette", Boolean.TRUE.equals(s.get("vignette")));
			p.put("ambientOcclusionLevel", s.get("ambientOcclusion") instanceof Double d ? d : 1.0);
			return p;
		}

		Manifest.IdMaps idMaps() {
			Map<String, List<Manifest.IdEntry>> kinds = new HashMap<>();
			for (String k : List.of("blocks", "items", "entities")) {
				List<Manifest.IdEntry> l = new ArrayList<>();
				m.materials().getOrDefault(k, Map.of()).forEach((id, matchers) -> l.add(new Manifest.IdEntry(id, List.copyOf(matchers))));
				kinds.put(k, l);
			}
			return new Manifest.IdMaps(kinds.get("blocks"), kinds.get("items"), kinds.get("entities"), Map.of());
		}

		/** RT section: the pack's own ray queries ("native"), the upscaling request, and requested converter effects as unavailable. */
		Manifest.Rt rt(Map<String, List<Prog>> programs) {
			List<String> effects = new ArrayList<>();
			// the TLAS only when an enabled pass traces rays (options and dimension overrides can disable them all)
			boolean rq = m.requires().contains("ray_query")
					&& programs.values().stream().flatMap(List::stream).anyMatch(p -> p.pass() != null && p.pass().raytracing());
			if (rq) effects.add(RT_EFFECT);
			if (rq && Boolean.TRUE.equals(m.settings().get("rayTracedEntities"))) effects.add(RT_EFFECT + ":entities");
			if (req.rt().contains("upscale")) effects.add("upscale");
			if (rr()) effects.add("rr");
			if (req.rt().contains("raygen")) effects.add("raygen"); // (kept: PackManager compares the request it reloads for)
			Map<String, String> unavailable = new TreeMap<>();
			for (String r : req.rt())
				if (r.equals("rr") && !rr()) unavailable.put(r, "the pack writes no Ray Reconstruction guides (settings.rayReconstruction)");
				else if (!r.equals("upscale") && !r.equals("rr") && !r.equals("raygen")) unavailable.put(r, "native packs trace their own rays");
			return effects.isEmpty() && unavailable.isEmpty() ? null : new Manifest.Rt(effects, unavailable, Map.of(), List.of(), null);
		}

		/** Pack textures to textures/ (raw ones converted to tightly packed texels of the promoted format; .mcmeta applied). */
		List<Manifest.TextureDef> copyTextures(java.util.Collection<Manifest.TextureDef> defs, Path tmp) throws IOException {
			List<Manifest.TextureDef> out = new ArrayList<>();
			for (var t : defs) {
				if (t.file() == null) {
					out.add(t);
					continue;
				}
				byte[] data = src.read(t.file()).orElseThrow();
				String format = t.format(), filter = t.filter(), wrap = t.wrap();
				if (t.rawPixel() != null) {
					format = IrisDimensionPlanner.promote(format);
					try {
						data = RawTexels.convert(data, t.format(), format, t.rawPixel(), t.size());
					} catch (IllegalArgumentException e) {
						diagnostics.add(Diagnostic.error(t.file(), 0, "raw texture " + t.name() + ": " + e.getMessage()));
						continue;
					}
				} else {
					var mcmeta = src.readString(t.file() + ".mcmeta");
					if (mcmeta.isPresent()) {
						try {
							var tex = com.google.gson.JsonParser.parseString(mcmeta.get()).getAsJsonObject().getAsJsonObject("texture");
							if (tex != null && tex.has("blur")) filter = tex.get("blur").getAsBoolean() ? "linear" : "nearest";
							if (tex != null && tex.has("clamp")) wrap = tex.get("clamp").getAsBoolean() ? "clamp" : "repeat";
						} catch (RuntimeException e) {
							diagnostics.add(Diagnostic.warning(t.file() + ".mcmeta", 0, "unreadable mcmeta: " + e.getMessage()));
						}
					}
				}
				String file = "textures/" + t.name() + t.file().substring(t.file().lastIndexOf('.'));
				write(tmp, file, data);
				out.add(new Manifest.TextureDef(t.name(), file, null, format, t.dims(), t.size(), filter, wrap, false, t.rawPixel()));
			}
			return out;
		}
	}

	static String stageName(StageType s) { return s.name().toLowerCase(Locale.ROOT); }

	/** {@code rel} holds pack-chosen names: it must not lead out of the output dir. */
	static void write(Path dir, String rel, byte[] bytes) throws IOException {
		dir = dir.toAbsolutePath().normalize();
		Path file = dir.resolve(rel).normalize();
		if (!file.startsWith(dir) || file.equals(dir)) throw new IOException("output path leaves the cache dir: " + rel);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}
}
