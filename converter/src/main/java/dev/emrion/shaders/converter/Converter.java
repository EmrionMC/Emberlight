package dev.emrion.shaders.converter;

import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.format.Manifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Facade used by the CLI and the mod. Owner: agent (c) (orchestration), wiring stages of (a) and (b).
 */
public interface Converter {
	String VERSION = "0.1.0";

	/**
	 * @param pack     zip or folder
	 * @param settings Iris settings .txt, may be null (= all defaults)
	 * @param cacheRoot output root; result goes to cacheRoot/&lt;cacheKey&gt;/
	 * @param dimensions dimension folders to convert, null or empty = all
	 * @param useCache  false = convert even if a valid cache directory exists (and replace it)
	 */
	record Request(Path pack, Path settings, Path cacheRoot, List<String> dimensions, ProgressListener progress, boolean useCache) {
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
