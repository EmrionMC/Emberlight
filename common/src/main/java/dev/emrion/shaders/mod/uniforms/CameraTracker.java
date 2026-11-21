package dev.emrion.shaders.mod.uniforms;

import org.joml.Vector3d;

/**
 * Iris CameraUniforms.CameraPositionTracker: cameraPosition / previousCameraPosition with the X/Z shift that keeps
 * |x|,|z| below 30000 (or re-centres after a jump of more than 1000 blocks). Pure.
 */
public final class CameraTracker {
	private static final double WALK_RANGE = 30000;
	private static final double TP_RANGE = 1000;

	private final Vector3d shift = new Vector3d();
	public final Vector3d current = new Vector3d();
	public final Vector3d previous = new Vector3d();
	public final Vector3d currentUnshifted = new Vector3d();
	public final Vector3d previousUnshifted = new Vector3d();

	/** Once per frame with the unshifted (true) camera position. */
	public void update(double x, double y, double z) {
		previous.set(current);
		previousUnshifted.set(currentUnshifted);
		current.set(x, y, z).add(shift);
		currentUnshifted.set(x, y, z);
		double dx = shift(current.x, previous.x);
		double dz = shift(current.z, previous.z);
		if (dx != 0.0 || dz != 0.0) {
			shift.x += dx;
			shift.z += dz;
			current.x += dx;
			current.z += dz;
			previous.x += dx;
			previous.z += dz;
		}
	}

	private static double shift(double value, double prev) {
		if (Math.abs(value) > WALK_RANGE || Math.abs(value - prev) > TP_RANGE) return -(value - value % WALK_RANGE);
		return 0.0;
	}
}
