package dev.emrion.shaders.mod.debug;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * -Demrion.debug.terrainMarks=true: splits the geometry phases by terrain pipeline. Each segment gets a GPU timestamp
 * (Profiler label "phase: pipeline") and a pipeline-statistics query (primitives in, vertex invocations, primitives after
 * clipping, fragment invocations), logged as per-frame averages every 600 frames.
 */
public final class TerrainStats {
	private TerrainStats() {}

	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.terrainMarks");
	private static final int LAG = 3, PER_FRAME = 32, VALUES = 4;

	private static @Nullable String open;
	private static @Nullable VkCommandBuffer openCb;
	/** Label of the current geometry phase (set by the frame executor). */
	public static String phase = "";

	private static long pool;
	private static int slot, queries;
	private static final String[][] labels = new String[LAG][PER_FRAME];
	private static final int[] counts = new int[LAG];
	private static final Map<String, long[]> acc = new LinkedHashMap<>();
	private static int frames;

	/** A pipeline was bound in a geometry pass; {@code name} is its vanilla name. */
	public static void bound(VkCommandBuffer cb, @Nullable String name) {
		String next = name != null && name.contains("terrain") ? name : null;
		if (java.util.Objects.equals(next, open)) return;
		close(cb);
		open = next;
		openCb = cb;
		if (next != null && pool != 0 && queries < PER_FRAME) {
			labels[slot][queries] = phase + ": " + next;
			vkCmdBeginQuery(cb, pool, slot * PER_FRAME + queries, 0);
		}
	}

	/** The geometry pass is about to end (still inside it). */
	public static void passEnd() {
		if (openCb != null) close(openCb);
		open = null;
		openCb = null;
	}

	private static void close(VkCommandBuffer cb) {
		Profiler.mark(cb, "  " + phase + ": " + (open != null ? open : "other"));
		if (open != null && pool != 0 && queries < PER_FRAME) vkCmdEndQuery(cb, pool, slot * PER_FRAME + queries++);
	}

	/** Frame start: read the queries of LAG frames ago, reset them. */
	public static void frame() {
		if (!ENABLED) return;
		var ctx = VulkanContext.get();
		if (pool == 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(LAG * PER_FRAME)
						.pipelineStatistics(VK_QUERY_PIPELINE_STATISTIC_INPUT_ASSEMBLY_PRIMITIVES_BIT | VK_QUERY_PIPELINE_STATISTIC_VERTEX_SHADER_INVOCATIONS_BIT
								| VK_QUERY_PIPELINE_STATISTIC_CLIPPING_PRIMITIVES_BIT | VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT);
				var p = stack.mallocLong(1);
				if (vkCreateQueryPool(ctx.vk, info, null, p) != VK_SUCCESS) return;
				pool = p.get(0);
				org.lwjgl.vulkan.VK12.vkResetQueryPool(ctx.vk, pool, 0, LAG * PER_FRAME);
			}
		}
		counts[slot] = queries;
		slot = (slot + 1) % LAG;
		int n = counts[slot];
		if (n > 0) {
			long[] r = new long[n * (VALUES + 1)];
			int res = vkGetQueryPoolResults(ctx.vk, pool, slot * PER_FRAME, n, r, (VALUES + 1) * 8L,
					VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
			boolean ok = res == VK_SUCCESS;
			for (int i = 0; ok && i < n; i++) ok = r[i * (VALUES + 1) + VALUES] != 0;
			if (ok) {
				for (int i = 0; i < n; i++) {
					long[] a = acc.computeIfAbsent(labels[slot][i], k -> new long[VALUES]);
					for (int v = 0; v < VALUES; v++) a[v] += r[i * (VALUES + 1) + v];
				}
				if (++frames == 600) {
					StringBuilder sb = new StringBuilder("Terrain stats (per frame, avg of 600): prims in / VS invocations / prims after clip / FS invocations");
					acc.forEach((k, a) -> sb.append(String.format("%n  %-60s %9d %9d %9d %10d", k, a[0] / frames, a[1] / frames, a[2] / frames, a[3] / frames)));
					EmrionShadersClient.LOG.info(sb.toString());
					acc.clear();
					frames = 0;
				}
			}
			org.lwjgl.vulkan.VK12.vkResetQueryPool(ctx.vk, pool, slot * PER_FRAME, PER_FRAME);
		}
		counts[slot] = 0;
		queries = 0;
	}
}
