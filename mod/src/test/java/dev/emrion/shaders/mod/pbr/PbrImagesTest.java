package dev.emrion.shaders.mod.pbr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PbrImagesTest {
	@Test
	void normalsAverageEveryChannel() {
		assertEquals(0x20202020, PbrImages.blend(0, 0, 0, 0x80808080, false));
		assertEquals(0xFF7F7FFF, PbrImages.blend(0xFF7F7FFF, 0xFF7F7FFF, 0xFF7F7FFF, 0xFF7F7FFF, false));
	}

	@Test
	void specularKeepsMetalIdsAndEmissionApart() {
		// G: three non-metal texels (avg 30) beat one iron (230); A: one emissive texel (100) loses to three "no emission" (255).
		int a = 0xFF000A00, b = 0xFF001E00, c = 0xFF003200, iron = 0x64E60000 | 0xE600;
		int out = PbrImages.blend(a, b, iron, c, true);
		assertEquals(0x1E, out >>> 8 & 0xFF);
		assertEquals(0xFF, out >>> 24);
	}

	@Test
	void scalingAnAnimationSheetKeepsFramesApart() {
		// two 3x3 frames stacked (black, white) scaled to 4x4 frames: no white may bleed into frame 0's last row
		try (var src = new com.mojang.blaze3d.platform.NativeImage(3, 6, true)) {
			src.fillRect(0, 3, 3, 3, 0xFFFFFFFF);
			try (var out = PbrImages.scale(src, 4, 8, 1, 2)) {
				for (int x = 0; x < 4; x++) {
					assertEquals(0, out.getPixel(x, 3));
					assertEquals(0xFFFFFFFF, out.getPixel(x, 4));
				}
			}
		}
	}

	@Test
	void majorityTiesGoToTheEarlierArgument() {
		assertEquals(0, PbrImages.majority(0, 1, 1, 0));
		assertEquals(1, PbrImages.majority(0, 1, 2, 1));
		assertEquals(2, PbrImages.majority(0, 1, 2, 2));
		assertEquals(0, PbrImages.majority(0, 1, 2, 3));
	}
}
