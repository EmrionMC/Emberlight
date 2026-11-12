package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.DefaultConverter;
import dev.emrion.shaders.converter.expr.CustomUniformParser;
import dev.emrion.shaders.converter.glsl.GlslTranspiler;
import dev.emrion.shaders.converter.pack.DefaultPackLoader;
import dev.emrion.shaders.converter.plan.IrisDimensionPlanner;
import dev.emrion.shaders.converter.plan.Std140LayoutBuilder;
import dev.emrion.shaders.converter.pack.PackSource;
import dev.emrion.shaders.converter.pack.StandardMacros;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.converter.spirv.SpirvCompiler;
import dev.emrion.shaders.format.Manifest;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** {@code java -jar converter-all.jar <pack.zip|dir> <settings.txt|-> <out-dir> [--dimension world0] [--no-cache] [--verbose]} */
public final class ConverterCli {
	private ConverterCli() {}

	static final int SHOWN_DIAGNOSTICS = 20;
	static final String USAGE = "usage: <pack.zip|dir> <settings.txt|-> <out-dir> [--dimension <folder>]... [--no-cache] [--verbose]";

	public static void main(String[] args) {
		int code;
		try (SpirvCompiler compiler = new ShadercSpirvCompiler()) {
			code = run(args, () -> defaultConverter(compiler), System.out);
		}
		System.exit(code);
	}

	static Converter defaultConverter(SpirvCompiler compiler) {
		var env = StandardMacros.Environment.DEFAULT;
		return new DefaultConverter(PackSource::open, new DefaultPackLoader(), CustomUniformParser::parse, new GlslTranspiler(),
				new Std140LayoutBuilder(), new IrisDimensionPlanner(), compiler,
				new Manifest.Environment(env.mcVersion(), StandardMacros.build(env)), Runtime.getRuntime().availableProcessors());
	}

	/** @return exit code: 0 no errors, 1 conversion errors, 2 usage */
	static int run(String[] args, Supplier<Converter> converter, PrintStream out) {
		List<String> positional = new ArrayList<>(), dimensions = new ArrayList<>();
		boolean useCache = true, verbose = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--no-cache" -> useCache = false;
				case "--verbose" -> verbose = true;
				case "--dimension" -> {
					if (++i == args.length) return usage(out, "--dimension needs a value");
					dimensions.add(args[i]);
				}
				default -> {
					if (args[i].startsWith("--")) return usage(out, "unknown option " + args[i]);
					positional.add(args[i]);
				}
			}
		}
		if (positional.size() != 3) return usage(out, "expected 3 arguments, got " + positional.size());

		boolean v = verbose;
		Converter.ProgressListener progress = (stage, done, total) -> {
			if (v && done == total) out.println("  " + stage + " " + done + "/" + total);
		};
		long start = System.nanoTime();
		var result = converter.get().convert(new Converter.Request(Path.of(positional.get(0)),
				positional.get(1).equals("-") ? null : Path.of(positional.get(1)), Path.of(positional.get(2)),
				dimensions.isEmpty() ? null : dimensions, progress, useCache));
		long ms = (System.nanoTime() - start) / 1_000_000;

		int programs = result.manifest() == null || result.manifest().dimensions() == null ? 0
				: result.manifest().dimensions().values().stream().mapToInt(d -> d.programs().size()).sum();
		long errors = result.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
		out.printf("programs: %d, stages ok: %d, failed: %d, errors: %d, warnings: %d%n", programs, result.stagesCompiled(),
				result.stagesFailed(), errors, result.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count());
		out.printf("time: %d ms%s, cache: %s%n", ms, result.cacheHit() ? " (cache hit)" : "", result.outputDir());
		int shown = verbose ? result.diagnostics().size() : Math.min(SHOWN_DIAGNOSTICS, result.diagnostics().size());
		result.diagnostics().stream().limit(shown).forEach(d -> out.println("  " + d));
		if (shown < result.diagnostics().size())
			out.println("  … " + (result.diagnostics().size() - shown) + " more (see report.json or --verbose)");
		return errors == 0 && result.manifest() != null ? 0 : 1;
	}

	private static int usage(PrintStream out, String problem) {
		out.println(problem);
		out.println(USAGE);
		return 2;
	}
}
