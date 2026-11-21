package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.mod.EmrionShadersClient;
import net.minecraft.world.phys.Vec3;

/** Debug only ({@code -Demrion.debug.shadowStats=true}): what the shadow pass draws and where its CPU time goes, logged every 5 s. */
final class ShadowStats {
	static final boolean ENABLED = Boolean.getBoolean("emrion.debug.shadowStats");
	private static long collect, entities, record, frames, last;

	static void cpu(long c, long en, long r, Vec3 cam) {
		collect += c;
		entities += en;
		record += r;
		frames++;
		long now = System.nanoTime();
		if (now - last < 5_000_000_000L) return;
		last = now;
		EmrionShadersClient.LOG.info(String.format(java.util.Locale.ROOT, "ShadowStats: cpu ms/frame collect %.3f entities %.3f recordOpaque %.3f; ",
				collect / 1e6 / frames, entities / 1e6 / frames, record / 1e6 / frames)
				+ dev.emrion.shaders.mod.compat.sodium.SodiumShadow.stats(cam.x, cam.y, cam.z));
		collect = entities = record = frames = 0;
	}
}
