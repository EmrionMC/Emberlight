package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class ReceiverGridTest {
	@Test
	void sunOverheadKeepsOnlyCastersAboveAReceiver() {
		var g = new ReceiverGrid();
		g.begin(new Matrix4f().rotateX((float) Math.toRadians(90))); // view +Z = world +Y: the light straight above
		g.addReceiver(0, 0, 0, 16, 16, 16);
		assertTrue(g.casts(0, 40, 0, 16, 56, 16), "above the receiver");
		assertTrue(g.casts(20, 40, 0, 36, 56, 16), "above, inside the filter margin");
		assertFalse(g.casts(0, -40, 0, 16, -24, 16), "below the receiver");
		assertFalse(g.casts(100, 40, 0, 116, 56, 16), "above, but beside the receiver");
	}
}
