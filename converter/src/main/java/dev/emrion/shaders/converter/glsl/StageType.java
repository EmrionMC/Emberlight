package dev.emrion.shaders.converter.glsl;

public enum StageType {
	VERTEX("vsh"), GEOMETRY("gsh"), FRAGMENT("fsh"), COMPUTE("csh");

	public final String extension;

	StageType(String extension) { this.extension = extension; }
}
