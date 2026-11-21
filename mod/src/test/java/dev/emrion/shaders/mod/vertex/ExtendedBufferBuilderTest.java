package dev.emrion.shaders.mod.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExtendedBufferBuilderTest {
	/** {position offset, then (src offset, dst offset, bytes) for Color, UV0, UV1, UV2, Normal}. */
	@Test
	void sodiumEntityVertexIsOneBlock() {
		assertEquals(36, ExtendedBufferBuilder.prefix(new int[] {0, 12, 12, 4, 16, 16, 8, 24, 24, 4, 28, 28, 4, 32, 32, 4}));
	}

	@Test
	void movedOrGappedElementsCopyOneByOne() {
		assertEquals(0, ExtendedBufferBuilder.prefix(new int[] {0, 12, 12, 4, 16, 16, 8, 24, 24, 4, 28, 28, 4, 36, 32, 4}));
		assertEquals(0, ExtendedBufferBuilder.prefix(new int[] {0, 12, 12, 4, 16, 16, 8, 24, 24, 4, 28, 28, 4, 36, 36, 4}));
		assertEquals(0, ExtendedBufferBuilder.prefix(new int[] {4, 16, 16, 4}));
	}
}
