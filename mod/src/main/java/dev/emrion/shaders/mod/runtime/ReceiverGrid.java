package dev.emrion.shaders.mod.runtime;

import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4fc;

/**
 * Receiver-driven caster test: a coarse light-space grid holding, per cell, the depth of the receiver point farthest from the
 * light. A caster box can only darken a receiver in a cell it covers if part of it is closer to the light than that receiver.
 * Boxes are camera-relative; light space is the shadow model-view rotation (view +Z points towards the light).
 */
public final class ReceiverGrid {
	/** User option "Shadow culling" ({@code perf.shadowReceivers}): off, outside (only outside the voxel volume), all. */
	public static final List<String> MODES = List.of("off", "outside", "all");
	public static volatile String mode = "all";

	private static final float CELL = 8f;
	private static final int N = 160; // ±640 blocks: the 256 cube's corners plus margins at any light angle
	/** Footprint dilation in blocks: shadow filtering, normal-offset bias and vertex waving around a receiver. */
	private static final float PAD = 8f;
	/** Depth slack in blocks for the shadow bias. */
	private static final float DEPTH_PAD = 4f;

	private final float[] far = new float[N * N];
	private float ux, uy, uz, vx, vy, vz, lx, ly, lz;
	private boolean any;

	void begin(Matrix4fc shadowView) {
		ux = shadowView.m00(); uy = shadowView.m10(); uz = shadowView.m20();
		vx = shadowView.m01(); vy = shadowView.m11(); vz = shadowView.m21();
		lx = shadowView.m02(); ly = shadowView.m12(); lz = shadowView.m22();
		Arrays.fill(far, Float.POSITIVE_INFINITY);
		any = false;
	}

	void addReceiver(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		float cx = (minX + maxX) * .5f, cy = (minY + maxY) * .5f, cz = (minZ + maxZ) * .5f;
		float hx = (maxX - minX) * .5f, hy = (maxY - minY) * .5f, hz = (maxZ - minZ) * .5f;
		float d = cx * lx + cy * ly + cz * lz - (Math.abs(lx) * hx + Math.abs(ly) * hy + Math.abs(lz) * hz) - DEPTH_PAD;
		float u = cx * ux + cy * uy + cz * uz, ru = Math.abs(ux) * hx + Math.abs(uy) * hy + Math.abs(uz) * hz + PAD;
		float v = cx * vx + cy * vy + cz * vz, rv = Math.abs(vx) * hx + Math.abs(vy) * hy + Math.abs(vz) * hz + PAD;
		int u0 = cell(u - ru), u1 = cell(u + ru), v0 = cell(v - rv), v1 = cell(v + rv);
		for (int j = v0; j <= v1; j++)
			for (int i = u0; i <= u1; i++) if (d < far[j * N + i]) far[j * N + i] = d;
		any = true;
	}

	/** True if some point of the box is closer to the light than a receiver in a cell the box covers. */
	boolean casts(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!any) return false;
		float cx = (minX + maxX) * .5f, cy = (minY + maxY) * .5f, cz = (minZ + maxZ) * .5f;
		float hx = (maxX - minX) * .5f, hy = (maxY - minY) * .5f, hz = (maxZ - minZ) * .5f;
		float d = cx * lx + cy * ly + cz * lz + Math.abs(lx) * hx + Math.abs(ly) * hy + Math.abs(lz) * hz;
		float u = cx * ux + cy * uy + cz * uz, ru = Math.abs(ux) * hx + Math.abs(uy) * hy + Math.abs(uz) * hz;
		float v = cx * vx + cy * vy + cz * vz, rv = Math.abs(vx) * hx + Math.abs(vy) * hy + Math.abs(vz) * hz;
		int u0 = cell(u - ru), u1 = cell(u + ru), v0 = cell(v - rv), v1 = cell(v + rv);
		for (int j = v0; j <= v1; j++)
			for (int i = u0; i <= u1; i++) if (far[j * N + i] < d) return true;
		return false;
	}

	private static int cell(float x) {
		return Math.clamp((int) Math.floor(x / CELL) + N / 2, 0, N - 1);
	}
}
