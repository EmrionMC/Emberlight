package dev.emrion.shaders.converter.report;

import java.util.List;

/** report.json. {@code crashed}: a converter crash, not a pack error (the cache converts again instead of reusing it). */
public record Report(boolean ok, int programs, int stagesCompiled, int stagesFailed, List<Diagnostic> diagnostics, boolean crashed) {}
