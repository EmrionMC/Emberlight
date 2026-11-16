package dev.emrion.shaders.converter;

import dev.emrion.shaders.converter.cache.CacheDir;
import dev.emrion.shaders.converter.cache.CacheKey;
import dev.emrion.shaders.converter.glsl.LineMap;
import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.converter.glsl.StageType;
import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.PackLoader;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.plan.DimensionPlanner;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.plan.PackProperties;
import dev.emrion.shaders.converter.plan.UniformLayoutBuilder;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.converter.report.Report;
import dev.emrion.shaders.converter.spirv.SpirvCompiler;
import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * load → custom uniforms → analyse → uniform layout → plan per dimension → emit + compile (parallel) → write cache dir.
 * Pack errors become diagnostics; a failed program is dropped from its plan and the run continues.
 */
public final class DefaultConverter implements Converter {

	public interface PackOpener {
		PackSource open(Path zipOrDir) throws IOException;
	}

	/** Seam for the static {@code CustomUniformParser.parse}. */
	public interface CustomUniforms {
		List<Manifest.CustomUniform> parse(SequencedMap<String, String> properties, Diagnostics diagnostics);
	}

	private final PackOpener opener;
	private final PackLoader loader;
	private final CustomUniforms customUniforms;
	private final Transpiler transpiler;
	private final UniformLayoutBuilder layoutBuilder;
	private final DimensionPlanner planner;
	private final SpirvCompiler compiler;
	private final Manifest.Environment environment;
	private final int threads;

	/** @param environment MC version and the standard macros (StandardMacros.build) passed to the loader */
	public DefaultConverter(PackOpener opener, PackLoader loader, CustomUniforms customUniforms, Transpiler transpiler,
	                        UniformLayoutBuilder layoutBuilder, DimensionPlanner planner, SpirvCompiler compiler,
	                        Manifest.Environment environment, int threads) {
		this.opener = opener;
		this.loader = loader;
		this.customUniforms = customUniforms;
		this.transpiler = transpiler;
		this.layoutBuilder = layoutBuilder;
		this.planner = planner;
		this.compiler = compiler;
		this.environment = environment;
		this.threads = threads;
	}

	@Override
	public Result convert(Request request) {
		ProgressListener progress = request.progress() == null ? ProgressListener.NONE : request.progress();
		Diagnostics diagnostics = new Diagnostics();
		progress.progress("load", 0, 1);
		try (PackSource source = opener.open(request.pack());
		     ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			String packHash = source.contentHash();
			String key = CacheKey.compute(packHash, request.settings(), environment.mcVersion(), environment.macros(), request.dimensions(), request.rt(), request.sodium());
			if (request.useCache()) {
				Result hit = cached(request.cacheRoot().resolve(key), key);
				if (hit != null) return hit;
			}
			return new Run(source, packHash, key, request, progress, diagnostics, pool).execute();
		} catch (IOException | RuntimeException e) {
			diagnostics.add(Diagnostic.error(request.pack().toString(), 0, "conversion failed: " + e));
			return new Result(null, null, diagnostics.all(), false, 0, 0);
		}
	}

	private static Result cached(Path dir, String key) throws IOException {
		Manifest manifest = CacheDir.readValid(dir, key);
		if (manifest == null || !Files.isRegularFile(dir.resolve(CacheDir.REPORT))) return null;
		Report report = ManifestJson.read(dir.resolve(CacheDir.REPORT), Report.class);
		return new Result(dir, manifest, report.diagnostics(), true, report.stagesCompiled(), report.stagesFailed());
	}

	/** One stage to compile; {@code variant} is null for non-vertex stages and for programs without vertex variants. */
	private record StageJob(String dim, String program, StageType stage, String variant, String glsl, LineMap source) {
		String baseName() {
			return dim + "/" + program + "." + stage.extension + (variant == null || variant.equals("fullscreen") ? "" : "." + variant);
		}

		String spvPath() { return "spv/" + baseName() + ".spv"; }

		String srcPath() { return "src/" + baseName() + ".glsl"; }

		String stageName() { return stage.name().toLowerCase(Locale.ROOT); }
	}

	private record StageResult(StageJob job, byte[] spv, List<Diagnostic> diagnostics) {}

