package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.mod.vk.Barriers;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Per-command-buffer hazard tracking for raw passes. Every image stays in GENERAL, so one global sync2 memory barrier per
 * pass is enough; its masks are exactly the RAW/WAR/WAW hazards of the resources the pass declares, never ALL_COMMANDS.
 * A new command buffer starts clean: MC fences everything before it with its global barrier.
 */
final class Hazards {
	/** Hazard state of one physical image or buffer. */
	static class Tracked {
		final String label;
		long writeStage, writeAccess, readStages, visibleStages, visibleAccess;

		Tracked(String label) {
			this.label = label;
		}
	}

	private final List<Tracked> touched = new ArrayList<>();
	private final List<Tracked> pending = new ArrayList<>();
	private final List<long[]> pendingAccess = new ArrayList<>();
	private long src, srcAccess, dst, dstAccess;

	void reset() {
		for (Tracked t : touched) t.writeStage = t.writeAccess = t.readStages = t.visibleStages = t.visibleAccess = 0;
		touched.clear();
	}

	void read(Tracked t, long stage, long access) {
		access(t, stage, access, false);
	}

	/** {@code access} includes any read the write implies (blending, read-modify-write storage). */
	void write(Tracked t, long stage, long access) {
		access(t, stage, access, true);
	}

	private void access(Tracked t, long stage, long access, boolean write) {
		if (t == null) return;
		if (t.writeStage != 0 && ((t.visibleStages & stage) != stage || (t.visibleAccess & access) != access)) {
			src |= t.writeStage;
			srcAccess |= t.writeAccess;
			dst |= stage;
			dstAccess |= access;
		}
		if (write && t.readStages != 0) { // write-after-read: execution dependency only
			src |= t.readStages;
			dst |= stage;
		}
		pending.add(t);
		pendingAccess.add(new long[] {stage, access, write ? 1 : 0});
	}

	/** Emits the barrier for the accesses declared since the last call, then records them as done. */
	void flush(VkCommandBuffer cb) {
		long[] m = flushMasks();
		if (m != null) Barriers.memory(cb, m[0], m[1], m[2], m[3]);
	}

	/** The barrier masks {srcStage, srcAccess, dstStage, dstAccess} the declared accesses need, or null. */
	long[] flushMasks() {
		long[] masks = src == 0 ? null : new long[] {src, srcAccess, dst, dstAccess};
		if (src != 0) {
			// the barrier made every earlier write visible to these stages
			for (Tracked t : pending) {
				t.visibleStages |= dst;
				t.visibleAccess |= dstAccess;
			}
		}
		for (int i = 0; i < pending.size(); i++) {
			Tracked t = pending.get(i);
			long[] a = pendingAccess.get(i);
			if (!touched.contains(t)) touched.add(t);
			if (a[2] == 1) {
				t.writeStage = a[0];
				t.writeAccess = a[1];
				t.readStages = t.visibleStages = t.visibleAccess = 0;
			} else {
				t.readStages |= a[0];
			}
		}
		pending.clear();
		pendingAccess.clear();
		src = srcAccess = dst = dstAccess = 0;
		return masks;
	}
}
