package dev.emrion.shaders.converter.plan;

import com.google.gson.JsonObject;
import dev.emrion.shaders.converter.report.Diagnostics;
import dev.emrion.shaders.format.Manifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.emrion.shaders.converter.plan.P.p;
import static org.junit.jupiter.api.Assertions.*;

class Std140LayoutBuilderTest {

	private final Std140LayoutBuilder builder = new Std140LayoutBuilder(TestCatalog::lookup);

	private Map<String, Manifest.UniformMember> build(P... programs) {
		var custom = List.of(
				new Manifest.CustomUniform("framemod8", "float", true, new JsonObject()),
				new Manifest.CustomUniform("worldDayTime", "float", false, new JsonObject()));
		var analyses = java.util.Arrays.stream(programs).map(P::analysis).toList();
		var layout = builder.build(analyses, custom, new Diagnostics());
		assertEquals(0, layout.size() % 16);
		assertEquals(custom, layout.custom());
		return layout.members().stream().collect(Collectors.toMap(Manifest.UniformMember::name, m -> m));
	}

	@Test
	void injectedMembersAlwaysPresent() {
		var m = build();
		assertEquals("ivec3", m.get("emr_CameraBlockPos").type());
		assertEquals("vec3", m.get("emr_CameraOffset").type());
		assertEquals("vec4", m.get("emr_FogColor").type());
		assertEquals("mat4", m.get("emr_LightmapTextureMatrix").type());
		for (String f : List.of("emr_FogStart", "emr_FogEnd", "emr_FogDensity")) assertEquals("float", m.get(f).type());
	}

	@Test
	void perDrawAndPhaseUniformsAreNotInTheBlock() {
		var m = build(p("gbuffers_terrain").uniform("entityId", "int").uniform("renderStage", "int")
				.uniform("entityColor", "vec4").uniform("near", "float"));
		assertFalse(m.containsKey("entityId"));
		assertFalse(m.containsKey("renderStage"));
		assertFalse(m.containsKey("entityColor"));
		assertTrue(m.containsKey("near"));
	}

	@Test
	void customUniformsUnknownsAndVariables() {
		var d = new Diagnostics();
		var layout = builder.build(List.of(p("composite").uniform("framemod8", "float").uniform("bogus", "float")
				.uniform("worldDayTime", "float").analysis()), List.of(
				new Manifest.CustomUniform("framemod8", "float", true, new JsonObject()),
				new Manifest.CustomUniform("worldDayTime", "float", false, new JsonObject())), d);
		var names = layout.members().stream().collect(Collectors.toMap(Manifest.UniformMember::name, Manifest.UniformMember::source));
		assertEquals("custom", names.get("framemod8"));
		assertFalse(names.containsKey("bogus"));
		assertFalse(names.containsKey("worldDayTime"), "variables are CPU-only");
		assertEquals(2, d.all().size());
	}

	@Test
	void std140Offsets() {
		var layout = builder.build(List.of(p("composite")
				.uniform("gbufferModelView", "mat4")
				.uniform("iris_DefaultNormalMat", "mat3")
				.uniform("cameraPosition", "vec3")
				.uniform("sunPosition", "vec3")
				.uniform("eyeBrightness", "ivec2")
				.uniform("near", "float")
				.uniform("isRightHanded", "bool")
				.uniform("frameCounter", "int")
				.uniform("lightningBoltPosition", "vec4")
				.uniform("viewWidth", "float", 3)
				.analysis()), List.of(), new Diagnostics());
		var m = layout.members().stream().collect(Collectors.toMap(Manifest.UniformMember::name, x -> x));
		// 16-aligned non-vec3 first, sorted by name: emr_FogColor, emr_LightmapTextureMatrix, gbufferModelView,
		// iris_DefaultNormalMat, lightningBoltPosition, viewWidth[3]
		assertEquals(0, m.get("emr_FogColor").offset());
		assertEquals(16, m.get("emr_LightmapTextureMatrix").offset());
		assertEquals(80, m.get("gbufferModelView").offset());
		assertEquals(144, m.get("iris_DefaultNormalMat").offset()); // mat3 = 3 × vec4 columns = 48 B
		assertEquals(192, m.get("lightningBoltPosition").offset());
		var arr = m.get("viewWidth");
		assertEquals(208, arr.offset());
		assertEquals(3, arr.arraySize());
		assertEquals(16, arr.arrayStride()); // float[] stride rounds to 16
		// vec3s (sorted), each followed by a scalar that fills its 4-byte tail
		assertEquals(256, m.get("cameraPosition").offset());
		assertEquals(268, m.get("emr_FogDensity").offset());
		assertEquals(272, m.get("emr_CameraBlockPos").offset());
		assertEquals(284, m.get("emr_FogEnd").offset());
		assertEquals(288, m.get("emr_CameraOffset").offset());
		assertEquals(300, m.get("emr_FogStart").offset());
		assertEquals(304, m.get("sunPosition").offset());
		assertEquals(316, m.get("frameCounter").offset());
		// then 8-aligned, then remaining scalars (bool stored as 4-byte int)
		assertEquals(320, m.get("eyeBrightness").offset());
		assertEquals(328, m.get("isRightHanded").offset());
		assertEquals("bool", m.get("isRightHanded").type());
		assertEquals(332, m.get("near").offset());
		assertEquals(336, layout.size());
	}

	@Test
	void vec3AlignmentWithoutScalars() {
		assertEquals(12, Std140LayoutBuilder.size("vec3"));
		assertEquals(64, Std140LayoutBuilder.size("mat4"));
		assertEquals(32, Std140LayoutBuilder.size("mat2"));
		assertEquals(8, Std140LayoutBuilder.size("ivec2"));
	}

	@Test
	void fixedBlocks() {
		assertEquals(144, Std140LayoutBuilder.PHASE.size());
		assertEquals(128, Std140LayoutBuilder.PHASE.members().get(2).offset());
		assertEquals(28, Std140LayoutBuilder.PUSH.members().get(4).offset());
	}
}
