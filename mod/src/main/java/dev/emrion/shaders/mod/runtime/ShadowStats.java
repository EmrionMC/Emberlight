package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.mod.EmrionShadersClient;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.phys.Vec3;

/** Debug only ({@code -Demrion.debug.shadowStats=true}): what the shadow pass draws and where its CPU time goes, logged every 5 s. */
final class ShadowStats {
	static final boolean ENABLED = Boolean.getBoolean("emrion.debug.shadowStats");
	private static long collect, prepare, entities, record, frames, last;

	static void cpu(long c, long p, long en, long r, LevelRenderer level, Vec3 cam, ShadowCuller culler, ObjectArrayList<SectionRenderDispatcher.RenderSection> sections) {
		collect += c;
		prepare += p;
		entities += en;
		record += r;
		frames++;
		long now = System.nanoTime();
		if (now - last < 5_000_000_000L) return;
		last = now;
		StringBuilder b = new StringBuilder(String.format("ShadowStats: cpu ms/frame collect %.3f prepare %.3f entities %.3f recordOpaque %.3f; ",
				collect / 1e6 / frames, prepare / 1e6 / frames, entities / 1e6 / frames, record / 1e6 / frames));
		collect = prepare = entities = record = frames = 0;
		// [safe/band][dark/lit][below/above camera] -> sections, opaque indices, translucent indices
		long[][] n = new long[8][3];
		var sky = Minecraft.getInstance().level.getLightEngine().getLayerListener(LightLayer.SKY);
		for (var s : sections) {
			var o = s.getRenderOrigin();
			float x = (float) (o.getX() - cam.x), y = (float) (o.getY() - cam.y), z = (float) (o.getZ() - cam.z);
			boolean safe = culler.inSafe(x, y, z, x + 16, y + 16, z + 16);
			long node = SectionPos.asLong(o.getX() >> 4, o.getY() >> 4, o.getZ() >> 4);
			boolean dark = darkAll(node);
			int k = (safe ? 0 : 4) + (dark ? 0 : 2) + (y + 16 < 0 ? 0 : 1);
			n[k][0]++;
			var m = s.getSectionMesh();
			for (var l : ChunkSectionLayer.values()) {
				var draw = m.getSectionDraw(l);
				if (draw != null) n[k][l.translucent() ? 2 : 1] += draw.indexCount();
			}
		}
		String[] names = {"safe/dark/below", "safe/dark/above", "safe/lit/below", "safe/lit/above", "band/dark/below", "band/dark/above", "band/lit/below", "band/lit/above"};
		for (int i = 0; i < 8; i++) b.append(String.format("%s %d sec %d kTri %d kTriT; ", names[i], n[i][0], n[i][1] / 3000, n[i][2] / 3000));
		EmrionShadersClient.LOG.info(b.toString());
	}

	private static boolean darkAll(long node) {
		var sky = Minecraft.getInstance().level.getLightEngine().getLayerListener(LightLayer.SKY);
		boolean dark = dark(sky, node);
		for (var d : net.minecraft.core.Direction.values()) dark &= dark(sky, SectionPos.offset(node, d));
		return dark;
	}

	/** Sky light 0 in every block of the section (MC's client light storage; a missing layer inherits the bottom row above). */
	private static boolean dark(net.minecraft.world.level.lighting.LayerLightEventListener sky, long node) {
		DataLayer l = sky.getDataLayerData(SectionPos.of(node));
		if (l == null) return false; // conservative: unknown = lit
		if (l.isDefinitelyHomogenous()) return l.isEmpty();
		for (byte v : l.getData()) if (v != 0) return false;
		return true;
	}
}
