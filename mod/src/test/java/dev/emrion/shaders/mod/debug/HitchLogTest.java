package dev.emrion.shaders.mod.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HitchLogTest {
	private static final long MS = 1_000_000L;

	@Test
	void medianIgnoresSpikesAndRolls() {
		var w = new HitchLog.Window(5);
		for (long ms : new long[] {10, 12, 11, 90, 13}) w.add(ms * MS);
		assertTrue(w.full());
		assertEquals(12 * MS, w.median());
		for (int i = 0; i < 5; i++) w.add(20 * MS); // the old frames roll out
		assertEquals(20 * MS, w.median());
	}

	@Test
	void hitchThresholds() {
		assertFalse(HitchLog.Window.isHitch(24 * MS, 5 * MS)); // under the 25 ms floor
		assertTrue(HitchLog.Window.isHitch(30 * MS, 12 * MS)); // > 2 × median
		assertFalse(HitchLog.Window.isHitch(30 * MS, 16 * MS)); // 2 × 16 = 32
		assertFalse(HitchLog.Window.isHitch(45 * MS, 40 * MS)); // under median + 8 ms
		assertTrue(HitchLog.Window.isHitch(49 * MS, 20 * MS));
	}
}
