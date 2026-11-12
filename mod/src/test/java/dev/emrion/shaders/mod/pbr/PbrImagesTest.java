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
	void majorityTiesGoToTheEarlierArgument() {
		assertEquals(0, PbrImages.majority(0, 1, 1, 0));
		assertEquals(1, PbrImages.majority(0, 1, 2, 1));
		assertEquals(2, PbrImages.majority(0, 1, 2, 2));
		assertEquals(0, PbrImages.majority(0, 1, 2, 3));
	}
}
