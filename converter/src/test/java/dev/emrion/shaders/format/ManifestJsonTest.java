package dev.emrion.shaders.format;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.report.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManifestJsonTest {

	static Manifest sample() {
		var expr = JsonParser.parseString("{\"op\":\"lit\",\"value\":2.0,\"type\":\"float\"}").getAsJsonObject();
		var member = new Manifest.UniformMember("gbufferModelView", "mat4", 0, 0, 0, "builtin");
		var uniforms = new Manifest.UniformLayout("EmrionUniforms", 64, List.of(member),
				List.of(new Manifest.CustomUniform("framemod2", "float", true, expr)),
				new Manifest.Block("EmrionPhase", 144, List.of()), new Manifest.Block(null, 48, List.of()));
		var program = new Manifest.Program("gbuffers", "gbuffers", List.of("gbuffers_terrain_solid"),
				new Manifest.Stages(new Manifest.VertexStage(Map.of("terrain_mdi", "spv/world0/gbuffers_terrain.vsh.terrain_mdi.spv")),
						null, null, null, new Manifest.SpvRef("spv/world0/gbuffers_terrain.fsh.spv"), null),
				List.of(new Manifest.Output(0, "colortex0")),
				new Manifest.Blend(new Manifest.BlendMode("vanilla", null, null, null, null), Map.of("colortex6", new Manifest.BlendMode("off", null, null, null, null))),
				new Manifest.AlphaTest("greater", 0.1f), List.of(new Manifest.Set0Ref("Sampler0", "combined_image_sampler")),
				List.of(0, 1, 7), List.of(), true, null, null, null, List.of("colortex0"), 1.0f, null);
		var dim = new Manifest.Dimension(Map.of("gbuffers_terrain", program),
				List.of(new Manifest.Resource(2, "sampled_image", null, "colortex0", null, null, new Manifest.SamplerDef("linear", "linear", "clamp", null))),
				List.of(new Manifest.Target("colortex0", "color", "R11F_G11F_B10F", "R11F_G11F_B10F", 2, new float[]{1, 1}, null, 1, true, true, null, true, 0)),
				List.of(new Manifest.FlipState(0, List.of())),
				List.of(new Manifest.Command("geometry", null, 0, "opaque", null, null, null, null, null, null)),
				List.of(),
				new Manifest.Shadow(true, 2048, 256, 1, 0.125f, -100.05f, 156, null, 2, -40, new boolean[]{true, true}, "reversed", 64));
		var props = new LinkedHashMap<String, Object>();
		props.put("sun", true);
		props.put("clouds", "off");
		return new Manifest(Manifest.FORMAT_VERSION, "0.1.0", "3fa2", new Manifest.PackInfo("pack", "abc"),
				new Manifest.Environment("26.3", Map.of("MC_VERSION", "260300", "IS_IRIS", "")),
				new Manifest.Options("s.txt", Map.of("SHADOW_QUALITY", "3"),
						List.of(new Manifest.OptionDecl("SHADOW_QUALITY", "define_value", "int", "2", List.of("1", "2", "3"), "shaders/lib/common.glsl", 120, null)),
						List.of(), Map.of(), Map.of("", List.of("[LIGHTING]")), List.of("SHADOW_QUALITY"), Map.of("", 2)),
				props, uniforms,
				new Manifest.IdMaps(List.of(new Manifest.IdEntry(10007, List.of("minecraft:oak_leaves"))), List.of(), List.of(), Map.of()),
				List.of(), List.of(new Manifest.DimensionMapping("minecraft:overworld", "world0")), Map.of("world0", dim));
	}

	@Test
	void roundTripsByteIdentical() throws Exception {
		String json = ManifestJson.toJson(sample());
		Manifest back = ManifestJson.GSON.fromJson(json, Manifest.class);
		assertEquals(json, ManifestJson.toJson(back));
		assertEquals(0.1f, back.dimensions().get("world0").programs().get("gbuffers_terrain").alphaTest().ref());
		assertEquals(Map.of("sun", true, "clouds", "off"), back.properties());
	}

	@Test
	void sortsMapKeysForDeterminism() {
		var m = new LinkedHashMap<String, String>();
		m.put("b", "1");
		m.put("a", "2");
		assertEquals("{\n  \"a\": \"2\",\n  \"b\": \"1\"\n}\n", ManifestJson.toJson(m));
	}

	@Test
	void usesFormatKeyNamesAndOmitsNulls() {
		JsonObject o = JsonParser.parseString(ManifestJson.toJson(sample())).getAsJsonObject();
		assertEquals(Manifest.FORMAT_VERSION, o.get("formatVersion").getAsInt());
		var program = o.getAsJsonObject("dimensions").getAsJsonObject("world0").getAsJsonObject("programs").getAsJsonObject("gbuffers_terrain");
		assertFalse(program.getAsJsonObject("stages").has("geometry"));
		assertEquals("spv/world0/gbuffers_terrain.fsh.spv", program.getAsJsonObject("stages").getAsJsonObject("fragment").get("spv").getAsString());
		assertEquals("lit", o.getAsJsonObject("uniforms").getAsJsonArray("custom").get(0).getAsJsonObject().getAsJsonObject("expr").get("op").getAsString());
	}

	@Test
	void reportWritesLowerCaseSeverity(@TempDir Path dir) throws Exception {
		var report = new Report(false, 1, 1, 1, List.of(new Diagnostic(Diagnostic.Severity.ERROR, "shaders/x.fsh", 3, "composite", "fragment", "boom")));
		ManifestJson.write(dir.resolve("report.json"), report);
		JsonObject o = JsonParser.parseString(java.nio.file.Files.readString(dir.resolve("report.json"))).getAsJsonObject();
		assertEquals("error", o.getAsJsonArray("diagnostics").get(0).getAsJsonObject().get("severity").getAsString());
		assertEquals(report, ManifestJson.read(dir.resolve("report.json"), Report.class));
	}
}
