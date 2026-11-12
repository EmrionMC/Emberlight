package dev.emrion.shaders.mod.runtime;

/**
 * Sodium's render-distance test for chunk sections (OcclusionCuller.visitNode/testDistance in Sodium 0.9.3): the point of
 * the section box, grown by one block on every side, that is nearest to the camera must lie inside a vertical cylinder
 * of radius {@code distance} (render distance × 16) and within {@code distance} vertically. Vanilla keeps every section
 * within a square-ish chunk radius instead, so with a pack its corner sections reach past {@code far}, where the pack's
 * border fog paints them as a flat grey wall while Iris (on Sodium) shows sky there.
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
