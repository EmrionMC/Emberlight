package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.PreprocessedStage;
import dev.emrion.shaders.format.Manifest;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Result of {@link PackLoader}. Producer: agent (a).
 *
 * @param options      resolved options (FORMAT.md §4)
 * @param properties   shaders.properties after preprocessing (option values + environment macros applied), keys in file
 *                     order, values trimmed, line continuations joined. Typed interpretation happens in the consumer.
 * @param idMaps       block/item/entity.properties, expanded (FORMAT.md §8)
 * @param dimensionMap dimension.properties (or Iris defaults world0/world-1/world1)
 * @param dimensions   folder name → programs available in that dimension (after program.X.enabled, fallbacks NOT applied)
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
