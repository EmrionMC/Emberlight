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
	void strongestFirstKeysSortLikeTheStableWeightSort() {
		// the order the overfull-cell path had with a boxed List.sort(Float.compare(b, a)): descending weight, ties in pair order
		java.util.Random r = new java.util.Random(2);
		float[] w = new float[5000];
		for (int i = 0; i < w.length; i++) w[i] = i % 7 == 0 ? (r.nextBoolean() ? 0f : -0f) : i % 5 == 0 ? 3.25f : (r.nextFloat() - 0.5f) * 40;
		Integer[] expected = new Integer[w.length];
		long[] keys = new long[w.length];
		for (int i = 0; i < w.length; i++) {
			expected[i] = i;
			keys[i] = RtLights.strongestFirst(w[i], i);
		}
		java.util.Arrays.sort(expected, (a, b) -> Float.compare(w[b], w[a]));
		java.util.Arrays.sort(keys);
		for (int i = 0; i < w.length; i++) assertEquals(expected[i], (int) keys[i], "position " + i);
	}

	@Test
	void aSourceBeyondTheProbeLimitIsCountedNotLooped() {
		// every source hashing to one slot: only BLOCK_PROBES fit where the shader looks
		float[] pos = new float[200 * 4];
		int[] block = new int[RtLights.BLOCK_SLOTS];
		assertEquals(200 - RtLights.BLOCK_PROBES, RtLights.fillBlocks(block, pos, 200));
	}

	@SuppressWarnings("unchecked")
	@Test
	void aCompileRunningWhenItsSectionEmptiesDoesNotPutItsLightsBack() throws Exception {
		var foundField = RtLights.class.getDeclaredField("FOUND");
		var sectionsField = RtLights.class.getDeclaredField("SECTIONS");
		foundField.setAccessible(true);
		sectionsField.setAccessible(true);
		var found = (ThreadLocal<int[]>) foundField.get(null);
		var sections = (java.util.Map<Long, int[]>) sectionsField.get(null);
		net.minecraft.core.SectionPos a = net.minecraft.core.SectionPos.of(1, 2, 3), b = net.minecraft.core.SectionPos.of(4, 5, 6);
		RtLights.collecting = true;
		try {
			RtLights.begin(); // a torch at 0 0 0, level 14
			found.get()[0] = 1;
			found.get()[1] = 14 << 12;
			RtLights.emptied(a.asLong());
			RtLights.end(a);
			assertNull(sections.get(a.asLong()), "the compile began before the section emptied");
			RtLights.begin(); // a later compile of the refilled section counts
			found.get()[0] = 1;
			found.get()[1] = 14 << 12;
			RtLights.end(a);
			assertNotNull(sections.get(a.asLong()));
			RtLights.begin();
			found.get()[0] = 1;
			found.get()[1] = 14 << 12;
			RtLights.clear(); // world change while compiling
			RtLights.end(b);
			assertNull(sections.get(b.asLong()));
		} finally {
			RtLights.collecting = false;
			RtLights.clear();
		}
	}
}
