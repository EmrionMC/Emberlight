package dev.emrion.shaders.mod.vertex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QuadMathTest {
	/** Up face of a block as MC emits it (counter-clockwise seen from above), u along +x, v along +z. */
	static final float[] UP = {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0};
	static final float[] UP_UV = {0, 0, 0, 1, 1, 1, 1, 0};

	@Test
	void faceNormalOfUpFacePointsUp() {
		float[] n = new float[3];
		QuadMath.faceNormal(UP, n);
		assertEquals(0, n[0], 1e-6);
		assertEquals(1, n[1], 1e-6);
		assertEquals(0, n[2], 1e-6);
		assertEquals(127 << 8, QuadMath.pack(n[0], n[1], n[2], 0));
	}

	@Test
	void faceNormalOfNorthFace() {
		// north face (-z), counter-clockwise seen from -z
		float[] north = {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0};
		float[] n = new float[3];
		QuadMath.faceNormal(north, n);
		assertEquals(-1, n[2], 1e-6);
	}

	@Test
	void tangentFollowsU() {
		// u grows along +x → tangent (1,0,0); bitangent (v) along +z vs tangent × normal = (1,0,0)×(0,1,0) = (0,0,1): w = +1
		int t = QuadMath.quadTangent(0, 1, 0, UP, UP_UV);
		assertEquals(QuadMath.pack(1, 0, 0, 1), t);
		// mirrored u → tangent (-1,0,0), handedness flips
		float[] mirrored = {1, 0, 1, 1, 0, 1, 0, 0};
		assertEquals(QuadMath.pack(-1, 0, 0, -1), QuadMath.quadTangent(0, 1, 0, UP, mirrored));
	}

	@Test
	void degenerateUvFallsBackToSecondTriangleThenZero() {
		float[] firstDegenerate = {0, 0, 0, 0, 0, 0, 1, 1}; // v0..v2 share a uv, triangle (2,3,0) is usable
		assertEquals(QuadMath.NO_TANGENT, QuadMath.tangent(0, 1, 0, UP, firstDegenerate, 0, 1, 2, false));
		// second triangle: tangent = normalize(p2 - p0) = (1, 0, 1)/√2
		assertEquals(QuadMath.pack(0.70710677F, 0, 0.70710677F, 0) & 0xFFFFFF, QuadMath.quadTangent(0, 1, 0, UP, firstDegenerate) & 0xFFFFFF);
		assertEquals(QuadMath.ZERO_TANGENT, QuadMath.quadTangent(0, 1, 0, UP, new float[8]));
	}

	@Test
	void packTruncatesLikeNormI8() {
		assertEquals(0x81 | 0x3F << 8, QuadMath.pack(-1, 0.5F, 0, 0)); // -127, 63
		assertEquals(-1, QuadMath.unpack(QuadMath.pack(-1, 0, 0, 0), 0), 1e-6);
	}

	@Test
	void midBlockIsCentreMinusVertexTimes64AsSignedByte() {
		assertEquals(32, QuadMath.midBlock(3, 3.0F));
		assertEquals(-32, QuadMath.midBlock(3, 4.0F));
		assertEquals(0, QuadMath.midBlock(3, 3.5F));
		assertEquals(-96, QuadMath.midBlock(0, -2.0F)); // 2.5·64 = 160 wraps like Iris's & 0xFF
	}
}
