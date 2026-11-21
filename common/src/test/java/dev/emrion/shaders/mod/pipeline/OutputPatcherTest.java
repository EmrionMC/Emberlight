package dev.emrion.shaders.mod.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class OutputPatcherTest {
	// Header + OpDecorate %10 Location 1, %11 Location 1, OpVariable %5 %10 Output, OpVariable %5 %11 Input.
	private static byte[] module() {
		int[] w = {0x07230203, 0x00010500, 0, 20, 0,
				(4 << 16) | 71, 10, 30, 1, (4 << 16) | 71, 11, 30, 1,
				(4 << 16) | 59, 5, 10, 3, (4 << 16) | 59, 5, 11, 1};
		ByteBuffer b = ByteBuffer.allocate(w.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : w) b.putInt(v);
		return b.array();
	}

	@Test
	void movesOnlyOutputs() {
		byte[] spv = module();
		OutputPatcher.patch(spv, new int[] {0, 4});
		ByteBuffer b = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(4, b.getInt(8 * 4)); // output %10: location 1 -> attachment 4
		assertEquals(1, b.getInt(12 * 4)); // input %11 untouched
	}
}
