package dev.emrion.shaders.mod.rt;

import static dev.emrion.shaders.mod.rt.RtPreset.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class RtPresetTest {
	private static final long GB = 1L << 30;

	@Test
	void applyRoundTripsThroughConfigAndDetects() {
		for (RtPreset preset : values()) {
			Properties p = new Properties();
			RtConfig.load(p); // defaults
			RtConfig.enabled = true;
			RtConfig.omm = false;
			preset.apply();
			assertTrue(RtConfig.enabled, "a preset leaves rt.enabled alone");
			assertFalse(RtConfig.omm, "a preset leaves rt.omm alone");
			assertEquals(preset, current());
			// every key survives RtConfig's parsing unchanged (a value it clamps or rejects would never be detected)
			Properties stored = new Properties();
			RtConfig.store(stored);
			preset.values.forEach((k, v) -> assertEquals(v, stored.getProperty("rt." + k), preset + " rt." + k));
			assertEquals(preset.name().toLowerCase(), stored.getProperty("rt.preset"));
		}
	}

	@Test
	void clearedExtraIsNotKept() {
		Properties file = new Properties();
		file.setProperty("rt.extra", "gi:debug=2");
		RtConfig.load(file);
		RtConfig.extra = ""; // rtset extra=
		RtConfig.store(file);
		RtConfig.load(file);
		assertEquals("", RtConfig.extra);
	}

	@Test
	void changingOneOptionIsCustom() {
		HIGH.apply();
		RtConfig.ao = !RtConfig.ao;
		assertNull(current());
		Properties stored = new Properties();
		RtConfig.store(stored);
		assertEquals("custom", stored.getProperty("rt.preset"));
		RtConfig.ao = !RtConfig.ao;
		assertEquals(HIGH, current());
	}

	@Test
	void presetsAreDistinctAndOrdered() {
		for (RtPreset a : values())
			for (RtPreset b : values()) if (a != b) assertNotEquals(a.values, b.values);
		assertEquals("off", LOW.values.get("gi"));
		assertEquals("high", ULTRA.values.get("gi"));
		assertEquals(MEDIUM, parse(" medium "));
	}

	@Test
	void gpuTable() {
		assertNull(forGpu(false, NVIDIA, "NVIDIA GeForce GTX 1080 Ti", 11 * GB, false), "no RT: RT off");
		assertEquals(LOW, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 2060", 6 * GB, false));
		assertEquals(LOW, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 3050", 8 * GB, false));
		assertEquals(MEDIUM, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 3060", 12 * GB, false));
		assertEquals(MEDIUM, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 3070 Ti", 8 * GB, false));
		assertEquals(MEDIUM, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4060 Ti", 8 * GB, false));
		assertEquals(HIGH, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 3080", 10 * GB, false));
		assertEquals(HIGH, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4070 Ti", 12 * GB - 300_000_000L, false));
		assertEquals(HIGH, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4070 SUPER", 12 * GB, false));
		assertEquals(ULTRA, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4080 SUPER", 16 * GB, false));
		assertEquals(ULTRA, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4090", 24 * GB, false));
		assertEquals(ULTRA, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 5090", 32 * GB, false));
		assertEquals(ULTRA, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 5070 Ti", 16 * GB, false));
		assertEquals(HIGH, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 5070", 12 * GB, false));
		assertEquals(HIGH, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4080 Laptop GPU", 12 * GB, false), "laptop: one tier down");
		assertEquals(LOW, forGpu(true, AMD, "AMD Radeon RX 6600 (RADV NAVI23)", 8 * GB, false));
		assertEquals(MEDIUM, forGpu(true, AMD, "AMD Radeon RX 6700 XT (RADV NAVI22)", 12 * GB, false));
		assertEquals(MEDIUM, forGpu(true, AMD, "AMD Radeon RX 7600", 8 * GB, false));
		assertEquals(HIGH, forGpu(true, AMD, "AMD Radeon RX 7800 XT (RADV NAVI32)", 16 * GB, false));
		assertEquals(HIGH, forGpu(true, AMD, "AMD Radeon RX 7900 XTX", 24 * GB, false));
		assertEquals(LOW, forGpu(true, INTEL, "Intel(R) Arc(tm) A380 Graphics (DG2)", 6 * GB, false));
		assertEquals(MEDIUM, forGpu(true, INTEL, "Intel(R) Arc(tm) A770 Graphics (DG2)", 16 * GB, false));
		assertEquals(LOW, forGpu(true, AMD, "AMD Radeon 780M (RADV PHOENIX)", 16 * GB, true), "integrated: Low");
		assertEquals(MEDIUM, forGpu(true, 0x1234, "Some Future GPU", 12 * GB, false), "unknown: by VRAM");
		assertEquals(HIGH, forGpu(true, 0x1234, "Some Future GPU", 32 * GB, false), "unknown: at most High");
		assertEquals(LOW, forGpu(true, NVIDIA, "NVIDIA GeForce RTX 4090 D", 6 * GB, false), "< 8 GB: Low");
	}
}
