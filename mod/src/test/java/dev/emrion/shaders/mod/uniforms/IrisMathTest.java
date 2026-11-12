package dev.emrion.shaders.mod.uniforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class IrisMathTest {
	private static final float EPS = 1e-5f;

	/** MC 26.3 Projection.getMatrix: JOML setPerspective with near/far swapped and zZeroToOne (reverse-Z). */
	private static Matrix4f mcPerspective(float fovDeg, float aspect, float near, float far) {
		return new Matrix4f().setPerspective(fovDeg * (float) (Math.PI / 180.0), aspect, far, near, true);
	}

	private static Vector4f ndc(Matrix4f p, float x, float y, float z) {
		Vector4f v = p.transform(new Vector4f(x, y, z, 1));
		return v.div(v.w);
	}

	@Test
	void reverseZPerspectiveBecomesGlPerspective() {
		float near = 0.05f, far = 1024f, fov = 70f, aspect = 16f / 9f;
		Matrix4f mc = mcPerspective(fov, aspect, near, far);
		Matrix4f gl = IrisMath.reverseZToGl(mc, new Matrix4f());
		Matrix4f expected = new Matrix4f().setPerspective(fov * (float) (Math.PI / 180.0), aspect, near, far, false);
		assertTrue(gl.equals(expected, 1e-4f), () -> gl + " vs " + expected);

		// Points: x/y NDC unchanged, depth: MC reverse-Z [1 at near → 0 at far], GL [−1 → 1], z_gl = 1 − 2·z_mc.
		for (float d : new float[]{near, 0.5f, 7.3f, 100f, 500f, far}) {
			Vector4f a = ndc(mc, 1.5f, -0.75f, -d), b = ndc(gl, 1.5f, -0.75f, -d);
			assertEquals(a.x, b.x, EPS);
			assertEquals(a.y, b.y, EPS);
			assertEquals(1 - 2 * a.z, b.z, 1e-4f);
		}
		assertEquals(1.0f, ndc(mc, 0, 0, -near).z, 1e-4f);
		assertEquals(-1.0f, ndc(gl, 0, 0, -near).z, 1e-4f);
		assertEquals(0.0f, ndc(mc, 0, 0, -far).z, 1e-4f);
		assertEquals(1.0f, ndc(gl, 0, 0, -far).z, 1e-4f);
		// GL window depth (z+1)/2, what our depth buffers store, is monotonic increasing with distance.
		assertTrue(ndc(gl, 0, 0, -10).z < ndc(gl, 0, 0, -11).z);
	}

	@Test
	void reverseZOrthoBecomesGlOrtho() {
		// Projection.setupOrtho path: setOrtho(0, w, h, 0, far, near, zZeroToOne) with near/far swapped.
		Matrix4f mc = new Matrix4f().setOrtho(0, 800, 600, 0, 3000, 1000, true);
		Matrix4f gl = IrisMath.reverseZToGl(mc, new Matrix4f());
		assertTrue(gl.equals(new Matrix4f().setOrtho(0, 800, 600, 0, 1000, 3000, false), 1e-5f));
	}

	@Test
	void shadowModelViewAtNoonWithoutSunPathRotation() {
		// shadowAngle 0.25 (noon) → skyAngle 0 → M = RotX(90) · T(offset); camera (0.5, 64.25, −3.5), interval 2:
		// offset = (0.5 % 2 − 1, 64.25 % 2 − 1, −3.5 % 2 − 1) = (−0.5, −0.75, −2.5)   (Java % keeps the sign)
		Matrix4f m = IrisMath.shadowModelView(0.25f, 0, 2, 0.5, 64.25, -3.5, new Matrix4f());
		Vector4f up = m.transform(new Vector4f(0, 1, 0, 0));
		assertVec(0, 0, 1, up); // world up points at the light (towards the viewer, +z)
		// RotX(90): (x, y, z) → (x, −z, y), so the origin goes to (−0.5, 2.5, −0.75)
		assertVec(-0.5f, 2.5f, -0.75f, m.transform(new Vector4f(0, 0, 0, 1)));
	}

	@Test
	void shadowModelViewWithSunPathRotationAndSunrise() {
		// Complementary: sunPathRotation −40 → RotX(90)·RotX(−40) = RotX(50): up (0,1,0) → (0, cos50, sin50)
		Matrix4f m = IrisMath.shadowModelView(0.25f, -40, 0, 0, 0, 0, new Matrix4f());
		assertVec(0, (float) Math.cos(Math.toRadians(50)), (float) Math.sin(Math.toRadians(50)), m.transform(new Vector4f(0, 1, 0, 0)));
		// shadowAngle 0 (sunrise) → skyAngle 0.75 → RotZ(−270°) = RotZ(90): east (+x) → +y → (RotX 90) +z: light in the east
		Matrix4f r = IrisMath.shadowModelView(0.0f, 0, 0, 0, 0, 0, new Matrix4f());
		assertVec(0, 0, 1, r.transform(new Vector4f(1, 0, 0, 0)));
	}

	@Test
	void shadowOrthoMatchesHandComputedGlOrtho() {
		Matrix4f p = IrisMath.shadowOrtho(256, -100.05f, 156, new Matrix4f());
		assertEquals(1 / 256f, p.m00(), EPS);
		assertEquals(1 / 256f, p.m11(), EPS);
		assertEquals(-2 / (156 + 100.05f), p.m22(), EPS);
		assertEquals(-(156 - 100.05f) / (156 + 100.05f), p.m32(), EPS);
		assertEquals(1, p.m33(), 0);
		// view z = −near (+100.05, behind the camera) → −1, view z = −far → +1
		assertEquals(-1, ndc(p, 0, 0, 100.05f).z, 1e-4f);
		assertEquals(1, ndc(p, 0, 0, -156).z, 1e-4f);
	}

	@Test
	void shadowPerspectiveKeepsIrisQuirks() {
		Matrix4f p = IrisMath.shadowPerspective(90, new Matrix4f());
		assertEquals(1, p.m00(), EPS);
		assertEquals((156 - 100.05f) / (-100.05f - 156), p.m22(), EPS);
		assertEquals(-1, p.m23(), 0);
		assertEquals(1, p.m33(), 0); // sic: Iris's matrix has m33 = 1
	}

	@Test
	void celestialPositions() {
		// MC 26.3 timeline: SUN_ANGLE = 0 at noon (6000) → sunAngle (0+90)/360 = 0.25, sun straight up in view space for an identity view.
		assertEquals(90, IrisMath.celestialAngle(0), 0);
		assertEquals(0.25f, IrisMath.celestialAngle(0) / 360f, 0);
		assertEquals(10, IrisMath.celestialAngle(280), 0); // 370 → 10
		assertEquals(350, IrisMath.celestialAngle(-100), 0);
		Matrix4f view = new Matrix4f();
		assertVec(0, 100, 0, IrisMath.celestialPosition(view, 0, 0, new Vector3f()));
		assertVec(0, 100, 0, IrisMath.upPosition(view, new Vector3f()));
		// Moon at noon: MOON_ANGLE = 180 → (0, −100, 0) under the horizon
		assertVec(0, -100, 0, IrisMath.celestialPosition(view, 0, 180, new Vector3f()));
		// Sun 90° later in the day goes west: RotY(−90)·RotX(90)·(0,100,0) = RotY(−90)·(0,0,100) = (−100, 0, 0)
		assertVec(-100, 0, 0, IrisMath.celestialPosition(view, 0, 90, new Vector3f()));
		// A view rotation is applied on the left: pitch down 90° maps up (0,100,0) to (0,0,100)... RotX(90)·(0,100,0) = (0,0,100)
		assertVec(0, 0, 100, IrisMath.upPosition(new Matrix4f().rotateX((float) Math.toRadians(90)), new Vector3f()));
	}

	@Test
	void handProjectionSqueezesGlDepth() {
		Matrix4f h = IrisMath.handProjection(70, 1600, 900, 1024, new Matrix4f(), new Matrix4f());
		Matrix4f gl = new Matrix4f().setPerspective((float) Math.toRadians(70), 1600f / 900f, 0.05f, 1024, false);
		Vector4f a = ndc(h, 0.1f, 0.1f, -0.5f), b = ndc(gl, 0.1f, 0.1f, -0.5f);
		assertEquals(b.x, a.x, EPS);
		assertEquals(b.z * IrisMath.HAND_DEPTH, a.z, EPS);
	}

	private static void assertVec(float x, float y, float z, Vector4f v) {
		assertEquals(x, v.x, 1e-4f, "x of " + v);
		assertEquals(y, v.y, 1e-4f, "y of " + v);
		assertEquals(z, v.z, 1e-4f, "z of " + v);
	}

	private static void assertVec(float x, float y, float z, Vector3f v) {
		assertVec(x, y, z, new Vector4f(v, 0));
	}
}
