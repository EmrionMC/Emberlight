package dev.emrion.shaders.converter.plan;

import dev.emrion.shaders.converter.glsl.Transpiler;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;

import java.util.Collection;
import java.util.List;

/**
 * Union of all referenced non-opaque uniforms over every program of every dimension → std140 EmrionUniforms layout,
 * EmrionPhase and EmrionDraw (push constant) blocks (FORMAT.md §6). Built-in types come from UniformCatalog, custom
 * ones from the parsed custom uniforms. Owner: agent (c2).
 */
public interface UniformLayoutBuilder {
	Manifest.UniformLayout build(Collection<Transpiler.ProgramAnalysis> analyses, List<Manifest.CustomUniform> custom, Diagnostics diagnostics);
}
