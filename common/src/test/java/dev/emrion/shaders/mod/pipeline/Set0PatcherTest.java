package dev.emrion.shaders.mod.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class Set0PatcherTest {
	// Header + OpDecorate %10 DescriptorSet 0, %10 Binding 3, %11 DescriptorSet 1, %11 Binding 3.
	private static byte[] module() {
		int[] w = {0x07230203, 0x00010500, 0, 20, 0,
				(4 << 16) | 71, 10, 34, 0, (4 << 16) | 71, 10, 33, 3,
				(4 << 16) | 71, 11, 34, 1, (4 << 16) | 71, 11, 33, 3};
		ByteBuffer b = ByteBuffer.allocate(w.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : w) b.putInt(v);
		return b.array();
	}

	private static int word(byte[] spv, int i) {
		return ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).getInt(i * 4);
	}

	@Test
	void patchesOnlySet0() {
		byte[] spv = module();
		Set0Patcher.patch(spv, List.of("A", "B", "C", "Sampler0"), List.of("Globals", "Sampler0", "Projection"));
		assertEquals(1, word(spv, 12)); // set 0 binding: Sampler0 is index 1 in the vanilla order
		assertEquals(3, word(spv, 20)); // set 1 untouched
	}

	@Test
	void missingResourceFails() {
		assertThrows(IllegalArgumentException.class, () -> Set0Patcher.patch(module(), List.of("A", "B", "C", "Sampler1"), List.of("Sampler0")));
	}
}
