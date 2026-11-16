package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import dev.emrion.shaders.format.Manifest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityCopiesTest {
	private static byte[] frag(String body) {
		String src = "#version 450\nlayout(set = 1, binding = 7) uniform sampler2D colortex0;\nlayout(set = 1, binding = 8) uniform sampler2D colortex1;\n"
				+ "layout(location = 0) out vec4 o;\nvoid main() {\n" + body + "\n}\n";
		try (var c = new ShadercSpirvCompiler()) {
			var r = c.compile("t.fsh", src, dev.emrion.shaders.converter.glsl.StageType.FRAGMENT);
			assertNotNull(r.spv(), r.diagnostics().toString());
			return r.spv();
		}
	}

	@Test
	void recognisesOnlyPlainTexelCopies() {
		assertEquals(new IdentityCopies.Copy(7, 0, false), IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);")));
		assertEquals(new IdentityCopies.Copy(8, 0, true), IdentityCopies.match(frag("o = texelFetch(colortex1, ivec2(gl_FragCoord.xy), 0);")));
		assertNull(IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb * 0.5, 1.0);")));
		assertNull(IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 1).rgb, 1.0);")));
		assertNull(IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy) + 1, 0).rgb, 1.0);")));
		assertNull(IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb, 0.5);")));
		assertNull(IdentityCopies.match(frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.yx), 0).rgb, 1.0);")));
		assertNull(IdentityCopies.match(frag("vec4 c = texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0); if (c.r > 1.0) discard; o = c;")));
	}

	@Test
	void skipsAPairWhoseAltIsDeadAfterwards() {
		byte[] copy = frag("o = vec4(texelFetch(colortex0, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);");
		var prog = new Manifest.Program(null, null, null, null, null, null, null, null, List.of(7), List.of(), false, null, null, null, null, 1f, null);
		var clear = new Manifest.Command("clear", null, null, null, null, null, null, null, null, List.of(new Manifest.ClearOp("colortex0", "both", null)));
		var toAlt = new Manifest.Command("fullscreen", "c2", 0, null, null, null, null, null, List.of(new Manifest.Attachment(0, "colortex0", "alt")), null);
		var toMain = new Manifest.Command("fullscreen", "c3", 1, null, null, null, null, null, List.of(new Manifest.Attachment(0, "colortex0", "main")), null);
		var readAlt = new Manifest.Command("fullscreen", "c4", 1, null, null, null, null, null, List.of(new Manifest.Attachment(0, "colortex1", "main")), null);
		var target = new Manifest.Target("colortex0", "color", "R11F_G11F_B10F", "R11F_G11F_B10F", 2, new float[] {1, 1}, null, 1, true, true, null, false, 0);
		var flips = List.of(new Manifest.FlipState(0, List.of()), new Manifest.FlipState(1, List.of(0)));
		var res = List.of(new Manifest.Resource(7, "sampled_image", null, "colortex0", null, null, null));
		var programs = Map.of("c2", prog, "c3", prog, "c4", prog);
		var dead = new Manifest.Dimension(programs, res, List.of(target), flips, List.of(clear, toAlt, toMain), List.of(), null);
		assertEquals(Set.of(1, 2), IdentityCopies.skippable(dead, p -> copy, p -> false));
		var live = new Manifest.Dimension(programs, res, List.of(target), flips, List.of(clear, toAlt, toMain, readAlt), List.of(), null);
		assertEquals(Set.of(), IdentityCopies.skippable(live, p -> copy, p -> false), "alt read afterwards");
		var noClear = new Manifest.Dimension(programs, res, List.of(target), flips, List.of(toAlt, toMain), List.of(), null);
		assertEquals(Set.of(), IdentityCopies.skippable(noClear, p -> copy, p -> false), "alt kept into the next frame");
	}
}
