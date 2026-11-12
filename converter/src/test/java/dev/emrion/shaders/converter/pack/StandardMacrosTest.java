package dev.emrion.shaders.converter.pack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StandardMacrosTest {
	private final Map<String, String> m = StandardMacros.build(StandardMacros.Environment.DEFAULT);

	@Test
	void versionsAndPlatform() {
		assertEquals("260300", m.get("MC_VERSION"));
		assertEquals("11107", m.get("IRIS_VERSION"));
		assertEquals("460", m.get("MC_GL_VERSION"));
		assertEquals("460", m.get("MC_GLSL_VERSION"));
		assertEquals("", m.get("MC_OS_LINUX"));
		assertEquals("", m.get("MC_GL_VENDOR_NVIDIA"));
		assertEquals("", m.get("MC_GL_RENDERER_GEFORCE"));
		assertFalse(m.containsKey("MC_OS_MAC"));
		assertEquals("12104", StandardMacros.formatVersion("1.21.4"));
	}

	@Test
	void irisFlags() {
		assertEquals("", m.get("IS_IRIS"));
		assertEquals("2", m.get("IRIS_TAG_SUPPORT"));
		assertEquals("32", m.get("MAX_COLOR_BUFFERS"));
		assertEquals("0.125", m.get("MC_HAND_DEPTH"));
		for (String f : new String[]{"CUSTOM_IMAGES", "SSBO", "BLOCK_EMISSION_ATTRIBUTE", "FADE_VARIABLE", "ENTITY_TRANSLUCENT"}) {
			assertEquals("", m.get("IRIS_FEATURE_" + f));
		}
	}

	@Test
	void renderStageOrdinals() {
		assertEquals("0", m.get("MC_RENDER_STAGE_NONE"));
		assertEquals("6", m.get("MC_RENDER_STAGE_STARS"));
		assertEquals("8", m.get("MC_RENDER_STAGE_TERRAIN_SOLID"));
		assertEquals("17", m.get("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT"));
		assertEquals("23", m.get("MC_RENDER_STAGE_HAND_TRANSLUCENT"));
	}

	@Test
	void biomesCategoriesPrecipitation() {
		assertEquals("0", m.get("BIOME_THE_VOID"));
		assertEquals("13", m.get("BIOME_PALE_GARDEN"));
		assertEquals("56", m.get("BIOME_SULFUR_CAVES"));
		assertEquals("62", m.get("BIOME_THE_END"));
		assertEquals("66", m.get("BIOME_END_BARRENS"));
		assertEquals("4", m.get("CAT_MESA"));
		assertEquals("2", m.get("PPT_SNOW"));
		assertEquals("15", m.get("DH_BLOCK_ILLUMINATED"));
	}
}
