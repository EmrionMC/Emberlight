package dev.emrion.shaders.converter.glsl;

import dev.emrion.shaders.converter.rt.RtPatchTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RtPatcherPrefilterTest {
	static RtPatchTable.Effect effect(RtPatchTable.Patch... patches) {
		return new RtPatchTable.Effect(null, null, null, null, null, null, null, null, List.of(patches), null, null, null, null, null, null);
	}

	@Test
	void looksForThePatchedName() {
		var e = effect(new RtPatchTable.Patch("vec3 GetShadow(vec3, float)|vec3 GetShadowOld(vec3)", "x"),
				new RtPatchTable.Patch("vec2[8]\toffsets", "x"), new RtPatchTable.Patch("float grid[2] [3]", "x"),
				new RtPatchTable.Patch("void\nmainImage()", "x", List.of("composite"), null, false));
		assertTrue(RtPatcher.mayMatch(e, "gbuffers_terrain", "vec3 GetShadowOld(vec3 p) { }"));
		assertTrue(RtPatcher.mayMatch(e, "gbuffers_terrain", "const vec2 offsets[8] = vec2[8](...);"));
		assertTrue(RtPatcher.mayMatch(e, "gbuffers_terrain", "float grid [2][3] = ...;"));
		assertTrue(RtPatcher.mayMatch(e, "composite", "void mainImage() {}"));
		assertFalse(RtPatcher.mayMatch(e, "gbuffers_terrain", "void mainImage() {}"), "patch is for composite only");
		assertFalse(RtPatcher.mayMatch(e, "gbuffers_terrain", "void main() { float offset; }"));
	}
}
