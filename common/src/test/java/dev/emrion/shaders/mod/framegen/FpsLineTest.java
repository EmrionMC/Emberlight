package dev.emrion.shaders.mod.framegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FpsLineTest {
	@Test
	void generatedFramesAfterTheUnit() {
		assertEquals("120 fps (60 generated) T: inf (immediate) @144Hz", Presenter.withGenerated("60 fps T: inf (immediate) @144Hz", 60, 120, 60));
		assertEquals("120 fps (61 generated) / Unlimited fps", Presenter.withGenerated("59 fps / Unlimited fps", 59, 120, 61));
	}

	@Test
	void unchangedWithoutPresentsOrForeignText() {
		assertEquals("60 fps T: inf", Presenter.withGenerated("60 fps T: inf", 60, 60, 0)); // presenting real frames only
		assertEquals("60 fps T: inf", Presenter.withGenerated("60 fps T: inf", 60, 0, 0));
		assertEquals("61 fps T: inf", Presenter.withGenerated("61 fps T: inf", 60, 120, 60)); // rendered changed in between
	}
}
