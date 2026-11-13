package dev.emrion.shaders.mod.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmoothLightTest {
	private static int lm(int block, int sky) { return block << 4 | sky << 20; }

	@Test
	void cornerLightFillsZerosWithSmallestNonZero() {
		// sky 0, 15, 12, 14: the 0 becomes 12 -> (192 + 240 + 192 + 224) / 4 = 212 (MC would use the centre value 14)
		assertEquals(212 << 16, SmoothLight.cornerLight(lm(0, 0), lm(0, 15), lm(0, 12), lm(0, 14), false, false, false, false));
		assertEquals(lm(4, 10), SmoothLight.cornerLight(lm(4, 10), lm(4, 10), lm(4, 10), lm(4, 10), false, false, false, false));
	}

	@Test
	void emissiveCornerCountsAsFullBlockLight() {
		assertEquals(lm(15, 15) & 0xFF, SmoothLight.cornerLight(lm(15, 15), lm(15, 15), lm(15, 15), lm(0, 15), false, false, false, true) & 0xFF);
	}
}
