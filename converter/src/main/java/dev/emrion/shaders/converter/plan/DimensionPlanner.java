package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Builds one dimension's plan (FORMAT.md §7): program table with fallbacks and vertex variants, set-1 resource table,
 * targets (formats, clears, sizes, mipmaps), static flip schedule, frame/once command lists, shadow settings,
 * plus the per-program {@link Transpiler.EmitContext} the converter needs to emit GLSL.
 * Owner: agent (c2). Consumer: agent (c1).
 */
public interface DimensionPlanner {

	DimensionPlan plan(
			String dimension,
			List<ResolvedPack.ProgramSource> programs,          // enabled and disabled programs of this dimension
			Map<String, Transpiler.ProgramAnalysis> analyses,    // program name → analysis (only enabled programs that analysed OK)
			SequencedMap<String, String> properties,             // preprocessed shaders.properties
			Map<String, String> optionValues,                    // resolved option values (some properties depend on them)
			Manifest.UniformLayout uniforms,
			Diagnostics diagnostics);

	/**
	 * @param dimension plan with every Program's stage SPIR-V paths left null (the converter fills them in)
	 * @param emits     program name → what to emit (context + vertex variants to compile; "fullscreen" for composite-like)
	 * @param textures  custom textures this dimension needs (merged into the manifest's top-level list by the converter)
	 */
	record DimensionPlan(Manifest.Dimension dimension, Map<String, ProgramEmit> emits, List<Manifest.TextureDef> textures) {}

	record ProgramEmit(Transpiler.EmitContext context, List<String> vertexVariants) {}
}
