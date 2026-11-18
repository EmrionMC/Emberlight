package dev.emrion.shaders.converter.cli;

import dev.emrion.shaders.converter.Converter;
import dev.emrion.shaders.converter.report.Diagnostic;
import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A tiny pack that ships its own RT table (shaders/emberlight/rt.json), the worked example of docs/RT-PATCH-TABLES.md. */
class PackRtTableTest {
	static final String TABLE = """
			{
			  "schema": 1,
			  "id": "tiny-rt",
			  "pack": "Tiny",
			  "version": "1.0",
			  "effects": {
			    "shadows": {
			      "requires": ["gbuffers_terrain"],
			      "helpers": "shadows.glsl",
			      "uniforms": {"gbufferModelViewInverse": "mat4", "shadowLightPosition": "vec3"},
			      "patches": [{"function": "float getShadow(vec3)", "body": "shadow_body.glsl"}]
			    }
			  }
			}
			""";

	static Map<String, String> pack(String table) {
		Map<String, String> f = new TreeMap<>();
		f.put("shaders/gbuffers_terrain.vsh", """
				#version 150
				in vec3 vaPosition;
				uniform mat4 modelViewMatrix, projectionMatrix;
				uniform vec3 chunkOffset;
				out vec3 playerPos;
				void main() {
					vec4 view = modelViewMatrix * vec4(vaPosition + chunkOffset, 1.0);
					playerPos = vaPosition + chunkOffset;
					gl_Position = projectionMatrix * view;
				}
				""");
		f.put("shaders/gbuffers_terrain.fsh", """
				#version 150
				in vec3 playerPos;
				float getShadow(vec3 pos) {
					return 1.0; // the pack's own shadow (a shadow map lookup in a real pack)
				}
				/* RENDERTARGETS: 0 */
				out vec4 color;
				void main() {
					color = vec4(vec3(getShadow(playerPos)), 1.0);
				}
				""");
		f.put("shaders/emberlight/rt.json", table);
		f.put("shaders/emberlight/shadows.glsl", """
				// 1 = the sun is visible from pos (player space, which is TLAS space)
				float tinyRtShadow(vec3 pos) {
					vec3 dir = normalize(mat3(gbufferModelViewInverse) * shadowLightPosition);
					rayQueryEXT rq;
					rayQueryInitializeEXT(rq, emrRtTlas, gl_RayFlagsTerminateOnFirstHitEXT | gl_RayFlagsOpaqueEXT, 0x01u, pos, 0.05, dir, 256.0);
					rayQueryProceedEXT(rq);
					return rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
				}
				""");
		f.put("shaders/emberlight/shadow_body.glsl", "return tinyRtShadow(pos);\n");
		f.put("shaders/emberlight/lift_body.glsl", "if (tinyRtShadow(pos) < 0.5) return 0.0;\n@own\n"); // liftsTakeThePacksOwnStatements
		return f;
	}

	static Path folder(Path dir, Map<String, String> files) throws Exception {
		for (var e : files.entrySet()) {
			Path p = dir.resolve(e.getKey());
			Files.createDirectories(p.getParent());
			Files.writeString(p, e.getValue());
		}
		return dir;
	}

