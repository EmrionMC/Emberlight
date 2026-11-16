package dev.emrion.shaders.mod.rt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RtLightsTest {
	@Test
	void blockSlotMatchesTheShaderHash() {
		// values of rtx/bl_common.glsl emrBlHash(uvec3(b)) & 32767, computed with unsigned 32-bit arithmetic
		assertEquals(17260, RtLights.blockSlot(1, 2, 3));
		assertEquals(2048, RtLights.blockSlot(-5, 40, 130));
		assertEquals(12060, RtLights.blockSlot(0, 0, 0));
	}

	/** emrBlSourceIn (rtx/bl_common.glsl): the slot of the source in block x y z within BLOCK_PROBES probes, -1 if not found. */
	private static int lookup(int[] block, float[] pos, int x, int y, int z) {
		int slot = RtLights.blockSlot(x, y, z);
		for (int g = 0; g < RtLights.BLOCK_PROBES; g++, slot = (slot + 1) & (block.length - 1)) {
			int e = block[slot];
			if (e == 0) return -1;
			if ((int) Math.floor(pos[(e - 1) * 4]) == x && (int) Math.floor(pos[(e - 1) * 4 + 1]) == y && (int) Math.floor(pos[(e - 1) * 4 + 2]) == z) return e - 1;
		}
		return -1;
	}

	private static void assertAllFound(float[] pos, int count) {
		int[] block = new int[RtLights.BLOCK_SLOTS];
		assertEquals(0, RtLights.fillBlocks(block, pos, count));
		for (int i = 0; i < count; i++)
			assertEquals(i, lookup(block, pos, (int) Math.floor(pos[i * 4]), (int) Math.floor(pos[i * 4 + 1]), (int) Math.floor(pos[i * 4 + 2])), "source " + i);
	}

	@Test
	void everyFullListSourceIsFoundWithinTheShaderProbeLimit() {
		// worst cases at the list cap: a solid 32x16x32 block of emitters (a glowstone or froglight wall, a lava lake), lines and a random scatter
		float[] pos = new float[RtLights.MAX_ALL * 4];
		int n = 0;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 32; z++)
				for (int x = 0; x < 32; x++, n++) {
					pos[n * 4] = 48 + x + 0.5f;
					pos[n * 4 + 1] = 56 + y + 0.5f;
					pos[n * 4 + 2] = 48 + z + 0.5f;
				}
		assertAllFound(pos, n);
		n = 0;
		for (int y = 0; y < 128; y++)
			for (int x = 0; x < 128; x++, n++) { // a full vertical plane of the grid
				pos[n * 4] = x + 0.5f;
				pos[n * 4 + 1] = y + 0.5f;
				pos[n * 4 + 2] = 64.5f;
			}
		assertAllFound(pos, n);
		java.util.Random r = new java.util.Random(1);
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (n = 0; n < RtLights.MAX_ALL; ) {
			int x = r.nextInt(150) - 11, y = r.nextInt(150) - 11, z = r.nextInt(150) - 11;
			if (!seen.add((long) x << 40 | (long) (y & 0xfffff) << 20 | (z & 0xfffff))) continue;
			pos[n * 4] = x + 0.5f;
			pos[n * 4 + 1] = y + 0.5f;
			pos[n * 4 + 2] = z + 0.5f;
			n++;
		}
		assertAllFound(pos, n);
	}

	@Test
	void aSourceBeyondTheProbeLimitIsCountedNotLooped() {
		// every source hashing to one slot: only BLOCK_PROBES fit where the shader looks
		float[] pos = new float[200 * 4];
		int[] block = new int[RtLights.BLOCK_SLOTS];
		assertEquals(200 - RtLights.BLOCK_PROBES, RtLights.fillBlocks(block, pos, 200));
	}
}
