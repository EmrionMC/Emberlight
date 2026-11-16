package dev.emrion.shaders.mod.runtime;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;

/**
 * Compiles sections MC's visibility graph has not reached: MC only meshes what the camera's occlusion graph visits, but the
 * shadow pass and RT need sections behind the camera too (Iris gets them from Sodium's shadow-frustum culling). Render thread only.
 */
public final class ForcedSections {
	private ForcedSections() {}

	private static final LongArrayList wanted = new LongArrayList();
	private static final net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
	/** Sections added to MC's compile list by the last {@link #schedule} (autotest/profiler). */
	public static int scheduled;

	public static void want(long sectionNode) {
		wanted.add(sectionNode);
	}

	/** LevelExtractor.extract, right after MC queued its visible dirty sections: queues the wanted ones the same way. */
	public static void schedule(SectionUpdateTracker tracker, ClientLevel level, ViewArea area, LevelRenderState out) {
		scheduled = 0;
		if (wanted.isEmpty()) return;
		RenderRegionCache cache = new RenderRegionCache();
		for (int i = 0; i < wanted.size(); i++) {
			long node = wanted.getLong(i);
			var dirty = tracker.getDirtyState(node);
			if (dirty == null || !dirty.isDirty()) continue;
			var section = area.getRenderSectionAt(pos.set(net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.x(node)),
					net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.y(node)),
					net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.z(node))));
			if (section == null || section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && !tracker.hasAllNeighbors(level, node)) continue;
			out.sectionUpdateRenderStates.add(new SectionUpdateRenderState(node, dirty.isDirtyFromPlayer(), cache.createRegion(level, node)));
			dirty.setNotDirty();
			scheduled++;
		}
		wanted.clear();
	}

	/** The pack stopped: nothing is wanted any more. */
	public static void clear() {
		wanted.clear();
	}
}
