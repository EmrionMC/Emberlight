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
		long writeStage, writeAccess, readStages;
		/**
		 * The last write is visible to stage × access for each pair (dst stages, dst access) of a barrier since it; a Vulkan
		 * access scope needs both to match, so the union of the stage masks and of the access masks would claim too much.
		 */
		final long[] visible = new long[2 * VISIBLE];
		int visibleCount;

		Tracked(String label) {
			this.label = label;
		}

		boolean visible(long stage, long access) {
			for (int i = 0; i < visibleCount; i++)
				if ((visible[2 * i] & stage) == stage && (visible[2 * i + 1] & access) == access) return true;
			return false;
		}

		void addVisible(long stage, long access) {
			if (visible(stage, access)) return;
			// shortcut: once full, the oldest pair is forgotten; that only costs an extra barrier
			int i = visibleCount < VISIBLE ? visibleCount++ : 0;
			visible[2 * i] = stage;
			visible[2 * i + 1] = access;
		}
	}

	private static final int VISIBLE = 4;

	private final List<Tracked> touched = new ArrayList<>();
	private final List<Tracked> pending = new ArrayList<>();
	/** Per pending access: stage, access, write (1/0), flattened so that declaring an access allocates nothing. */
	private long[] pendingAccess = new long[48];
	private final long[] masks = new long[4];
	private long src, srcAccess, dst, dstAccess;

	void reset() {
		for (Tracked t : touched) {
			t.writeStage = t.writeAccess = t.readStages = 0;
			t.visibleCount = 0;
		}
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
		if (t.writeStage != 0 && !t.visible(stage, access)) {
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
			// the barrier made every earlier write its source scope covers visible to these stages × accesses
			for (Tracked t : pending)
				if ((t.writeStage & ~src) == 0 && (t.writeAccess & ~srcAccess) == 0) t.addVisible(dst, dstAccess);
		}
		for (int i = 0; i < pending.size(); i++) {
			Tracked t = pending.get(i);
			if (!touched.contains(t)) touched.add(t);
			if (pendingAccess[3 * i + 2] == 1) {
				t.writeStage = pendingAccess[3 * i];
				t.writeAccess = pendingAccess[3 * i + 1];
				t.readStages = 0;
				t.visibleCount = 0;
			} else {
				t.readStages |= pendingAccess[3 * i];
			}
		}
		pending.clear();
		src = srcAccess = dst = dstAccess = 0;
		return result;
	}
}
