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
	/** Per pending access: stage, access, write (1/0), flattened so that declaring an access allocates nothing. */
	private long[] pendingAccess = new long[48];
	private final long[] masks = new long[4];
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
		int n = pending.size() * 3;
		if (n + 3 > pendingAccess.length) pendingAccess = java.util.Arrays.copyOf(pendingAccess, 2 * pendingAccess.length);
		pendingAccess[n] = stage;
		pendingAccess[n + 1] = access;
		pendingAccess[n + 2] = write ? 1 : 0;
		pending.add(t);
	}

	/** Emits the barrier for the accesses declared since the last call, then records them as done. */
	void flush(VkCommandBuffer cb) {
		long[] m = flushMasks();
		if (m != null) Barriers.memory(cb, m[0], m[1], m[2], m[3]);
	}

	/** The barrier masks {srcStage, srcAccess, dstStage, dstAccess} the declared accesses need (reused array), or null. */
	long[] flushMasks() {
		long[] result = src == 0 ? null : masks;
		if (src != 0) {
			masks[0] = src;
			masks[1] = srcAccess;
			masks[2] = dst;
			masks[3] = dstAccess;
			// the barrier made every earlier write visible to these stages
			for (Tracked t : pending) {
				t.visibleStages |= dst;
				t.visibleAccess |= dstAccess;
			}
		}
		for (int i = 0; i < pending.size(); i++) {
			Tracked t = pending.get(i);
			if (!touched.contains(t)) touched.add(t);
			if (pendingAccess[3 * i + 2] == 1) {
				t.writeStage = pendingAccess[3 * i];
				t.writeAccess = pendingAccess[3 * i + 1];
				t.readStages = t.visibleStages = t.visibleAccess = 0;
			} else {
				t.readStages |= pendingAccess[3 * i];
			}
		}
		pending.clear();
		src = srcAccess = dst = dstAccess = 0;
		return result;
	}
}
