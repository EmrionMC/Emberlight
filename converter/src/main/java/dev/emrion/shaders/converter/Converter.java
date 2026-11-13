package dev.emrion.shaders.converter;

import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.format.Manifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Facade used by the CLI and the mod. Owner: agent (c) (orchestration), wiring stages of (a) and (b).
 */
public interface Converter {
	String VERSION = "0.2.0"; // bump on every change to converter output: it is part of the cache key

	/**
	 * @param pack     zip or folder
	 * @param settings Iris settings .txt, may be null (= all defaults)
	 * @param cacheRoot output root; result goes to cacheRoot/&lt;cacheKey&gt;/
	 * @param dimensions dimension folders to convert, null or empty = all
	 * @param useCache  false = convert even if a valid cache directory exists (and replace it)
	 * @param rt        RT effects to patch in (RAYTRACING.md §3.2), e.g. "shadows" or "shadows:full"; empty = today's output exactly
	 */
	record Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress, boolean useCache,
	               java.util.Set<String> rt) {
		public Request {
			rt = rt == null ? java.util.Set.of() : java.util.Set.copyOf(rt);
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
