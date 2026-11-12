package dev.emrion.shaders.mod.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ShadowCullerTest {
	/** Camera at the origin looking down −Z (GL perspective, 70°). */
	private static final Matrix4f VP = new Matrix4f().setPerspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000f);

	private static boolean box(ShadowCuller c, float x, float y, float z) {
		return c.visible(x - 8, y - 8, z - 8, x + 8, y + 8, z + 8);
	}

	@Test
	void sunOverheadCullsCastersBehindTheCamera() {
		var c = new ShadowCuller().set(-1, 256, true, VP, new Vector3f(0, 1, 0));
		assertTrue(box(c, 0, -20, -100), "in front");
		assertTrue(box(c, 0, 60, -100), "above the view, casts into it");
		assertFalse(box(c, 0, -20, 100), "behind, shadow falls behind");
		assertFalse(box(c, 0, 60, 100), "high behind, shadow falls straight down behind");
		assertFalse(box(c, 0, -20, -300), "outside the distance box");
	}

	@Test
	void sunBehindKeepsCastersBehindThatShadowTheView() {
		var c = new ShadowCuller().set(-1, 256, true, VP, new Vector3f(0, 1, 1).normalize());
		assertTrue(box(c, 0, 40, 40), "high behind the camera, shadow falls forward into the view");
		assertFalse(box(c, 0, -60, 60), "low behind: shadow goes away from the view");
	}

	@Test
	void safeZoneKeepsEverythingNearAndDistanceOnlyKeepsTheBox() {
		var safe = new ShadowCuller().set(64, 256, true, VP, new Vector3f(0, 1, 0));
		assertTrue(box(safe, 0, -20, 40), "behind but inside the voxel safe zone");
		assertFalse(box(safe, 0, -20, 100), "behind, outside the safe zone");
		var distance = new ShadowCuller().set(-1, 32, false, VP, new Vector3f(0, 1, 0));
		assertTrue(box(distance, 0, 0, 30));
		assertFalse(box(distance, 0, 0, 50));
	}
}
