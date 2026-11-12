package dev.emrion.shaders.converter.glsl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectiveParserTest {
	private final List<String> warnings = new ArrayList<>();

	private List<Integer> db(String text) {
		DirectiveParser.DrawBuffers d = DirectiveParser.drawBuffers(text, warnings::add);
		return d == null ? null : d.buffers();
	}

	@Test
	void lastDrawBuffersWins() {
		assertEquals(List.of(0, 6, 4), db("/* DRAWBUFFERS:06 */\nx\n/*DRAWBUFFERS:064*/"));
	}

	@Test
	void laterRenderTargetsBeatsEarlierDrawBuffers() {
		DirectiveParser.DrawBuffers d = DirectiveParser.drawBuffers("/* DRAWBUFFERS:0 */ /* RENDERTARGETS: 0,1,12 */", warnings::add);
		assertEquals(List.of(0, 1, 12), d.buffers());
		assertTrue(d.renderTargets());
		assertEquals(List.of(3), db("/* RENDERTARGETS: 5 */ /* DRAWBUFFERS:3 */"));
	}

	@Test
	void drawBuffersAreDigitsOnly() {
		assertEquals(List.of(1, 2), db("/* DRAWBUFFERS:1a2 */"));
		assertEquals(1, warnings.size());
	}

	@Test
	void lastOccurrenceMustFollowCommentStart() {
		assertNull(db("/* DRAWBUFFERS:0 */ text DRAWBUFFERS:1 */"));
		assertNull(db("no directive"));
	}

	@Test
	void constsInsideBlockCommentsCountAndLastWins() {
		Map<String, String> c = DirectiveParser.consts("/*\nconst int colortex0Format = R11F_G11F_B10F;\n*/\nconst bool colortex1Clear = false;\n  const int shadowMapResolution = 1024; // [x]\nconst int shadowMapResolution = 2048;\n");
		assertEquals("R11F_G11F_B10F", c.get("colortex0Format"));
		assertEquals("false", c.get("colortex1Clear"));
		assertEquals("2048", c.get("shadowMapResolution"));
	}

	@Test
	void wrongTypeForKnownNameIsIgnored() {
		Map<String, String> c = DirectiveParser.consts("const int shadowMapResolution = 1024;\nconst float shadowMapResolution = 4096.0;\nconst bool colortex2Clear = 0;\nconst float shadowDistance = 256.0;\n");
		assertEquals("1024", c.get("shadowMapResolution"));
		assertNull(c.get("colortex2Clear"));
		assertEquals("256.0", c.get("shadowDistance"));
	}

	@Test
	void constGrammar() {
		assertNull(DirectiveParser.constLine("constint x = 1;"));
		assertNull(DirectiveParser.constLine("const mat4 x = 1;"));
		assertArrayEquals(new String[]{"vec4", "colortex0ClearColor", "vec4(1.0, 0.0, 0.0, 1.0)"},
				DirectiveParser.constLine("  const vec4 colortex0ClearColor = vec4(1.0, 0.0, 0.0, 1.0); // c"));
	}

	@Test
	void computeDirectives() {
		String src = "layout (local_size_x = 8, local_size_y = 8, local_size_z = 8) in;\nconst ivec3 workGroups = ivec3(64, 32, 64);\nconst vec2 workGroupsRender = vec2(0.5, 0.5);\n";
		Directives d = DirectiveParser.parse(StageType.COMPUTE, src, src, warnings::add);
		assertArrayEquals(new int[]{8, 8, 8}, d.localSize());
		assertArrayEquals(new int[]{64, 32, 64}, d.workGroups());
		assertArrayEquals(new float[]{0.5f, 0.5f}, d.workGroupsRender());
		assertNull(d.drawBuffers());
		assertArrayEquals(new int[]{16, 1, 1}, DirectiveParser.localSize("layout(local_size_x=16) in;"));
	}

	@Test
	void drawBuffersOnlyForFragment() {
		Directives v = DirectiveParser.parse(StageType.VERTEX, "", "/* DRAWBUFFERS:0 */", warnings::add);
		assertNull(v.drawBuffers());
		Directives f = DirectiveParser.parse(StageType.FRAGMENT, "", "/* DRAWBUFFERS:01 */", warnings::add);
		assertEquals(List.of(0, 1), f.drawBuffers());
		assertFalse(f.renderTargetsSyntax());
	}
}