	static Path zip(Path file, Map<String, String> files) throws Exception {
		try (var z = new ZipOutputStream(Files.newOutputStream(file))) {
			for (var e : files.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue().getBytes());
			}
		}
		return file;
	}

	static Converter.Result convert(Path pack, Path out, Set<String> rt) {
		try (var compiler = new ShadercSpirvCompiler()) {
			return ConverterCli.defaultConverter(compiler).convert(new Converter.Request(pack, null, out, null, Converter.ProgressListener.NONE, false, rt));
		}
	}

	static void assertShadowsApplied(Converter.Result r) throws Exception {
		assertEquals(List.of(), r.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList());
		assertEquals(0, r.stagesFailed());
		assertEquals(Map.of(), r.manifest().rt().unavailable());
		assertTrue(r.manifest().rt().effects().contains("shadows"), r.manifest().rt().toString());
		String fsh = Files.readString(r.outputDir().resolve("src/gbuffers_terrain.fsh.glsl"));
		assertTrue(fsh.contains("tinyRtShadow"), fsh);
		assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("tiny-rt (Tiny 1.0, from the pack)")));
	}

	@Test
	void folderPackTableAppliesAndCompiles(@TempDir Path tmp) throws Exception {
		// shadows:rtx is what the mod sends in the RTX style: a table without that variant ignores it
		assertShadowsApplied(convert(folder(tmp.resolve("Tiny"), pack(TABLE)), tmp.resolve("out"), Set.of("shadows", "shadows:rtx")));
	}

	@Test
	void zipPackTableApplies(@TempDir Path tmp) throws Exception {
		assertShadowsApplied(convert(zip(tmp.resolve("Tiny.zip"), pack(TABLE)), tmp.resolve("out"), Set.of("shadows")));
	}

	@Test
	void tableEffectsForTheRtPage(@TempDir Path tmp) throws Exception {
		Map<String, String> noTable = pack(TABLE);
		noTable.remove("shaders/emberlight/rt.json");
		try (var own = dev.emrion.shaders.converter.pack.PackSource.open(folder(tmp.resolve("own"), pack(TABLE)));
				var none = dev.emrion.shaders.converter.pack.PackSource.open(folder(tmp.resolve("none"), noTable));
				var broken = dev.emrion.shaders.converter.pack.PackSource.open(folder(tmp.resolve("broken"), pack("{")))) {
			assertEquals(Set.of("shadows"), dev.emrion.shaders.converter.glsl.RtPatcher.tableEffects(own));
			assertEquals(Set.of(), dev.emrion.shaders.converter.glsl.RtPatcher.tableEffects(none));
			assertEquals(Set.of(), dev.emrion.shaders.converter.glsl.RtPatcher.tableEffects(broken));
		}
	}

	@Test
	void rtOffIgnoresTheTable(@TempDir Path tmp) throws Exception {
		var with = convert(folder(tmp.resolve("a/Tiny"), pack(TABLE)), tmp.resolve("o1"), Set.of());
		assertNull(with.manifest().rt());
		var plain = pack(TABLE);
		plain.keySet().removeIf(k -> k.startsWith("shaders/emberlight/"));
		var without = convert(folder(tmp.resolve("b/Tiny"), plain), tmp.resolve("o2"), Set.of());
		assertEquals(Files.readString(without.outputDir().resolve("src/gbuffers_terrain.fsh.glsl")),
				Files.readString(with.outputDir().resolve("src/gbuffers_terrain.fsh.glsl")));
	}

	static String unavailable(Path tmp, String table) throws Exception {
		var r = convert(folder(tmp.resolve("Tiny"), pack(table)), tmp.resolve("out"), Set.of("shadows"));
		String why = r.manifest().rt().unavailable().get("shadows");
		assertNotNull(why, r.manifest().rt().toString());
		return why;
	}

	@Test
	void pathEscapesAreRejected(@TempDir Path tmp) throws Exception {
		for (String bad : List.of("../gbuffers_terrain.fsh", "../../../../etc/passwd", "/etc/passwd", "a/./b.glsl", "C:\\\\x.glsl", "..", "x\\\\..\\\\y")) {
			String why = unavailable(tmp.resolve(Integer.toString(bad.hashCode())), TABLE.replace("\"shadow_body.glsl\"", "\"" + bad + "\""));
			assertTrue(why.contains("is not allowed"), bad + ": " + why);
		}
		String why = unavailable(tmp.resolve("helpers"), TABLE.replace("\"shadows.glsl\"", "\"ok.glsl,../x.glsl\""));
		assertTrue(why.contains("'../x.glsl' is not allowed"), why);
	}

	@Test
	void liftsTakeThePacksOwnStatements(@TempDir Path tmp) throws Exception {
		String lifting = TABLE.replace("\"patches\":", "\"lifts\": {\"own\": {\"function\": \"float getShadow(vec3)\", \"at\": \"return 1.0;\"}},\n\"patches\":")
				.replace("shadow_body.glsl", "lift_body.glsl");
		var r = convert(folder(tmp.resolve("a/Tiny"), pack(lifting)), tmp.resolve("o1"), Set.of("shadows"));
		assertShadowsApplied(r);
		assertTrue(Files.readString(r.outputDir().resolve("src/gbuffers_terrain.fsh.glsl")).contains("return 0.0; return 1.0;"));
		// a missing anchor makes the effect unavailable, or uses "otherwise"
		assertTrue(unavailable(tmp.resolve("b"), lifting.replace("return 1.0;", "return 2.0;")).contains("@own: getShadow has no 'return 2.0;'"));
		r = convert(folder(tmp.resolve("c/Tiny"), pack(lifting.replace("\"return 1.0;\"}", "\"return 2.0;\", \"otherwise\": \"return 0.5;\"}"))),
				tmp.resolve("o3"), Set.of("shadows"));
		assertShadowsApplied(r);
		assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("@own left out")));
		// statements using a name not declared where they go (main's color, declared after getShadow) are never inserted
		String why = unavailable(tmp.resolve("d"), lifting.replace("float getShadow(vec3)\", \"at\": \"return 1.0;", "void main()\", \"at\": \"color ="));
		assertTrue(why.contains("use 'color', which is not declared"), why);
	}

	@Test
	void alternativeSignaturesAndTextLifts(@TempDir Path tmp) throws Exception {
		// a pack version that changed the signature: either alternative matches; a text lift follows whichever the pack has
		String alt = TABLE.replace("\"float getShadow(vec3)\"", "\"float getShadow(vec3,float) | float getShadow(vec3)\"")
				.replace("\"patches\":", "\"lifts\": {\"v2\": {\"function\": \"float getShadow(vec3)\", \"text\": \"* 0.75\", \"otherwise\": \"* 0.5\"},"
						+ " \"v3\": {\"function\": \"float getShadow(vec3,float)\", \"text\": \"* 0.25\", \"otherwise\": \"\"}},\n\"patches\":");
		Map<String, String> files = pack(alt);
		files.put("shaders/emberlight/shadow_body.glsl", "return tinyRtShadow(pos) @v2 @v3;\n");
		var r = convert(folder(tmp.resolve("Tiny"), files), tmp.resolve("out"), Set.of("shadows"));
		assertShadowsApplied(r);
		assertTrue(Files.readString(r.outputDir().resolve("src/gbuffers_terrain.fsh.glsl")).contains("return tinyRtShadow(pos) * 0.75 ;"));
	}

	@Test
	void authorErrorsAreReported(@TempDir Path tmp) throws Exception {
		assertTrue(unavailable(tmp.resolve("a"), TABLE.replace("\"schema\": 1", "\"schema\": 99")).contains("schema 99 is not supported"));
		assertTrue(unavailable(tmp.resolve("b"), TABLE.replace("\"schema\": 1,", "")).contains("missing \"schema\""));
		assertTrue(unavailable(tmp.resolve("c"), TABLE.replace("}\n}", "")).contains("invalid JSON"));
		assertTrue(unavailable(tmp.resolve("d"), TABLE.replace("shadow_body.glsl", "nope.glsl")).contains("shaders/emberlight/nope.glsl not found"));
		String why = unavailable(tmp.resolve("e"), TABLE.replace("float getShadow(vec3)", "float getShadow(vec2)"));
		assertTrue(why.contains("'float getShadow(vec2)'") && why.contains("gbuffers_terrain"), why);
		why = unavailable(tmp.resolve("f"), TABLE.replace("\"version\": \"1.0\",", "\"version\": \"1.0\", \"detect\": [\"lib/v2.glsl\"],"));
		assertTrue(why.contains("targets Tiny 1.0 but shaders/lib/v2.glsl is missing"), why);
	}
}
