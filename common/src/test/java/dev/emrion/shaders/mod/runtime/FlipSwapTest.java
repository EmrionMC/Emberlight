package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.format.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlipSwapTest {
	private static Manifest.Program program(float scale, String blend, Integer... set1) {
		var b = new Manifest.Blend(new Manifest.BlendMode(blend, null, null, null, null), Map.of());
		return new Manifest.Program(null, null, null, null, null, b, null, null, List.of(set1), List.of(), false, null, null, null, null, scale, null);
	}

	private static Manifest.Target target(String name, int mips) {
		return new Manifest.Target(name, "color", "RGBA8", "RGBA8", 2, new float[] {1, 1}, null, mips, true, false, null, false, 0);
	}

	private static Manifest.Command full(String program, int flip, String... altTargets) {
		return new Manifest.Command("fullscreen", program, flip, null, null, null, null, null,
				java.util.Arrays.stream(altTargets).map(t -> new Manifest.Attachment(0, t, "alt")).toList(), null);
	}

	private static Manifest.Command copy(String t) {
		return new Manifest.Command("copy", null, null, null, t, null, "alt", "main", null, null);
	}

	@Test
	void swapsOnlyTargetsWhoseAltIsFullyOverwrittenFirst() {
		// colortex1: plain full write. colortex2: blended. colortex3: half viewport. colortex4: discarding program.
		// colortex5: read on its alt side before the write. colortex6: mip chain.
		var frame = List.of(
				new Manifest.Command("geometry", null, 0, "opaque", null, null, null, null, null, null),
				full("a", 0, "colortex1"), full("blend", 0, "colortex2"), full("half", 0, "colortex3"), full("kill", 0, "colortex4"),
				full("reads5", 1, "colortex5"), full("a", 0, "colortex6"),
				copy("colortex1"), copy("colortex2"), copy("colortex3"), copy("colortex4"), copy("colortex5"), copy("colortex6"));
		var dim = new Manifest.Dimension(
				Map.of("a", program(1f, "off"), "blend", program(1f, "custom"), "half", program(0.5f, "off"), "kill", program(1f, "off"),
						"reads5", program(1f, "off", 5)),
				List.of(new Manifest.Resource(5, "sampled_image", null, "colortex5", null, null, null)),
				List.of(target("colortex1", 1), target("colortex2", 1), target("colortex3", 1), target("colortex4", 1), target("colortex5", 1),
						target("colortex6", 0)),
				List.of(new Manifest.FlipState(0, List.of()), new Manifest.FlipState(1, List.of(5))), frame, List.of(), null);
		assertEquals(Set.of("colortex1"), FlipSwap.swappable(dim, "kill"::equals, null));
		assertEquals(Set.of(), FlipSwap.swappable(dim, "kill"::equals, "colortex1"));
	}

	@Test
	void aSmallerAttachmentInTheSamePassBlocksTheSwap() {
		var half = new Manifest.Target("colortex2", "color", "RGBA8", "RGBA8", 2, new float[] {0.5f, 0.5f}, null, 1, true, false, null, false, 0);
		var frame = List.of(full("a", 0, "colortex1"), full("a", 0, "colortex3", "colortex2"), copy("colortex1"), copy("colortex3"));
		var dim = new Manifest.Dimension(Map.of("a", program(1f, "off")), List.of(), List.of(target("colortex1", 1), half, target("colortex3", 1)),
				List.of(new Manifest.FlipState(0, List.of())), frame, List.of(), null);
		assertEquals(Set.of("colortex1"), FlipSwap.swappable(dim, p -> false, null));
	}

	@Test
	void findsDiscardInstructions() {
		var b = java.nio.ByteBuffer.allocate(4 * 7).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x07230203).putInt(0).putInt(0).putInt(10).putInt(0);
		b.putInt(1 << 16 | 253); // OpReturn
		b.putInt(1 << 16 | 252); // OpKill
		assertTrue(FlipSwap.discards(b.array()));
		b.putInt(24, 1 << 16 | 253);
		assertFalse(FlipSwap.discards(b.array()));
	}
}
