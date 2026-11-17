package dev.emrion.shaders.converter.nativefmt;

import dev.emrion.shaders.converter.report.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NativeManifestTest {
	static final Set<String> FILES = Set.of("shaders/a.vert.glsl", "shaders/a.frag.glsl", "shaders/f.frag.glsl", "shaders/c.comp.glsl",
			"textures/n.png");

	/** A valid manifest with {@code extra} spliced into the top-level object. */
	static String manifest(String extra) {
		return """
				// comment
				{
				  "format": 1, "id": "t", "name": "T", "version": "1",
				  "images": { "albedo": { "format": "RGBA8" }, "hist": { "format": "RGBA16F", "history": true, "scale": 0.5 } },
				  "passes": [
				    { "name": "gb", "kind": "geometry", "classes": ["terrain_solid"], "vertex": "shaders/a.vert.glsl",
				      "fragment": "shaders/a.frag.glsl", "writes": ["albedo"] },
				    { "name": "out", "kind": "final", "fragment": "shaders/f.frag.glsl" }
				  ]%s
				}
				""".formatted(extra);
	}

	static List<Diagnostic> errors(String json) {
		List<Diagnostic> d = new ArrayList<>();
		NativeManifest m = NativeManifest.parse(json, FILES::contains, d);
		List<Diagnostic> errors = d.stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).toList();
		assertEquals(errors.isEmpty(), m != null, "a manifest is returned exactly when there are no errors: " + d);
		return errors;
	}

	static void assertError(String json, int line, String fragment) {
		List<Diagnostic> e = errors(json);
		assertTrue(e.stream().anyMatch(d -> d.line() == line && d.message().contains(fragment) && d.file().equals("emberlight.json")),
				"expected emberlight.json:" + line + " containing '" + fragment + "', got " + e);
	}

	@Test
	void validManifestParses() {
		List<Diagnostic> d = new ArrayList<>();
		NativeManifest m = NativeManifest.parse(manifest(""), FILES::contains, d);
		assertNotNull(m, d.toString());
		assertEquals(2, m.passes().size());
		assertEquals("opaque", m.passes().get(0).phase());
		assertEquals("final", m.passes().get(1).phase());
		var hist = m.images().get(1);
		assertNull(hist.clear(), "history images are never cleared");
		assertArrayEquals(new float[]{0.5f, 0.5f}, hist.scale());
	}

	@Test
	void syntaxErrorsNameTheLine() {
		assertError("{\n  \"format\": 1,\n  \"id\": \"x\" \"name\": 2\n}", 3, "invalid JSON");
		assertError("{\n \"format\": 1,\n \"format\": 1\n}", 3, "duplicate key");
	}

	@Test
	void formatVersionIsChecked() {
		assertError("{ \"format\": 2 }", 1, "needs a newer Emberlight");
		assertError("{ \"id\": \"x\" }", 1, "missing \"format\"");
	}

	@Test
	void unknownKeysAreErrorsButXKeysAreNot() {
		assertError(manifest(",\n  \"imagse\": {}"), 10, "unknown key \"imagse\"");
		assertTrue(errors(manifest(",\n  \"x-tool\": { \"anything\": 1 }")).isEmpty());
	}

	@Test
	void passReferencesAreChecked() {
		String bad = manifest("").replace("\"writes\": [\"albedo\"]", "\"writes\": [\"albdo\"]");
		assertError(bad, 7, "writes unknown image \"albdo\" (images: albedo, hist)");
		assertError(manifest("").replace("shaders/a.frag.glsl", "shaders/missing.glsl"), 7, "file \"shaders/missing.glsl\" not found");
		assertError(manifest("").replace("terrain_solid", "terrain_soild"), 6, "unknown geometry class \"terrain_soild\"");
		assertError(manifest("").replace("{ \"name\": \"out\", \"kind\": \"final\", \"fragment\": \"shaders/f.frag.glsl\" }",
				"{ \"name\": \"out\", \"kind\": \"fullscreen\", \"phase\": \"composite\", \"fragment\": \"shaders/f.frag.glsl\", \"writes\": [\"albedo\"] }"),
				5, "needs one pass of kind \"final\"");
	}

	@Test
	void classesBelongToOnePass() {
		String json = manifest("").replace("{ \"name\": \"out\"", """
				{ "name": "gb2", "kind": "geometry", "classes": ["terrain_solid"], "vertex": "shaders/a.vert.glsl",
				      "fragment": "shaders/a.frag.glsl", "writes": ["albedo"] },
				    { "name": "out\"""");
		assertError(json, 8, "class terrain_solid is already drawn by pass \"gb\"");
	}

	@Test
	void raytracingNeedsTheFeature() {
		String compute = manifest("").replace("{ \"name\": \"out\"", """
				{ "name": "rt", "kind": "compute", "phase": "deferred", "compute": "shaders/c.comp.glsl", "raytracing": true },
				    { "name": "out\"""");
		assertError(compute, 8, "add \"requires\": [\"ray_query\"]");
		assertTrue(errors(compute.replace("\"version\": \"1\",", "\"version\": \"1\", \"requires\": [\"ray_query\"],")).isEmpty());
	}

	/** §14.4: the RR guide images are writable by compute passes only with settings.rayReconstruction, which needs all three and ray queries. */
	@Test
	void rayReconstructionGuidesAreChecked() {
		String guides = manifest("").replace("{ \"name\": \"out\"", """
				{ "name": "rr", "kind": "compute", "phase": "composite", "compute": "shaders/c.comp.glsl", "writes": ["el_rrDiffuse", "el_rrSpecular", "el_rrNormal"] },
				    { "name": "out\"""");
		assertError(guides, 5, "add \"rayReconstruction\": true to settings");
		String on = guides.replace("\"version\": \"1\",", "\"version\": \"1\", \"requires\": [\"ray_query\"], \"settings\": { \"rayReconstruction\": true },");
		assertTrue(errors(on).isEmpty(), errors(on).toString());
		assertError(on.replace(", \"el_rrNormal\"", ""), 3, "no compute pass writes el_rrNormal");
		assertError(on.replace("\"requires\": [\"ray_query\"], ", ""), 3, "settings.rayReconstruction needs");
		assertError(on.replace("\"kind\": \"compute\", \"phase\": \"composite\", \"compute\": \"shaders/c.comp.glsl\"",
				"\"kind\": \"fullscreen\", \"phase\": \"composite\", \"fragment\": \"shaders/f.frag.glsl\""), 8, "writes unknown image \"el_rrDiffuse\"");
		List<String> err = new ArrayList<>();
		Conditions.check("!EL_RR_ACTIVE && EL_UPSCALING", Map.of(), err::add);
		assertEquals(List.of(), err);
	}

	@Test
	void optionsAreValidated() {
		assertError(manifest(",\n" + """
				  "options": [
				    { "name": "RAYS", "type": "int", "default": 0, "min": 1, "max": 8 }
				  ]"""), 11, "default 0 is below min 1");
		assertError(manifest(",\n" + """
				  "options": [
				    { "name": "MODE", "type": "enum", "default": "c", "values": ["a", "b"] }
				  ]"""), 11, "default \"c\" is not one of a, b");
		assertError(manifest(",\n" + """
				  "options": [
				    { "name": "lower", "type": "bool", "default": true }
				  ]"""), 11, "must match [A-Z]");
		List<Diagnostic> d = new ArrayList<>();
		NativeManifest m = NativeManifest.parse(manifest(",\n" + """
				  "options": [
				    { "name": "STRENGTH", "type": "float", "default": 0.3, "min": 0.1, "max": 0.5, "step": 0.1 },
				    { "name": "TINT", "type": "color", "default": [1.0, 0.95, 0.5] }
				  ]"""), FILES::contains, d);
		assertNotNull(m, d.toString());
		assertEquals(List.of("0.1", "0.2", "0.3", "0.4", "0.5"), m.options().get(0).values(), "no binary float noise in the value list");
		assertEquals("1.0,0.95,0.5", m.options().get(1).defaultValue());
	}

	@Test
	void conditionsAreCheckedAndLowered() {
		var opts = Map.of(
				"RT", new NativeManifest.Option("RT", 1, "bool", "true", List.of(), false, null, null),
				"RAYS", new NativeManifest.Option("RAYS", 1, "int", "1", List.of("1", "2"), false, null, null),
				"MODE", new NativeManifest.Option("MODE", 1, "enum", "fast", List.of("fast", "nice"), false, null, null),
				"GAIN", new NativeManifest.Option("GAIN", 1, "float", "1.0", List.of("1.0"), false, null, null));
		List<String> err = new ArrayList<>();
		Conditions.check("RT && (RAYS > 1 || MODE == MODE_NICE)", opts, err::add);
		assertEquals(List.of(), err);
		var vals = Map.of("RT", 1L, "RAYS", 1L, "MODE", 1L, "MODE_NICE", 1L);
		assertEquals(1L, Conditions.eval("RT && (RAYS > 1 || MODE == MODE_NICE)", vals::get));
		assertEquals(0L, Conditions.eval("RT && (RAYS > 1 || MODE == 0)", vals::get));
		Conditions.check("RT && RAYZ", opts, err::add);
		Conditions.check("GAIN > 1", opts, err::add);
		Conditions.check("(RT", opts, err::add);
		assertEquals(List.of("unknown option \"RAYZ\"", "float option GAIN cannot be used in a condition (use bool, int or enum)",
				"missing \")\""), err);
	}

	@Test
	void pagesAndProfilesReferenceOptions() {
		assertError(manifest(",\n" + """
				  "options": [ { "name": "A", "type": "bool", "default": true } ],
				  "pages": [ { "name": "main", "items": ["A", "B", "[nope]"] } ]"""), 11, "unknown option \"B\"");
		assertError(manifest(",\n" + """
				  "options": [ { "name": "A", "type": "bool", "default": true } ],
				  "profiles": { "low": { "A": 3 } }"""), 11, "invalid value for A");
	}

	@Test
	void reservedNamesAreRejected() {
		assertError(manifest("").replace("\"albedo\": {", "\"colortex3\": {"), 4, "name is reserved");
		assertError(manifest(",\n  \"buffers\": { \"albedo\": { \"bytes\": 16 } }"), 10, "used by two resources");
	}

	@Test
	void scalarsOnlyWhereOneNumberIsAllowed() {
		assertError(manifest(",\n" + """
				  "options": [ { "name": "TINT", "type": "color", "default": 0.5 } ]"""), 10, "needs 3 numbers");
		String compute = manifest("").replace("{ \"name\": \"out\"", """
				{ "name": "c", "kind": "compute", "phase": "deferred", "compute": "shaders/c.comp.glsl", "workgroups": { "size": 8 } },
				    { "name": "out\"""");
		assertError(compute, 8, "workgroups.size needs 3 numbers");
		assertError(compute.replace("{ \"size\": 8 }", "{ \"count\": 1 }"), 8, "workgroups.count needs 3 numbers");
		assertError(compute.replace("{ \"size\": 8 }", "{ \"scale\": 0.5 }"), 8, "workgroups.scale needs 2 numbers");
		assertTrue(errors(manifest("").replace("\"history\": true, \"scale\": 0.5", "\"history\": true, \"scale\": 0.25")).isEmpty(), "image scale takes a number");
	}
}
