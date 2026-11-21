package dev.emrion.shaders.mod.photo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PhotoModeTest {
	@Test
	void averagesInLinearLightAndFlipsRows() {
		// 2x2 RGBA8, bottom row first: black/white on the bottom row, two mid greys on the top row
		ByteBuffer frame = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(0, 0xFF000000).putInt(4, 0xFFFFFFFF).putInt(8, 0xFF808080).putInt(12, 0xFF808080);
		float[] full = new float[12];
		PhotoMode.accumulate(frame, 2, 2, 1, full);
		assertEquals(0x80, PhotoMode.encode(full[0], 1)); // output row 0 = the top row of the frame
		assertEquals(0, PhotoMode.encode(full[6], 1));
		assertEquals(255, PhotoMode.encode(full[9], 1));
		float[] half = new float[3];
		PhotoMode.accumulate(frame, 2, 2, 2, half);
		PhotoMode.accumulate(frame, 2, 2, 2, half);
		// linear mean of 0, 1, 0.216, 0.216 = 0.358 -> sRGB 161, not the 0x60 an sRGB-space mean would give
		assertEquals(161, PhotoMode.encode(half[1], 8));
	}
}
