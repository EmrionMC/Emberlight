package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.report.Diagnostics;

import java.nio.file.Path;
import java.util.Map;

/**
 * Turns a pack + Iris settings file into fully resolved, preprocessed program sources for every dimension.
 */
public interface PackLoader {

	/** @param environmentMacros standard macros from {@link StandardMacros}; MC_RENDER_STAGE_* and per-program ones are added here */
	ResolvedPack load(PackSource source, Path settingsFile, Map<String, String> environmentMacros, Diagnostics diagnostics);
}
