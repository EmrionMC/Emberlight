package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.format.Manifest;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Result of {@link PackLoader}.
 * @param properties   shaders.properties after preprocessing, keys in file order
 * @param dimensions   folder name → programs available in that dimension (fallbacks not applied)
 */
public record ResolvedPack(
		PackSource source,
		Manifest.Options options,
		SequencedMap<String, String> properties,
		Manifest.IdMaps idMaps,
		List<Manifest.DimensionMapping> dimensionMap,
		Map<String, List<ProgramSource>> dimensions
) {
	/** One program in one dimension: all its stages, preprocessed. enabled=false if program.&lt;dim&gt;/&lt;name&gt;.enabled evaluates false. */
	public record ProgramSource(String name, boolean enabled, List<PreprocessedStage> stages) {}
}
