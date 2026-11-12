package dev.emrion.shaders.converter.glsl;

/** Iris program groups (iris-spec §1.1). GBUFFERS and SHADOW draw MC geometry; the rest are fullscreen/compute passes. */
public enum ProgramGroup {
	SETUP, BEGIN, SHADOW, SHADOWCOMP, PREPARE, GBUFFERS, DEFERRED, COMPOSITE, FINAL;

	public boolean isGeometry() { return this == GBUFFERS || this == SHADOW; }
}
