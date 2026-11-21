package dev.emrion.shaders.mod.runtime;

import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Iris's shadow-pass culling (ShadowRenderer.createShadowFrustum), on camera-relative boxes:
 * <ul>
 *   <li>{@code max}: boxes outside the cube of half size {@code max} around the camera are culled (Iris BoxCuller);</li>
 *   <li>{@code safe}: boxes touching the cube of half size {@code safe} are always kept (Iris SAFE_ZONE, the pack's
 *       {@code shadow.culling=reversed}, so everything near the player reaches the voxelizer);</li>
 *   <li>{@code frustum}: the rest must pass the tight caster test (Iris AdvancedShadowCullingFrustum, after L. Spiro): the
 *       player frustum planes facing the light plus the silhouette edges extruded along the light direction.</li>
 * </ul>
 */
final class ShadowCuller {
	static final float UNLIMITED = Float.POSITIVE_INFINITY;

	private final float[][] planes = new float[13][4];
	private final Vector4f[] base = new Vector4f[6];
	private int planeCount;
	private float safe = -1, max = UNLIMITED;
	private boolean frustum;

	ShadowCuller() {
		for (int i = 0; i < 6; i++) base[i] = new Vector4f();
	}

	/**
	 * {@code safe} &lt; 0: no safe zone. {@code viewProjection}: player projection · camera rotation (GL clip space);
	 * {@code light}: unit vector towards the shadow light, both camera-relative.
	 */
	ShadowCuller set(float safe, float max, boolean frustum, Matrix4fc viewProjection, Vector3f light) {
		this.safe = safe;
		this.max = max;
		this.frustum = frustum;
		planeCount = 0;
		if (!frustum) return this;
		// planes in Iris's order: x ≤ w, x ≥ −w, y ≤ w, y ≥ −w, z ≤ w (far), z ≥ −w (near); inside: n·p + w ≥ 0
		Vector4f w = viewProjection.getRow(3, new Vector4f());
		for (int i = 0; i < 6; i++) {
			viewProjection.getRow(i >> 1, base[i]);
			if ((i & 1) == 0) base[i].negate();
			base[i].add(w);
		}
		boolean[] back = new boolean[6];
		for (int i = 0; i < 6; i++) {
			Vector4f p = base[i];
			float len = (float) Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
			if (len < 1e-6f) continue; // degenerate (infinite far plane): no plane
			p.div(len);
			float dot = p.x * light.x + p.y * light.y + p.z * light.z;
			back[i] = dot > 0;
			if (dot >= 0) add(p.x, p.y, p.z, p.w);
		}
		for (int i = 0; i < 6; i++) {
			if (!back[i]) continue;
			for (int j = 0; j < 6; j++) if (j >> 1 != i >> 1 && !back[j] && isPlane(j)) edge(base[i], base[j], light);
		}
		return this;
	}

	private boolean isPlane(int i) {
		Vector4f p = base[i];
		return p.x * p.x + p.y * p.y + p.z * p.z > 0.5f; // normalized above unless degenerate
	}

	private void add(float x, float y, float z, float w) {
		float[] p = planes[planeCount++];
		p[0] = x;
		p[1] = y;
		p[2] = z;
		p[3] = w;
	}

	/** The plane through the intersection line of {@code back} and {@code front} that contains the light direction. */
	private void edge(Vector4f back, Vector4f front, Vector3f light) {
		Vector3f nb = new Vector3f(back.x, back.y, back.z), nf = new Vector3f(front.x, front.y, front.z);
		Vector3f line = nb.cross(nf, new Vector3f());
		float len2 = line.lengthSquared();
		if (len2 < 1e-12f) return;
		Vector3f normal = line.cross(light, new Vector3f()); // = nf(nb·L) − nb(nf·L): a positive mix of inward normals
		// a point on the line: ((line × nb)·(−wf) + (nf × line)·(−wb)) / |line|²
		Vector3f point = line.cross(nb, new Vector3f()).mul(-front.w).add(nf.cross(line, new Vector3f()).mul(-back.w)).div(len2);
		add(normal.x, normal.y, normal.z, -normal.dot(point));
	}

	boolean inSafe(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return safe >= 0 && !(maxX < -safe || minX > safe || maxY < -safe || minY > safe || maxZ < -safe || minZ > safe);
	}

	/** Camera-relative box. */
	boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (maxX < -max || minX > max || maxY < -max || minY > max || maxZ < -max || minZ > max) return false;
		if (safe >= 0 && !(maxX < -safe || minX > safe || maxY < -safe || minY > safe || maxZ < -safe || minZ > safe)) return true;
		for (int i = 0; i < planeCount; i++) {
			float[] p = planes[i];
			float x = p[0] < 0 ? minX : maxX, y = p[1] < 0 ? minY : maxY, z = p[2] < 0 ? minZ : maxZ;
			if (p[0] * x + p[1] * y + p[2] * z < -p[3]) return false;
		}
		return true;
	}
}
