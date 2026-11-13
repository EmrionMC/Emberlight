package dev.emrion.shaders.converter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RawTexelsTest {
	@Test
	void rgbHalfGetsOpaqueAlpha() {
		byte[] in = {1, 2, 3, 4, 5, 6};
		assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 0x00, 0x3C}, RawTexels.convert(in, "RGB16F", "RGBA16F", "RGB HALF_FLOAT", new int[]{1, 1, 1}));
	}

	@Test
	void bgrBytesAreSwizzled() {
		assertArrayEquals(new byte[]{3, 2, 1, -1}, RawTexels.convert(new byte[]{1, 2, 3}, "RGB8", "RGBA8", "BGR UNSIGNED_BYTE", new int[]{1, 1}));
	}

	@Test
	void singleChannelPassesThroughAndValueConversionsAreRefused() {
		assertArrayEquals(new byte[]{7, 8}, RawTexels.convert(new byte[]{7, 8}, "R8", "R8", "RED UNSIGNED_BYTE", new int[]{2, 1, 1}));
		assertThrows(IllegalArgumentException.class, () -> RawTexels.convert(new byte[8], "RGBA8", "RGBA8", "RGBA FLOAT", new int[]{1, 1}));
		assertThrows(IllegalArgumentException.class, () -> RawTexels.convert(new byte[1], "R8", "R8", "RED UNSIGNED_BYTE", new int[]{2, 1}));
	}
}
