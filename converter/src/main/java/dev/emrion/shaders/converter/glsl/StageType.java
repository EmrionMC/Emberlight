package dev.emrion.shaders.converter.glsl;

public enum StageType {
	VERTEX("vsh"), TESS_CONTROL("tcs"), TESS_EVAL("tes"), GEOMETRY("gsh"), FRAGMENT("fsh"), COMPUTE("csh"); // pipeline order

	/** Runs between the vertex and the fragment stage (compiled per vertex variant like the vertex stage). */
	public boolean intermediate() { return this == TESS_CONTROL || this == TESS_EVAL || this == GEOMETRY; }

	public final String extension;

	StageType(String extension) { this.extension = extension; }
}
