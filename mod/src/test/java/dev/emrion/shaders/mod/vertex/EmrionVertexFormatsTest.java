package dev.emrion.shaders.mod.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.emrion.shaders.format.VertexLayouts;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmrionVertexFormatsTest {
	static String layout(VertexFormat f) {
		StringBuilder b = new StringBuilder();
		for (VertexFormatElement e : f.getElements()) b.append(e.name()).append('@').append(e.offset()).append(':').append(e.format()).append(' ');
		return b.append(f.getVertexSize()).toString();
	}

	@Test
	void terrainLayout() {
		assertEquals("Position@0:RGB32_FLOAT Color@12:RGBA8_UNORM UV0@16:RG32_FLOAT UV2@24:RG16_SINT Normal@28:RGBA8_SNORM "
				+ "mc_Entity@32:RG16_SINT mc_midTexCoord@36:RG32_FLOAT at_tangent@44:RGBA8_SNORM at_midBlock@48:RGBA16_FLOAT 56",
				layout(EmrionVertexFormats.TERRAIN));
	}

	@Test
	void entityLayout() {
		assertEquals("Position@0:RGB32_FLOAT Color@12:RGBA8_UNORM UV0@16:RG32_FLOAT UV1@24:RG16_SINT UV2@28:RG16_SINT Normal@32:RGBA8_SNORM "
				+ "mc_midTexCoord@36:RG32_FLOAT at_tangent@44:RGBA8_SNORM iris_Entity@48:RGBA16_UINT 56", layout(EmrionVertexFormats.ENTITY));
	}

	@Test
	void elementNamesMatchTheConverterVariants() {
		assertEquals(VertexLayouts.VARIANTS.get("terrain_ubo"), names(EmrionVertexFormats.TERRAIN));
		assertEquals(VertexLayouts.VARIANTS.get("entity"), names(EmrionVertexFormats.ENTITY));
	}

	static List<String> names(VertexFormat f) {
		return f.getElements().stream().map(VertexFormatElement::name).toList();
	}
}
