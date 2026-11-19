package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.format.Manifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TlasReadersTest {
	private static Manifest.Program program(String kind, Integer... set1) {
		return new Manifest.Program(kind, null, null, null, null, null, null, null, List.of(set1), List.of(), false, null, null, null, null, 1f, null);
	}

	private static Manifest.Command cmd(String cmd, String program, String phase) {
		return new Manifest.Command(cmd, program, 0, phase, null, null, null, null, null, null);
	}

	private static Manifest.Dimension dim(Map<String, Manifest.Program> programs) {
		var frame = List.of(cmd("fullscreen", "prepare", null), cmd("geometry", null, "shadow_opaque"), cmd("geometry", null, "shadow_translucent"),
				cmd("fullscreen", "shadowcomp", null), cmd("geometry", null, "opaque"), cmd("fullscreen", "deferred", null));
		return new Manifest.Dimension(programs, List.of(new Manifest.Resource(3, "acceleration_structure", null, null, null, "emrion_tlas", null),
				new Manifest.Resource(4, "sampled_image", null, null, null, "emrion_rtao", null)), List.of(), List.of(), frame, List.of(), null);
	}

	@Test
	void onlyPassesBeforeTheShadowEndCount() {
		assertFalse(FrameExecutor.readsTlasBeforeShadowEnd(dim(Map.of("prepare", program("composite"), "shadow", program("shadow"),
				"shadowcomp", program("composite", 3), "deferred", program("composite", 3)))), "shadowcomp and deferred run after the shadow pass");
		assertTrue(FrameExecutor.readsTlasBeforeShadowEnd(dim(Map.of("prepare", program("composite", 3), "shadow", program("shadow"),
				"shadowcomp", program("composite"), "deferred", program("composite")))), "prepare traces before it");
		assertTrue(FrameExecutor.readsTlasBeforeShadowEnd(dim(Map.of("prepare", program("composite", 4), "shadow", program("shadow"),
				"shadowcomp", program("composite"), "deferred", program("composite")))), "the RT AO pass runs before its first reader");
		assertTrue(FrameExecutor.readsTlasBeforeShadowEnd(dim(Map.of("prepare", program("composite"), "shadow", program("shadow", 3),
				"shadowcomp", program("composite"), "deferred", program("composite")))), "a shadow program traces");
	}
}
