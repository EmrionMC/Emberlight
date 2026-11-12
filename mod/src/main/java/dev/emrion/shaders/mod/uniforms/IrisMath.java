package dev.emrion.shaders.mod.uniforms;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Pure matrix/angle math for the Iris uniforms (no MC classes, unit-tested). Everything produced here is GL convention:
 * NDC z in [-1, 1], near → -1 (ARCHITECTURE §5.2).
 */
public final class IrisMath {
	private IrisMath() {}

	/** Iris CompositeTransformer's gl_ProjectionMatrix: maps the [0,1] quad to NDC. */
	public static final Matrix4fc COMPOSITE_PROJECTION = new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1);

	/** Iris BuiltinReplacementUniforms: gl_TextureMatrix[1] for lightmap coords (scale 1/256, offset 1/32). */
	public static final Matrix4fc LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
			0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0, 0, 0, 0.00390625f, 0, 0.03125f, 0.03125f, 0.03125f, 1);

	/** Iris HandRenderer.DEPTH: the hand projection squeezes clip z so the hand does not clip into blocks. */
	public static final float HAND_DEPTH = 0.125f;

	/**
	 * dest = C·p, C mapping MC 26.3's reverse-Z zero-to-one clip space to GL clip space: z' = w − 2z (x, y, w unchanged).
	 * Reverse-Z: z_ndc = 1 at near, 0 at far. GL: −1 at near, +1 at far. z'/w = 1 − 2·z/w maps 1 → −1 and 0 → 1.
	 * For MC's Projection (JOML setPerspective with near/far swapped and zZeroToOne) this equals
	 * {@code setPerspective(fov, aspect, near, far, false)} exactly (see IrisMathTest).
	 */
	public static Matrix4f reverseZToGl(Matrix4fc p, Matrix4f dest) {
		// Row 2 (m02, m12, m22, m32) becomes row3 − 2·row2.
		float m02 = p.m03() - 2 * p.m02(), m12 = p.m13() - 2 * p.m12(), m22 = p.m23() - 2 * p.m22(), m32 = p.m33() - 2 * p.m32();
		dest.set(p);
		return dest.m02(m02).m12(m12).m22(m22).m32(m32);
	}

	/** Iris CelestialUniforms.getSunAngle: (SUN_ANGLE/MOON_ANGLE attribute + 90°) wrapped once into [0, 360]. */
	public static float celestialAngle(float attributeDegrees) {
		float c = attributeDegrees + 90.0f;
		if (c < 0) c += 360;
		else if (c > 360) c -= 360;
		return c;
	}

	/**
	 * sunPosition/moonPosition: gbufferModelView · RotY(−90°) · RotZ(sunPathRotation) · RotX(rawAngle) · (0, 100, 0, 1).
	 * rawAngle is the SUN_ANGLE/MOON_ANGLE attribute in degrees (not the +90 one). Iris uses +100 for the moon too.
	 */
	public static Vector3f celestialPosition(Matrix4fc gbufferModelView, float sunPathRotation, float rawAngle, Vector3f dest) {
		SCRATCH.set(gbufferModelView)
				.rotateY((float) Math.toRadians(-90.0))
				.rotateZ((float) Math.toRadians(sunPathRotation))
				.rotateX((float) Math.toRadians(rawAngle));
		return xyz(SCRATCH.transform(SCRATCH4.set(0, 100, 0, 1)), dest);
	}

	/** upPosition: gbufferModelView · RotY(−90°) · (0, 100, 0, 0). */
	public static Vector3f upPosition(Matrix4fc gbufferModelView, Vector3f dest) {
		SCRATCH.set(gbufferModelView).rotateY((float) Math.toRadians(-90.0));
		return xyz(SCRATCH.transform(SCRATCH4.set(0, 100, 0, 0)), dest);
	}

	/** endFlashPosition: gbufferModelView · RotY(180 − yAngle) · RotX(−90 − xAngle) · (0, 100, 0, 0). */
	public static Vector3f endFlashPosition(Matrix4fc gbufferModelView, float xAngle, float yAngle, Vector3f dest) {
		SCRATCH.set(gbufferModelView)
				.rotateY((float) Math.toRadians(180.0f - yAngle))
				.rotateX((float) Math.toRadians(-90.0f - xAngle));
		return xyz(SCRATCH.transform(SCRATCH4.set(0, 100, 0, 0)), dest);
	}

	/**
	 * Iris ShadowMatrices.createModelViewMatrix (iris-spec §2.4). shadowAngle is Iris's shadowAngle uniform (0..1).
	 * The result maps <b>player space</b> (world position − unshifted camera position, i.e. MC's camera-relative render
	 * space) to shadow view space: R · T(cam mod interval − interval/2), R = RotX(90)·RotZ(−360·skyAngle)·RotX(sunPathRotation).
	 */
	public static Matrix4f shadowModelView(float shadowAngle, float sunPathRotation, float intervalSize,
	                                       double camX, double camY, double camZ, Matrix4f dest) {
		float skyAngle = shadowAngle < 0.25f ? shadowAngle + 0.75f : shadowAngle - 0.25f;
		dest.identity()
				.rotateX((float) Math.toRadians(90.0))
				.rotateZ((float) Math.toRadians(skyAngle * -360.0f))
				.rotateX((float) Math.toRadians(sunPathRotation));
		snapToGrid(dest, intervalSize, camX, camY, camZ);
		return dest;
	}

	/** End-flash variant of the shadow modelview (Iris, when the pack sets endFlashShadows in the End). */
	public static Matrix4f endFlashShadowModelView(float xAngle, float yAngle, float intervalSize,
	                                               double camX, double camY, double camZ, Matrix4f dest) {
		dest.identity().rotateX((float) Math.toRadians(-xAngle)).rotateY((float) Math.toRadians(yAngle));
		snapToGrid(dest, intervalSize, camX, camY, camZ);
		return dest;
	}

	private static void snapToGrid(Matrix4f m, float interval, double camX, double camY, double camZ) {
		if (Math.abs(interval) == 0.0f) return;
		// Java % keeps the sign, so negative coordinates land in (−1.5·i, 0.5·i): Iris behaviour, kept on purpose.
		float half = interval / 2.0f;
		m.translate((float) camX % interval - half, (float) camY % interval - half, (float) camZ % interval - half);
	}

	/** Iris ShadowMatrices.createOrthoMatrix in GL convention. halfPlane = shadowDistance. */
	public static Matrix4f shadowOrtho(float halfPlane, float near, float far, Matrix4f dest) {
		return dest.setOrthoSymmetric(halfPlane * 2, halfPlane * 2, near, far, false);
	}

	/** Iris ShadowMatrices.createPerspectiveMatrix (shadowMapFov), GL branch: fixed near −100.05 / far 156 and m33 = 1, as in Iris. */
	public static Matrix4f shadowPerspective(float fovDegrees, Matrix4f dest) {
		final float near = -100.05f, far = 156.0f;
		float y = (float) (1.0f / Math.tan(Math.toRadians(fovDegrees) * 0.5f));
		return dest.set(y, 0, 0, 0, 0, y, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0, 2.0f * far * near / (near - far), 1);
	}

	/** Iris HandRenderer projection: Scale(1,1,0.125) · GL perspective(0.05, depthFar, hudFov, w/h) · bob. */
	public static Matrix4f handProjection(float hudFovDegrees, float width, float height, float depthFar, Matrix4fc bob, Matrix4f dest) {
		return dest.scaling(1, 1, HAND_DEPTH)
				.mul(SCRATCH.setPerspective((float) Math.toRadians(hudFovDegrees), width / height, 0.05f, depthFar, false))
				.mul(bob);
	}

	// Render-thread scratch (the uniform system is single-threaded), so per-frame calls do not allocate.
	private static final Matrix4f SCRATCH = new Matrix4f();
	private static final Vector4f SCRATCH4 = new Vector4f();

	private static Vector3f xyz(Vector4f v, Vector3f dest) {
		return dest.set(v.x, v.y, v.z);
	}
}
