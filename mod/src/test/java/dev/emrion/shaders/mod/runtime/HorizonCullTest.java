package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HorizonCullTest {
	@Test
	void cylinderAroundTheCamera() {
		float d = 16 * 16;
		assertTrue(HorizonCull.inRenderDistance(0, 64, 0, 8, 70, 8, d)); // camera section
		assertTrue(HorizonCull.inRenderDistance(256, 64, 0, 8, 70, 8, d)); // nearest x 255 - 8 = 247 < 256
		assertFalse(HorizonCull.inRenderDistance(272, 64, 0, 8, 70, 8, d));
		// corner: vanilla keeps (15, 15) chunks away, the cylinder does not (nearest point ~ (231, 231) -> 327 blocks)
		assertFalse(HorizonCull.inRenderDistance(240, 64, 240, 8, 70, 8, d));
		assertTrue(HorizonCull.inRenderDistance(176, 64, 176, 8, 70, 8, d)); // (167, 167) -> 236
		assertFalse(HorizonCull.inRenderDistance(0, 400, 0, 8, 70, 8, d)); // too far above
	}
}