	/** State of one conversion run. */
	private final class Run {
		final PackSource source;
		final String packHash, key;
		final Request request;
		final ProgressListener progress;
		final Diagnostics diagnostics;
		final ExecutorService pool;

		Run(PackSource source, String packHash, String key, Request request, ProgressListener progress, Diagnostics diagnostics, ExecutorService pool) {
			this.source = source;
			this.packHash = packHash;
			this.key = key;
			this.request = request;
			this.progress = progress;
			this.diagnostics = diagnostics;
			this.pool = pool;
		}

		Result execute() throws IOException {
			ResolvedPack pack = loader.load(source, request.settings(), environment.macros(), diagnostics);
			Manifest.Rt rt = null;
			List<String> rtFrees = List.of();
			if (!request.rt().isEmpty()) { // RT variant; an empty set never gets here
				var patched = dev.emrion.shaders.converter.glsl.RtPatcher.apply(pack, request.rt());
				pack = patched.pack();
				rt = patched.rt();
				rtFrees = patched.frees();
				diagnostics.addAll(patched.diagnostics());
			}
			progress.progress("load", 1, 1);
			List<Manifest.CustomUniform> custom = customUniforms.parse(pack.properties(), diagnostics);
			List<String> dims = selectDimensions(pack);
			int[] failed = {0};

			Map<String, Map<String, Transpiler.ProgramAnalysis>> analyses = analyse(pack, dims, failed);
			if (!rtFrees.isEmpty()) {
				List<Diagnostic> d = new java.util.ArrayList<>();
				pack = dev.emrion.shaders.converter.glsl.RtPatcher.free(pack, rtFrees, analyses, d);
				diagnostics.addAll(d);
			}
			progress.progress("plan", 0, dims.size());
			Manifest.UniformLayout layout = layoutBuilder.build(
					analyses.values().stream().flatMap(m -> m.values().stream()).toList(), custom, diagnostics);
			Map<String, DimensionPlanner.DimensionPlan> plans = new TreeMap<>();
			for (String dim : dims) {
				try {
					plans.put(dim, planner.plan(dim, pack.dimensions().get(dim), analyses.get(dim), pack.properties(),
							pack.options().values(), layout, diagnostics));
				} catch (RuntimeException e) {
					diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, null, 0, null, null, "planning " + dim + " failed: " + e));
				}
				progress.progress("plan", plans.size(), dims.size());
			}

