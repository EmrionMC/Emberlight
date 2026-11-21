package dev.emrion.shaders.mod.upscale;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class UpscalingTest {
	@Test
	void haltonMatchesFsrSequence() {
		assertEquals(0.5f, Upscaling.halton(1, 2), 1e-6);
		assertEquals(0.25f, Upscaling.halton(2, 2), 1e-6);
		assertEquals(1f / 3f, Upscaling.halton(1, 3), 1e-6);
		assertEquals(2f / 3f, Upscaling.halton(2, 3), 1e-6);
	}

	@Test
	void offLeavesSizesAndMatricesAlone() {
		UpscaleConfig.mode = UpscaleConfig.Mode.OFF;
		assertArrayEquals(new int[] {2560, 1440}, Upscaling.renderSize(2560, 1440));
		Upscaling.setSizes(2560, 1440, 2560, 1440, false);
		Matrix4f p = new Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 256f), copy = new Matrix4f(p);
		assertFalse(Upscaling.jitter(p, new Matrix4f()));
		assertEquals(copy, p);
		assertEquals(1920, Upscaling.viewWidth(1920));
	}

	@Test
	void jitterIsSubPixelAndPresetSetsRenderSize() {
		UpscaleConfig.mode = UpscaleConfig.Mode.FSR;
		UpscaleConfig.preset = UpscaleConfig.Preset.QUALITY;
		int[] r = Upscaling.renderSize(2560, 1440);
		assertArrayEquals(new int[] {1706, 960}, r);
		Upscaling.setSizes(r[0], r[1], 2560, 1440, true);
		Matrix4f p = new Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 256f), copy = new Matrix4f(p);
		assertTrue(Upscaling.jitter(p, new Matrix4f()));
		// x clip offset per unit w = 2 * jitter / width, |jitter| <= 0.5 px
		float dx = (p.m20() - copy.m20()) / -1f;
		assertTrue(Math.abs(dx) <= 1f / r[0] + 1e-6, "jitter " + dx);
		assertEquals(r[0], Upscaling.viewWidth(2560));
		UpscaleConfig.mode = UpscaleConfig.Mode.OFF;
		Upscaling.setSizes(2560, 1440, 2560, 1440, false);
	}
}
