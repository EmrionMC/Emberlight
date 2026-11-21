package dev.emrion.shaders.mod.framegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PacingTest {
	@Test
	void steadyFramesGiveHalfTheIntervalMinusTheSafetyMargin() {
		var p = new Presenter.Pacing();
		long t = 1_000_000_000L, delay = 0;
		for (int i = 0; i <= 10; i++) delay = p.update(t + i * 10_000_000L); // 100 FPS, no jitter
		assertEquals(5_000_000L - 100_000L, delay);
	}

	@Test
	void noDelayUntilTenIntervalsAndAfterAGap() {
		var p = new Presenter.Pacing();
		long t = 1_000_000_000L;
		for (int i = 0; i < 10; i++) assertEquals(0, p.update(t + i * 10_000_000L));
		assertEquals(0, p.update(t + 10 * 10_000_000L + 200_000_000L)); // > 100 ms gap restarts
	}

	@Test
	void modeParsing() {
		assertEquals(FrameGenConfig.Mode.FSR, FrameGenConfig.parse(" fsr "));
		assertEquals(FrameGenConfig.Mode.DLSS, FrameGenConfig.parse("DLSS"));
		assertEquals(FrameGenConfig.Mode.OFF, FrameGenConfig.parse("bogus"));
	}
}
