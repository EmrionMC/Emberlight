package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.report.Diagnostics;

import java.nio.file.Path;
import java.util.Map;

/**
 * Turns a pack + Iris settings file into fully resolved, preprocessed program sources for every dimension.
 * Owner: agent (a). Consumer: agent (c) (Converter implementation).
 */
public interface PackLoader {

	/**
	 * @param settingsFile Iris settings .txt or null
	 * @param environmentMacros standard macros (MC_VERSION, IS_IRIS, MC_GL_VENDOR_*, MC_OS_*, IRIS_FEATURE_*, ...) — built by
	 *                          {@link StandardMacros}; MC_RENDER_STAGE_* and per-program macros are added by the loader.
	 */
	ResolvedPack load(PackSource source, Path settingsFile, Map<String, String> environmentMacros, Diagnostics diagnostics);
}
