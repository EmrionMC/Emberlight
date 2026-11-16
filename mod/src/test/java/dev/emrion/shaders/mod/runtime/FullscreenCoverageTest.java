package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.emrion.shaders.converter.spirv.ShadercSpirvCompiler;
import org.junit.jupiter.api.Test;

class FullscreenCoverageTest {
	/** A composite vertex stage as the converter emits it (GlslTranspiler's emr_fs* / emr_ftransform helpers, z epilogue). */
	private static byte[] vert(String body) {
		String src = """
				#version 450
				layout(set = 0, binding = 0) uniform U { float viewWidth; float frameTimeCounter; } u;
				layout(location = 0) out vec2 texCoord;
				int emr_fsVertexId() { return int[](0, 1, 2, 2, 3, 0)[gl_VertexIndex]; }
				vec2 emr_fsUV() { int v = emr_fsVertexId(); return vec2(float(v == 1 || v == 2), float(v >= 2)); }
				vec4 emr_ftransform() { return mat4(vec4(2.0, 0.0, 0.0, 0.0), vec4(0.0, 2.0, 0.0, 0.0), vec4(0.0), vec4(-1.0, -1.0, 0.0, 1.0))
						* vec4(emr_fsUV(), 0.0, 1.0); }
				void main() {
				""" + body + """

					texCoord = emr_fsUV();
					gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
				}
				""";
		try (var c = new ShadercSpirvCompiler()) {
			var r = c.compile("t.vsh", src, dev.emrion.shaders.converter.glsl.StageType.VERTEX);
			assertNotNull(r.spv(), r.diagnostics().toString());
			return r.spv();
		}
	}

	@Test
	void provesThePlainQuad() {
		assertTrue(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform();")));
		assertTrue(FullscreenCoverage.covers(vert("gl_Position = vec4(emr_fsUV() * 2.0 - 1.0, 0.0, 1.0);")));
		// a branch on a uniform that only touches a varying
		assertTrue(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform(); if (u.frameTimeCounter > 1.0) texCoord = vec2(0.5);")));
	}

	@Test
	void rejectsMovedOrUnknownQuads() {
		assertFalse(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform(); gl_Position.xy = gl_Position.xy * 0.5 - 0.5;")), "bloom tile");
		assertFalse(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform(); gl_Position.x += 0.01;")), "shifted");
		assertFalse(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform(); gl_Position.xy *= u.viewWidth;")), "depends on a uniform");
		assertFalse(FullscreenCoverage.covers(vert("gl_Position = emr_ftransform(); if (u.frameTimeCounter > 1.0) gl_Position.x *= 0.5;")),
				"may be moved behind a branch");
		assertFalse(FullscreenCoverage.covers(vert("gl_Position = vec4(emr_fsUV() * 2.0 - 1.0, 2.0, 1.0);")), "depth-clipped");
		assertFalse(FullscreenCoverage.covers(new byte[] {1, 2, 3}));
	}

	@Test
	void unionOfTwoTriangles() {
		double[][] a = {{-1, -1}, {1, -1}, {1, 1}}, b = {{1, 1}, {-1, 1}, {-1, -1}};
		assertTrue(FullscreenCoverage.coversSquare(a, b));
		assertFalse(FullscreenCoverage.coversSquare(a, a), "overlapping halves leave a gap");
		assertTrue(FullscreenCoverage.coversSquare(new double[][] {{-1, -1}, {3, -1}, {-1, 3}}, new double[][] {{0, 0}, {0, 0}, {0, 0}}));
	}
}
