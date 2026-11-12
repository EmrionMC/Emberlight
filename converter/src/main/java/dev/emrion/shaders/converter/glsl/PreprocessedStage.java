package dev.emrion.shaders.converter.glsl;

/**
 * Output of the preprocessor for one program stage: options applied, includes inlined, all conditionals resolved,
 * macros expanded. Only #version, #extension and #pragma remain. Comments are stripped from {@code text}.
 * {@code directives} were parsed from the comment-preserving variant (Iris semantics, iris-spec §3.1).
 * Producer: agent (a). Consumer: agent (b) Transpiler.
 */
public record PreprocessedStage(
		String program,          // e.g. "gbuffers_terrain", "composite1", "shadowcomp"
		ProgramGroup group,
		StageType stage,
		String sourcePath,       // pack path of the stage file, e.g. "shaders/world0/gbuffers_terrain.vsh"
		String text,
		LineMap lineMap,
		Directives directives
) {}
