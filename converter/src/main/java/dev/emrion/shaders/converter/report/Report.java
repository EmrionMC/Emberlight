package dev.emrion.shaders.converter.report;

import java.util.List;

/** report.json (FORMAT.md §9). */
public record Report(boolean ok, int programs, int stagesCompiled, int stagesFailed, List<Diagnostic> diagnostics) {}
