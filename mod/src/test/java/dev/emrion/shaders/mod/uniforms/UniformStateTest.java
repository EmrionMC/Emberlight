package dev.emrion.shaders.mod.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Timer, smoothing, camera shift, Iris directive quirk and std140 writing. */
class UniformStateTest {
	private static final long MS = 1_000_000L;

	@Test
	void frameTimeHasMillisecondResolutionAndCounterResetsAfterAnHour() {
		IrisTimer t = new IrisTimer();
		t.beginFrame(5_000 * MS);
		assertEquals(1, t.frameCounter());
		assertEquals(0, t.frameTime(), 0); // first frame: no previous start
		t.beginFrame(5_016 * MS + 999_999); // 16.999999 ms → 16 ms
		assertEquals(0.016f, t.frameTime(), 0);
		assertEquals(0.016f, t.frameTimeCounter(), 0);
		// Step to just below 3600 s, then one more frame crosses it: Iris resets to 0 (does not keep the remainder).
		long now = 5_016 * MS + 999_999;
		now += 3_599_000 * MS;
		t.beginFrame(now);
		assertEquals(3599.016f, t.frameTimeCounter(), 1e-3f);
		t.beginFrame(now + 1_000 * MS);
		assertEquals(0, t.frameTimeCounter(), 0);
		assertEquals(4, t.frameCounter());
	}

	@Test
	void frameCounterWrapsAt720720() {
		IrisTimer t = new IrisTimer();
		for (int k = 0; k < 720720; k++) t.beginFrame(k);
		assertEquals(0, t.frameCounter());
	}

	@Test
	void wetnessSmoothing() {
		// Complementary: wetnessHalflife = drynessHalflife = 300 → Iris: up 300 (from drynessHalflife), down 200 (default).
		IrisUniformSystem.Directives d = IrisUniformSystem.Directives.fromConsts(300f, 300f, null, true, false);
		assertEquals(300, d.wetnessHalfLife(), 0);
		assertEquals(200, d.drynessHalfLife(), 0);
		assertEquals(10, d.eyeBrightnessHalfLife(), 0);
		assertEquals(600, IrisUniformSystem.Directives.fromConsts(null, null, null, true, false).wetnessHalfLife(), 0);
		assertEquals(500, IrisUniformSystem.Directives.fromConsts(500f, null, null, true, false).wetnessHalfLife(), 0);
		assertEquals(250, IrisUniformSystem.Directives.fromConsts(500f, 250f, null, true, false).wetnessHalfLife(), 0);

		SmoothedFloat w = new SmoothedFloat(d.wetnessHalfLife(), d.drynessHalfLife());
		assertEquals(0, w.update(0, 0.016f), 0); // first sample taken as is
		// Half life 300 ds = 30 s: one 30 s step halves the distance to the target.
		assertEquals(0.5f, w.update(1, 30f), 1e-5f);
		// Going down uses 200 ds = 20 s.
		assertEquals(0.25f, w.update(0, 20f), 1e-5f);
		// Many small steps follow e^(−kt): 30 s of 1/60 s frames upward from 0.25 to 1 → 0.25 + 0.75·0.5
		for (int k = 0; k < 1800; k++) w.update(1, 1 / 60f);
		assertEquals(0.625f, w.get(), 1e-3f);
	}

	@Test
	void cameraShiftKeepsDeltaAndRecentres() {
		CameraTracker c = new CameraTracker();
		c.update(100, 64, 200);
		assertEquals(100, c.current.x, 0);
		c.update(35_000.5, 64, 200); // jump > 1000 → shift by −30000 on x
		assertEquals(5_000.5, c.current.x, 1e-9);
		assertEquals(100 - 30_000, c.previous.x, 1e-9);
		assertEquals(35_000.5, c.currentUnshifted.x, 0);
		c.update(35_001.5, 64, 200);
		assertEquals(5_001.5, c.current.x, 1e-9); // shift persists
		assertEquals(1.0, c.current.x - c.previous.x, 1e-9);
	}

	@Test
	void blockPlanWritesStd140Offsets() {
		assertEquals(16, BlockPlan.words("mat4"));
		assertEquals(12, BlockPlan.words("mat3"));
		assertEquals(3, BlockPlan.words("ivec3"));
		assertEquals(1, BlockPlan.words("bool"));
		// store: [mat4 0..15][vec3 16..18][float 19][ivec2 20..21]; block offsets: mat4 @0, vec3 @64, float @76, ivec2 @80
		int[] store = new int[22];
		for (int k = 0; k < 16; k++) store[k] = Float.floatToRawIntBits(k + 1);
		store[16] = Float.floatToRawIntBits(1.5f);
		store[17] = Float.floatToRawIntBits(2.5f);
		store[18] = Float.floatToRawIntBits(3.5f);
		store[19] = Float.floatToRawIntBits(9f);
		store[20] = -7;
		store[21] = 42;
		BlockPlan plan = new BlockPlan(new int[]{0, 16, 19, 20}, new int[]{0, 64, 76, 80}, new int[]{16, 3, 1, 2});
		ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
		buf.position(128); // writes are relative to the position (ring-buffer slot)
		plan.write(buf, store);
		assertEquals(128, buf.position());
		assertEquals(1f, buf.getFloat(128), 0);
		assertEquals(16f, buf.getFloat(128 + 60), 0);
		assertEquals(1.5f, buf.getFloat(128 + 64), 0);
		assertEquals(3.5f, buf.getFloat(128 + 72), 0);
		assertEquals(9f, buf.getFloat(128 + 76), 0);
		assertEquals(-7, buf.getInt(128 + 80));
		assertEquals(42, buf.getInt(128 + 84));
		assertEquals(0, buf.getInt(128 + 88));
	}
}
