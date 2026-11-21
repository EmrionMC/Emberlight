package dev.emrion.shaders.mod.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainPrepassTest {
	@Test
	void turnsOffAfterLowFramesAndBackOnAboveTheUpperThreshold() {
		for (int i = 0; i < 7; i++) assertTrue(TerrainPrepass.observe(1.02)); // a street: not yet
		assertTrue(TerrainPrepass.observe(1.4)); // one overdrawn frame resets the count
		for (int i = 0; i < 7; i++) assertTrue(TerrainPrepass.observe(1.02));
		assertFalse(TerrainPrepass.observe(1.02)); // eighth low frame in a row
		assertFalse(TerrainPrepass.observe(1.15)); // between the thresholds: stays off
		assertTrue(TerrainPrepass.observe(1.4)); // a probe sees overdraw: back on
		assertTrue(TerrainPrepass.observe(1.15)); // between the thresholds: stays on
	}
}
