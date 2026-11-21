package dev.emrion.shaders.mod.runtime;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HazardsTest {
	@Test
	void rawWarAndRedundantReads() {
		var h = new Hazards();
		var a = new Hazards.Tracked("a");
		var b = new Hazards.Tracked("b");
		h.write(a, COLOR_OUT, COLOR_WRITE);
		assertNull(h.flushMasks(), "first write in a fresh command buffer needs no barrier");

		h.read(a, FRAGMENT, SAMPLED_READ);
		h.write(b, COLOR_OUT, COLOR_WRITE);
		assertArrayEquals(new long[] {COLOR_OUT, COLOR_WRITE, FRAGMENT, SAMPLED_READ}, h.flushMasks(), "read-after-write");

		h.read(a, FRAGMENT, SAMPLED_READ);
		assertNull(h.flushMasks(), "the write is already visible to fragment sampling");

		h.write(a, COLOR_OUT, COLOR_WRITE);
		long[] war = h.flushMasks();
		assertEquals(FRAGMENT | COLOR_OUT, war[0], "write-after-read waits for the readers (and write-after-write for the old write)");
		assertEquals(COLOR_WRITE, war[1]);

		h.reset();
		h.read(a, COMPUTE, SAMPLED_READ);
		assertNull(h.flushMasks(), "a new command buffer starts clean");
	}

	@Test
	void visibilityNeedsStageAndAccessTogether() {
		var h = new Hazards();
		var a = new Hazards.Tracked("a");
		h.write(a, COMPUTE, STORAGE_WRITE);
		h.flushMasks();
		h.read(a, FRAGMENT, STORAGE_READ);
		assertNotNull(h.flushMasks());
		h.read(a, COMPUTE, SAMPLED_READ);
		assertNotNull(h.flushMasks());
		h.read(a, FRAGMENT, SAMPLED_READ);
		long[] m = h.flushMasks();
		assertNotNull(m, "neither barrier made the write visible to fragment sampling");
		assertArrayEquals(new long[] {COMPUTE, STORAGE_WRITE, FRAGMENT, SAMPLED_READ}, m);
		h.read(a, FRAGMENT, SAMPLED_READ);
		assertNull(h.flushMasks(), "now it is");
	}

	@Test
	void attachmentLoadReadsThePreviousWrite() {
		var h = new Hazards();
		var a = new Hazards.Tracked("a");
		h.write(a, TRANSFER, TRANSFER_WRITE); // clear
		h.flushMasks();
		h.write(a, COLOR_OUT, FrameExecutor.LOADED_WRITE); // unblended fullscreen pass: loadOp LOAD, then write
		assertArrayEquals(new long[] {TRANSFER, TRANSFER_WRITE, COLOR_OUT, COLOR_WRITE | COLOR_READ}, h.flushMasks(),
				"the load is a colour-attachment read of the clear");
	}
}
