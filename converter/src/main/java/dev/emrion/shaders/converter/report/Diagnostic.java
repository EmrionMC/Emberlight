package dev.emrion.shaders.converter.report;

/** One finding. file is pack-relative (e.g. "shaders/program/gbuffers_terrain.glsl"); line is 1-based, 0 = unknown. */
public record Diagnostic(Severity severity, String file, int line, String program, String stage, String message) {
	public enum Severity { ERROR, WARNING, INFO }

	public static Diagnostic error(String file, int line, String message) {
		return new Diagnostic(Severity.ERROR, file, line, null, null, message);
	}

	public static Diagnostic warning(String file, int line, String message) {
		return new Diagnostic(Severity.WARNING, file, line, null, null, message);
	}

	public Diagnostic in(String program, String stage) {
		return new Diagnostic(severity, file, line, program, stage, message);
	}

	@Override
	public String toString() {
		return severity + " " + (file == null ? "?" : file) + ":" + line
				+ (program == null ? "" : " [" + program + (stage == null ? "" : "." + stage) + "]") + " " + message;
	}
}