			List<StageJob> jobs = emit(plans, analyses);
			Path tmp = CacheDir.begin(request.cacheRoot(), key);
			try {
				return writeOutput(pack, plans, jobs, layout, rt, failed, tmp);
			} catch (IOException | RuntimeException e) {
				CacheDir.deleteRecursively(tmp); // failed or cancelled (interrupted): nothing stays behind
				throw e;
			}
		}

		Result writeOutput(ResolvedPack pack, Map<String, DimensionPlanner.DimensionPlan> plans, List<StageJob> jobs, Manifest.UniformLayout layout, Manifest.Rt rt,
		             int[] failed, Path tmp) throws IOException {
			List<StageResult> results = compile(jobs, tmp);
			progress.progress("write", 0, 1);

			Map<String, Manifest.Dimension> dimensions = new TreeMap<>();
			int compiled = 0, programs = 0;
			for (var e : plans.entrySet()) {
				String dim = e.getKey();
				Manifest.Dimension filled = PlanFiller.fill(e.getValue().dimension(),
						results.stream().filter(r -> r.job().dim().equals(dim)).toList(), diagnostics);
				dimensions.put(dim, filled);
				programs += filled.programs().size();
			}
			for (StageResult r : results) {
				if (r.spv() != null) compiled++;
				else failed[0]++;
			}
			Manifest manifest = new Manifest(Manifest.FORMAT_VERSION, VERSION, key, new Manifest.PackInfo(source.name(), packHash),
					environment, pack.options(), packProperties(pack), layout, pack.idMaps(), copyTextures(plans, tmp),
					pack.dimensionMap(), dimensions, rt);
			ManifestJson.write(tmp.resolve(CacheDir.MANIFEST), manifest);
			List<Diagnostic> all = diagnostics.all();
			ManifestJson.write(tmp.resolve(CacheDir.REPORT), new Report(!diagnostics.hasErrors(), programs, compiled, failed[0], all));
			Path out = CacheDir.commit(tmp, request.cacheRoot(), key);
			progress.progress("write", 1, 1);
			return new Result(out, manifest, all, false, compiled, failed[0]);
		}

		/** Typed shaders.properties flags plus the pack-wide const directives the runtime needs (Iris reads them from code). */
		static Map<String, Object> packProperties(ResolvedPack pack) {
			Map<String, Object> out = new java.util.TreeMap<>(PackProperties.from(pack.properties()));
			var anyDim = new TreeMap<>(pack.dimensions()).firstEntry();
			if (anyDim != null) {
				var consts = dev.emrion.shaders.converter.pack.PackConsts.merge(anyDim.getValue());
				for (String k : List.of("wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife", "ambientOcclusionLevel"))
					if (consts.containsKey(k)) {
						try { out.put(k, Double.parseDouble(consts.get(k).replaceAll("[fF]$", ""))); }
						catch (NumberFormatException ignored) { /* Iris skips unparsable const values too */ }
					}
			}
			return out;
		}

		List<String> selectDimensions(ResolvedPack pack) {
			if (pack.dimensions().values().stream().allMatch(List::isEmpty))
				diagnostics.add(Diagnostic.error(null, 0, "pack contains no shader programs (is shaders/ missing?)"));
			if (request.dimensions() == null || request.dimensions().isEmpty()) return new TreeMap<>(pack.dimensions()).keySet().stream().toList();
			List<String> dims = new ArrayList<>();
			for (String d : request.dimensions().stream().sorted().distinct().toList()) {
				if (pack.dimensions().containsKey(d)) dims.add(d);
				else diagnostics.add(Diagnostic.error(null, 0, "pack has no dimension folder " + d));
			}
			return dims;
		}

		/** Analyses enabled programs in parallel; a program with analysis errors is left out (and its stages count as failed). */
		Map<String, Map<String, Transpiler.ProgramAnalysis>> analyse(ResolvedPack pack, List<String> dims, int[] failed) {
			List<ResolvedPack.ProgramSource> programs = new ArrayList<>();
			List<String> programDims = new ArrayList<>();
			for (String dim : dims)
				for (var p : pack.dimensions().get(dim))
					if (p.enabled()) {
						programs.add(p);
						programDims.add(dim);
					}
			AtomicInteger done = new AtomicInteger();
			progress.progress("analyse", 0, programs.size());
			List<Transpiler.ProgramAnalysis> results = parallel(programs.stream().<Callable<Transpiler.ProgramAnalysis>>map(p -> () -> {
				try {
					return transpiler.analyze(p.stages());
				} catch (RuntimeException e) {
					return null;
				} finally {
					progress.progress("analyse", done.incrementAndGet(), programs.size());
				}
			}).toList());

			Map<String, Map<String, Transpiler.ProgramAnalysis>> out = new TreeMap<>();
			dims.forEach(d -> out.put(d, new TreeMap<>()));
			for (int i = 0; i < programs.size(); i++) {
				var program = programs.get(i);
				var analysis = results.get(i);
				if (analysis == null) {
					diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, null, 0, program.name(), null, "transpiler crashed during analysis"));
				} else {
					analysis.diagnostics().forEach(d -> diagnostics.add(d.program() == null ? d.in(program.name(), d.stage()) : d));
					if (analysis.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR)) {
						out.get(programDims.get(i)).put(program.name(), analysis);
						continue;
					}
				}
				failed[0] += program.stages().size();
			}
			return out;
		}

		/** Emits every (program, variant); only the first variant contributes non-vertex stages (they do not vary). */
		List<StageJob> emit(Map<String, DimensionPlanner.DimensionPlan> plans, Map<String, Map<String, Transpiler.ProgramAnalysis>> analyses) {
			record Emit(String dim, String program, String variant, boolean first) {}
			List<Emit> emits = new ArrayList<>();
			for (var plan : plans.entrySet())
				for (var program : new TreeMap<>(plan.getValue().emits()).entrySet()) {
					List<String> variants = program.getValue().vertexVariants();
					// Sodium draws every chunk mesh that vanilla would draw with terrain_mdi
					if (request.sodium() && variants != null && variants.contains("terrain_mdi"))
						variants = java.util.stream.Stream.concat(variants.stream(), java.util.stream.Stream.of("terrain_sodium")).toList();
					if (variants == null || variants.isEmpty()) emits.add(new Emit(plan.getKey(), program.getKey(), null, true));
					else for (int i = 0; i < variants.size(); i++) emits.add(new Emit(plan.getKey(), program.getKey(), variants.get(i), i == 0));
				}
			// one task per program: the transpiler parses it once for all its variants
			List<List<Emit>> programs = new ArrayList<>();
			for (Emit e : emits) {
				if (e.first()) programs.add(new ArrayList<>());
				programs.getLast().add(e);
			}
			List<Transpiler.EmitResult> results = new ArrayList<>();
			for (var rs : parallel(programs.stream().<Callable<List<Transpiler.EmitResult>>>map(es -> () -> {
				Emit e = es.getFirst();
				var analysis = analyses.get(e.dim()).get(e.program());
				List<String> variants = new ArrayList<>();
				es.forEach(x -> variants.add(x.variant()));
				if (analysis == null) return java.util.Collections.nCopies(es.size(), null);
				try {
					return transpiler.emitVariants(analysis, plans.get(e.dim()).emits().get(e.program()).context(), variants);
				} catch (RuntimeException ex) {
					return java.util.Collections.nCopies(es.size(), new Transpiler.EmitResult(Map.of(), List.of(Diagnostic.error(null, 0, "transpiler crashed: " + ex))));
				}
			}).toList())) results.addAll(rs);

			List<StageJob> jobs = new ArrayList<>();
			for (int i = 0; i < emits.size(); i++) {
				Emit e = emits.get(i);
				var r = results.get(i);
				if (r == null) {
					diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, null, 0, e.program(), null, "planned for emission but has no analysis"));
					continue;
				}
				r.diagnostics().forEach(d -> diagnostics.add(d.program() == null ? d.in(e.program(), d.stage()) : d));
				if (r.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR) || r.glsl().isEmpty()) {
					// emit failure: a job without GLSL fails the program in PlanFiller
					jobs.add(new StageJob(e.dim(), e.program(), StageType.VERTEX, e.variant(), null, null));
					continue;
				}
				for (StageType stage : StageType.values()) {
					String glsl = r.glsl().get(stage);
					boolean perVariant = stage == StageType.VERTEX || stage.intermediate();
					if (glsl == null || (!perVariant && !e.first())) continue;
					LineMap source = analyses.get(e.dim()).get(e.program()).stages().stream().filter(st -> st.stage() == stage)
							.map(PreprocessedStage::lineMap).findFirst().orElse(null);
					jobs.add(new StageJob(e.dim(), e.program(), stage, perVariant ? e.variant() : null, glsl, source));
				}
			}
			return jobs;
		}

		List<StageResult> compile(List<StageJob> jobs, Path tmp) throws IOException {
			AtomicInteger done = new AtomicInteger();
			progress.progress("compile", 0, jobs.size());
			// identical (stage, source) compiles once; a copy reuses its SPIR-V when that compile was clean (diagnostics name the job)
			Map<String, StageJob> firsts = new HashMap<>();
			List<StageJob> unique = new ArrayList<>(), copies = new ArrayList<>();
			for (StageJob job : jobs) {
				if (job.glsl() == null || firsts.putIfAbsent(job.stage() + "\0" + job.glsl(), job) == null) unique.add(job);
				else copies.add(job);
			}
			java.util.function.Function<StageJob, Callable<StageResult>> compileJob = job -> () -> {
				try {
					if (job.glsl() == null) return new StageResult(job, null, List.of());
					var r = compiler.compile(job.baseName(), job.glsl(), job.stage(), srcLines(job.srcPath(), job.source()));
					return new StageResult(job, r.spv(), r.diagnostics().stream().map(d -> d.in(job.program(), job.stageName())).toList());
				} catch (RuntimeException e) {
					return new StageResult(job, null, List.of(new Diagnostic(Diagnostic.Severity.ERROR, job.srcPath(), 0,
							job.program(), job.stageName(), "compiler crashed: " + e)));
				} finally {
					progress.progress("compile", done.incrementAndGet(), jobs.size());
				}
			};
			Map<StageJob, StageResult> byJob = new java.util.IdentityHashMap<>();
			for (StageResult r : parallel(unique.stream().map(compileJob).toList())) byJob.put(r.job(), r);
			List<StageJob> recompile = new ArrayList<>();
			for (StageJob job : copies) {
				StageResult first = byJob.get(firsts.get(job.stage() + "\0" + job.glsl()));
				if (first.spv() == null || !first.diagnostics().isEmpty()) recompile.add(job);
				else {
					byJob.put(job, new StageResult(job, first.spv(), List.of()));
					progress.progress("compile", done.incrementAndGet(), jobs.size());
				}
			}
			for (StageResult r : parallel(recompile.stream().map(compileJob).toList())) byJob.put(r.job(), r);
			List<StageResult> results = jobs.stream().map(byJob::get).toList();
			for (StageResult r : results) {
				diagnostics.addAll(r.diagnostics());
				StageJob job = r.job();
				if (job.glsl() != null) write(tmp, job.srcPath(), job.glsl().getBytes(StandardCharsets.UTF_8));
				if (r.spv() != null) write(tmp, job.spvPath(), r.spv());
			}
			return results;
		}

		/** Custom textures of all dimensions, deduplicated by name and copied to textures/; {@code file} becomes the cache path. */
		List<Manifest.TextureDef> copyTextures(Map<String, DimensionPlanner.DimensionPlan> plans, Path tmp) throws IOException {
			Map<String, Manifest.TextureDef> byName = new TreeMap<>();
			for (var plan : plans.values())
				for (var t : plan.textures()) byName.putIfAbsent(t.name(), t);
			List<Manifest.TextureDef> out = new ArrayList<>();
			for (var t : byName.values()) {
				if (t.file() == null) {
					out.add(t);
					continue;
				}
				var bytes = source.read(t.file());
				if (bytes.isEmpty()) {
					diagnostics.add(Diagnostic.error(t.file(), 0, "custom texture " + t.name() + " not found in pack"));
					continue;
				}
				String ext = t.file().substring(t.file().lastIndexOf('.') + 1);
				String file = "textures/" + t.name() + "." + ext;
				String format = t.format();
				byte[] data = bytes.get();
				if (t.rawPixel() != null) {
					format = IrisDimensionPlanner.promote(format);
					try {
						data = RawTexels.convert(data, t.format(), format, t.rawPixel(), t.size());
					} catch (IllegalArgumentException e) {
						diagnostics.add(Diagnostic.warning(t.file(), 0, "raw custom texture " + t.name() + " skipped: " + e.getMessage()));
						continue;
					}
				}
				write(tmp, file, data);
				String filter = t.filter(), wrap = t.wrap();
				// Iris applies the PNG's .mcmeta ("texture": {"blur", "clamp"}) to custom textures.
				var mcmeta = source.readString(t.file() + ".mcmeta");
				if (mcmeta.isPresent()) {
					try {
						var tex = com.google.gson.JsonParser.parseString(mcmeta.get()).getAsJsonObject().getAsJsonObject("texture");
						if (tex != null && tex.has("blur")) filter = tex.get("blur").getAsBoolean() ? "linear" : "nearest";
						if (tex != null && tex.has("clamp")) wrap = tex.get("clamp").getAsBoolean() ? "clamp" : "repeat";
					} catch (RuntimeException e) {
						diagnostics.add(Diagnostic.warning(t.file() + ".mcmeta", 0, "unreadable mcmeta: " + e.getMessage()));
					}
				}
				out.add(new Manifest.TextureDef(t.name(), file, t.resource(), format, t.dims(), t.size(), filter, wrap, t.mipmap(), t.rawPixel()));
			}
			return out;
		}

		<T> List<T> parallel(List<Callable<T>> tasks) {
			try {
				List<T> out = new ArrayList<>(tasks.size());
				for (Future<T> f : pool.invokeAll(tasks)) out.add(f.get());
				return out;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("conversion interrupted", e);
			} catch (ExecutionException e) {
				throw new IllegalStateException(e.getCause());
			}
		}
	}

	/**
	 * Compile errors point at the pack file and line: the emitted GLSL carries {@code #line} directives with preprocessed
	 * line numbers, which {@code source} maps back. Lines it cannot map (generated code) point at the written src/ file.
	 */
	private static LineMap srcLines(String srcPath, LineMap source) {
		return new LineMap() {
			public String file(int outputLine) {
				String f = source == null ? null : source.file(outputLine);
				return f != null ? f : srcPath;
			}

			public int line(int outputLine) {
				return source != null && source.file(outputLine) != null ? source.line(outputLine) : outputLine;
			}
		};
	}

	/** {@code rel} holds pack-chosen names (dimension folders, texture names): it must not lead out of the output dir. */
	private static void write(Path dir, String rel, byte[] bytes) throws IOException {
		dir = dir.toAbsolutePath().normalize();
		Path file = dir.resolve(rel).normalize();
		if (!file.startsWith(dir) || file.equals(dir)) throw new IOException("output path leaves the cache dir: " + rel);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	/** Fills compiled SPIR-V paths into a planned dimension and removes programs with any failed stage. */
	static final class PlanFiller {
		private PlanFiller() {}

		static Manifest.Dimension fill(Manifest.Dimension planned, List<StageResult> results, Diagnostics diagnostics) {
			Map<String, List<StageResult>> byProgram = new LinkedHashMap<>();
			results.forEach(r -> byProgram.computeIfAbsent(r.job().program(), k -> new ArrayList<>()).add(r));
			Map<String, Manifest.Program> programs = new TreeMap<>();
			for (var e : new TreeMap<>(planned.programs()).entrySet()) {
				List<StageResult> rs = byProgram.get(e.getKey());
				if (rs == null) {
					programs.put(e.getKey(), e.getValue());
					continue;
				}
				var failed = rs.stream().filter(r -> r.spv() == null).toList();
				if (!failed.isEmpty()) {
					diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, null, 0, e.getKey(), null,
							"removed from the " + rs.getFirst().job().dim() + " plan: " + failed.size() + " stage(s) failed"));
					continue;
				}
				programs.put(e.getKey(), withStages(e.getValue(), rs));
			}
			return new Manifest.Dimension(programs, planned.resources(), planned.targets(), planned.flipStates(),
					withoutPrograms(planned.frame(), programs, planned.programs()),
					withoutPrograms(planned.once(), programs, planned.programs()), planned.shadow());
		}

		private static Manifest.Program withStages(Manifest.Program p, List<StageResult> rs) {
			Map<StageType, Map<String, String>> variants = new java.util.EnumMap<>(StageType.class);
			String fragment = null, compute = null;
			for (StageResult r : rs) {
				StageJob j = r.job();
				switch (j.stage()) {
					case FRAGMENT -> fragment = j.spvPath();
					case COMPUTE -> compute = j.spvPath();
					default -> variants.computeIfAbsent(j.stage(), k -> new TreeMap<>()).put(j.variant() == null ? "fullscreen" : j.variant(), j.spvPath());
				}
			}
			var stages = new Manifest.Stages(variants(variants, StageType.VERTEX), variants(variants, StageType.TESS_CONTROL),
					variants(variants, StageType.TESS_EVAL), variants(variants, StageType.GEOMETRY), ref(fragment), ref(compute));
			return new Manifest.Program(p.kind(), p.group(), p.fallbackFor(), stages, p.outputs(), p.blend(), p.alphaTest(),
					p.set0(), p.set1(), p.writesStorage(), p.usesPushConstants(), p.localSize(), p.workGroups(),
					p.workGroupsRender(), p.mipmapBefore(), p.viewportScale(), p.renderStageOrdinal(), p.viewportOffset());
		}

		private static Manifest.VertexStage variants(Map<StageType, Map<String, String>> m, StageType t) {
			return m.containsKey(t) ? new Manifest.VertexStage(m.get(t)) : null;
		}

		private static Manifest.SpvRef ref(String path) { return path == null ? null : new Manifest.SpvRef(path); }

		/** Drops commands of programs that were planned but removed (failed). */
		private static List<Manifest.Command> withoutPrograms(List<Manifest.Command> commands, Map<String, Manifest.Program> kept,
		                                                     Map<String, Manifest.Program> planned) {
			if (commands == null) return null;
			return commands.stream().filter(c -> c.program() == null || kept.containsKey(c.program()) || !planned.containsKey(c.program())).toList();
		}
	}
}
