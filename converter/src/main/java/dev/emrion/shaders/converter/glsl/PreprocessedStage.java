package dev.emrion.shaders.converter.glsl;

/** One preprocessed stage. {@code directives} come from the comment-preserving text (Iris reads them from comments). */
public record PreprocessedStage(
		String program,          // e.g. "gbuffers_terrain", "composite1", "shadowcomp"
		ProgramGroup group,
		StageType stage,
		String sourcePath,       // pack path of the stage file, e.g. "shaders/world0/gbuffers_terrain.vsh"
		String text,
		LineMap lineMap,
		Directives directives
) {}
