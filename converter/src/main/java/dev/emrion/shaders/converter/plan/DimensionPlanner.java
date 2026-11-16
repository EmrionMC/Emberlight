package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.pack.ResolvedPack;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/** Builds one dimension's plan: programs, resources, targets, flip schedule, command lists and shadow settings. */
public interface DimensionPlanner {

	DimensionPlan plan(
			String dimension,
			List<ResolvedPack.ProgramSource> programs,          // enabled and disabled programs of this dimension
			Map<String, Transpiler.ProgramAnalysis> analyses,    // program name → analysis (only enabled programs that analysed OK)
			SequencedMap<String, String> properties,             // preprocessed shaders.properties
			Map<String, String> optionValues,                    // resolved option values (some properties depend on them)
			Manifest.UniformLayout uniforms,
			Diagnostics diagnostics);

	/** {@code dimension} has its SPIR-V paths left null; the converter fills them in. */
	record DimensionPlan(Manifest.Dimension dimension, Map<String, ProgramEmit> emits, List<Manifest.TextureDef> textures) {}

	record ProgramEmit(Transpiler.EmitContext context, List<String> vertexVariants) {}
}
