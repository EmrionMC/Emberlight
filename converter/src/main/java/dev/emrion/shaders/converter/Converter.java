package dev.emrion.shaders.converter;

import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.format.Manifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Facade used by the CLI and the mod.
 */
public interface Converter {
	String VERSION = "0.1.0-alpha.1.41"; // bump on every change to converter output: it is part of the cache key
	/** Bump on every change to the terrain_sodium output only (cache key of Sodium requests; VERSION would change every output). */
	String SODIUM_VARIANT = "5";

	/**
	 * @param settings Iris settings .txt, may be null (= all defaults)
	 * @param rt       RT effects to patch in, e.g. "shadows:full"; empty = plain Iris-equivalent output
	 * @param sodium   also emit the terrain_sodium vertex variant
	 */
	record Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress, boolean useCache,
	               java.util.Set<String> rt, boolean sodium) {
		public Request {
			rt = rt == null ? java.util.Set.of() : java.util.Set.copyOf(rt);
		}

		public Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress, boolean useCache,
		               java.util.Set<String> rt) {
			this(pack, settings, cacheRoot, dimensions, progress, useCache, rt, false);
		}

		public Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress, boolean useCache) {
			this(pack, settings, cacheRoot, dimensions, progress, useCache, java.util.Set.of());
		}

		public Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress) {
			this(pack, settings, cacheRoot, dimensions, progress, true);
		}
	}

	/** manifest is null only if the pack could not be read at all. cacheHit = nothing was recompiled. */
	record Result(Path outputDir, Manifest manifest, List<Diagnostic> diagnostics, boolean cacheHit,
	              int stagesCompiled, int stagesFailed) {}

	interface ProgressListener {
		ProgressListener NONE = (stage, done, total) -> {};

		void progress(String stage, int done, int total);
	}

	Result convert(Request request);
}
