package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FrameExecutorViewportTest {
	@Test
	void mixedSizeOutputsKeepTheScissorInsideTheRenderArea() {
		// first output 1920x1080, second at half size: viewport of the first, scissor inside the 960x540 render area
		assertArrayEquals(new int[] {0, 0, 1920, 1080, 0, 0, 960, 540}, FrameExecutor.viewport(1920, 1080, 960, 540, 1f, null));
	}

	@Test
	void scaleAndOffsetAsIris() {
		assertArrayEquals(new int[] {0, 0, 960, 540, 0, 0, 960, 540}, FrameExecutor.viewport(1920, 1080, 1920, 1080, 0.5f, null));
		// a tile at (0.75, 0.5) of size 0.5 overhangs the image: the scissor ends at its edge
		assertArrayEquals(new int[] {1440, 540, 960, 540, 1440, 540, 480, 540},
				FrameExecutor.viewport(1920, 1080, 1920, 1080, 0.5f, new float[] {0.75f, 0.5f}));
	}
}
