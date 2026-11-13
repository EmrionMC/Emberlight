package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.format.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeferredClearsTest {
	private static Manifest.Program program(Integer... set1) {
		return new Manifest.Program(null, null, null, null, null, null, null, null, List.of(set1), List.of(), false, null, null, null, null, 1f, null);
	}

	private static Manifest.Command cmd(String cmd, String program, String phase) {
		return new Manifest.Command(cmd, program, 0, phase, null, null, null, null, null, null);
	}

	@Test
	void movesImageClearsBehindTheirLastUse() {
		var clear = new Manifest.Command("clear", null, null, null, null, null, null, null, null, List.of(
				new Manifest.ClearOp("voxel", "main", null), new Manifest.ClearOp("wsr", "main", null),
				new Manifest.ClearOp("unused", "main", null), new Manifest.ClearOp("colortex0", "both", null)));
		var mip = new Manifest.Command("mipmaps", null, null, null, "colortex5", "main", null, null, null, null);
		var frame = List.of(clear, cmd("geometry", null, "shadow_opaque"), cmd("geometry", null, "opaque"), mip,
				cmd("fullscreen", "composite", null), cmd("final", "final", null));
		var dim = new Manifest.Dimension(
				Map.of("shadow", program(0), "gbuffers_terrain", program(0), "composite", program(1), "final", program()),
				List.of(new Manifest.Resource(0, "storage_image", null, "voxel", null, null, null),
						new Manifest.Resource(1, "sampled_image", null, "wsr", null, null, null)),
				List.of(), List.of(), frame, List.of(), null);
		var d = new DeferredClears(dim, Set.of("voxel", "wsr", "unused")::contains);

		assertEquals(List.of("colortex0"), d.start().get(clear).stream().map(Manifest.ClearOp::name).toList(), "colour targets stay");
		assertEquals(List.of("voxel", "wsr"), d.before()[5].stream().map(Manifest.ClearOp::name).toList(),
				"voxel after its last geometry phase and the mipmaps + composite behind it, wsr after composite (its last reader)");
		for (int i = 0; i < 5; i++) assertNull(d.before()[i]);
		assertNull(d.before()[6], "the never-used image is not cleared again");
	}

	@Test
	void keepsClearsOfImagesUsedBeforeTheClear() {
		var clear = new Manifest.Command("clear", null, null, null, null, null, null, null, null, List.of(new Manifest.ClearOp("voxel", "main", null)));
		var frame = List.of(cmd("compute", "begin", null), clear, cmd("fullscreen", "composite", null));
		var dim = new Manifest.Dimension(Map.of("begin", program(0), "composite", program(0)),
				List.of(new Manifest.Resource(0, "storage_image", null, "voxel", null, null, null)), List.of(), List.of(), frame, List.of(), null);
		var d = new DeferredClears(dim, n -> true);
		assertEquals(1, d.start().get(clear).size());
	}
}
