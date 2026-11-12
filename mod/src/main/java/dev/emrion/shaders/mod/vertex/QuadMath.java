package dev.emrion.shaders.mod.vertex;

/**
 * Per-polygon vertex math with Iris's semantics (NormalHelper, NormI8, ExtendedDataHelper; iris-spec §6.1), kept free of
 * Minecraft types so it is unit-testable. Positions are xyz-interleaved, UVs uv-interleaved, one entry per vertex.
 */
public final class QuadMath {
	private QuadMath() {}

	/** Iris's sentinel for "this triangle has no usable tangent" (never a real packing: w is always ±1). */
	public static final int NO_TANGENT = -1;
	/** What Iris ends up with for a degenerate UV mapping: zero tangent, handedness +1. */
	public static final int ZERO_TANGENT = pack(0, 0, 0, 1);

	/** NormI8 packing (x | y<<8 | z<<16 | w<<24, each (int)(c·127), truncated): matches an RGBA8_SNORM attribute. */
	public static int pack(float x, float y, float z, float w) {
		return ((int) (x * 127) & 0xFF) | ((int) (y * 127) & 0xFF) << 8 | ((int) (z * 127) & 0xFF) << 16 | ((int) (w * 127) & 0xFF) << 24;
	}

	/** One NormI8 component back to float (byte / 127). */
	public static float unpack(int packed, int component) {
		return (byte) (packed >> (component * 8)) / 127.0F;
	}

	/** Face normal of a quad, normalize((p2 − p0) × (p3 − p1)), written to out[0..2] (NormalHelper.computeFaceNormal). */
	public static void faceNormal(float[] p, float[] out) {
		float dx0 = p[6] - p[0], dy0 = p[7] - p[1], dz0 = p[8] - p[2];
		float dx1 = p[9] - p[3], dy1 = p[10] - p[4], dz1 = p[11] - p[5];
		float x = dy0 * dz1 - dz0 * dy1, y = dz0 * dx1 - dx0 * dz1, z = dx0 * dy1 - dy0 * dx1;
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		// joml normalize of a zero vector yields NaN, which (int) turns into 0: same as leaving it zero
		if (len == 0) { out[0] = out[1] = out[2] = 0; return; }
		out[0] = x / len;
		out[1] = y / len;
		out[2] = z / len;
	}

	/**
	 * Packed tangent of triangle (a, b, c) for normal n (NormalHelper.computeTangent), or {@link #NO_TANGENT} if the tangent is
	 * zero. {@code project} first projects the vertices onto the normal's plane (computeTangentSmooth, for smooth normals).
	 */
	public static int tangent(float nx, float ny, float nz, float[] p, float[] uv, int a, int b, int c, boolean project) {
		float x0 = p[a * 3], y0 = p[a * 3 + 1], z0 = p[a * 3 + 2];
		float x1 = p[b * 3], y1 = p[b * 3 + 1], z1 = p[b * 3 + 2];
		float x2 = p[c * 3], y2 = p[c * 3 + 1], z2 = p[c * 3 + 2];
		if (project) {
			float d0 = x0 * nx + y0 * ny + z0 * nz, d1 = x1 * nx + y1 * ny + z1 * nz, d2 = x2 * nx + y2 * ny + z2 * nz;
			x0 -= d0 * nx; y0 -= d0 * ny; z0 -= d0 * nz;
			x1 -= d1 * nx; y1 -= d1 * ny; z1 -= d1 * nz;
			x2 -= d2 * nx; y2 -= d2 * ny; z2 -= d2 * nz;
		}
		float e1x = x1 - x0, e1y = y1 - y0, e1z = z1 - z0;
		float e2x = x2 - x0, e2y = y2 - y0, e2z = z2 - z0;
		float du1 = uv[b * 2] - uv[a * 2], dv1 = uv[b * 2 + 1] - uv[a * 2 + 1];
		float du2 = uv[c * 2] - uv[a * 2], dv2 = uv[c * 2 + 1] - uv[a * 2 + 1];
		float denom = du1 * dv2 - du2 * dv1;
		float f = denom == 0 ? 1 : 1 / denom;
		float tx = f * (dv2 * e1x - dv1 * e2x), ty = f * (dv2 * e1y - dv1 * e2y), tz = f * (dv2 * e1z - dv1 * e2z);
		float tc = rsqrt(tx * tx + ty * ty + tz * tz);
		tx *= tc; ty *= tc; tz *= tc;
		if (tx == 0 && ty == 0 && tz == 0) return NO_TANGENT;
		float bx = f * (-du2 * e1x + du1 * e2x), by = f * (-du2 * e1y + du1 * e2y), bz = f * (-du2 * e1z + du1 * e2z);
		float bc = rsqrt(bx * bx + by * by + bz * bz);
		bx *= bc; by *= bc; bz *= bc;
		// handedness: does the real bitangent agree with tangent × normal?
		float px = ty * nz - tz * ny, py = tz * nx - tx * nz, pz = tx * ny - ty * nx;
		return pack(tx, ty, tz, bx * px + by * py + bz * pz < 0 ? -1 : 1);
	}

	/** Quad tangent: first triangle, else the second one (Iris+Sodium terrain), else a zero tangent. */
	public static int quadTangent(float nx, float ny, float nz, float[] p, float[] uv) {
		int t = tangent(nx, ny, nz, p, uv, 0, 1, 2, false);
		if (t == NO_TANGENT) t = tangent(nx, ny, nz, p, uv, 2, 3, 0, false);
		return t == NO_TANGENT ? ZERO_TANGENT : t;
	}

	/** One at_midBlock component: Iris's signed byte of (block centre − vertex) · 64 (wraps like Iris's {@code & 0xFF}). */
	public static float midBlock(int local, float vertex) {
		return (byte) (int) ((local + 0.5F - vertex) * 64);
	}

	private static float rsqrt(float v) {
		return v == 0 ? 1 : (float) (1.0 / Math.sqrt(v));
	}
}
