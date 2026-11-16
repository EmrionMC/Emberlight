package dev.emrion.shaders.mod.runtime;

/**
 * Sodium's cylindrical render-distance test for sections (as Iris on Sodium sees them). Vanilla's square-ish radius lets
 * corner sections reach past {@code far}, where the pack's border fog paints them as a grey wall.
 */
public final class HorizonCull {
	private HorizonCull() {}

	public static boolean inRenderDistance(int originX, int originY, int originZ, double camX, double camY, double camZ, float distance) {
		double dx = nearestToZero(originX - 1 - camX, originX + 17 - camX);
		double dy = nearestToZero(originY - 1 - camY, originY + 17 - camY);
		double dz = nearestToZero(originZ - 1 - camZ, originZ + 17 - camZ);
		return dx * dx + dz * dz < distance * distance && Math.abs(dy) < distance;
	}

	private static double nearestToZero(double min, double max) {
		return min > 0 ? min : max < 0 ? max : 0;
	}
}
