package dev.emrion.shaders.mod.pipeline;

import dev.emrion.shaders.mod.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Whether this frame's opaque terrain gets the depth prepass (SodiumWorldRendererMixin). The prepass pays off only where terrain
 * overdraws: forest 3.5 fragments per visible pixel, a street seen head-on about 1.0, where it only costs the second vertex pass
 * (+0.3 ms). Two pipeline-statistics queries measure the overdraw on prepass frames: fragment shader invocations of the prepass
 * (every fragment the scene would shade without it, helper lanes included) over those of the shading pass (the visible ones).
 * Occlusion counts left out the helper lanes and read 1.16 where the prepass still saved 0.4 ms (overview). Below {@link #OFF} for a few
 * frames the prepass stops; then every {@link #PROBE}th frame still runs it to measure, and at {@link #ON} or above it resumes.
 * Same image either way, with one exception: Euphoria's distant snow-layer dither (prepass_snow.glsl) is skipped after a prepass,
 * so with that IPBR option the dither band can change on a switch or probe frame (shortcut: not in the target settings; turn the
 * adaptivity off for conversions with that patch if it shows). The query pool lives as long as the device. Off (always prepass): -Demrion.perf.prepassAdaptive=false, a device without pipeline-statistics queries, and with
 * -Demrion.debug.terrainMarks.
 */
public final class TerrainPrepass {
	private TerrainPrepass() {}

	// measured: street 1.02 (prepass +0.25 ms), overview 1.40 and torch hall 1.52 (-0.45 ms), pool 1.5, forest 2.8 (-4 ms)
	private static final double OFF = 1.12, ON = 1.2;
	private static final int LOW_FRAMES = 8, PROBE = 20, LAG = dev.emrion.shaders.mod.vk.UboRing.REGIONS;

	private static final boolean DEBUG = Boolean.getBoolean("emrion.debug.prepass");
	private static long pool;
	private static boolean unsupported;
	/** Ring slot of this frame; per slot whether its two queries were written. */
	private static int slot;
	private static final boolean[] written = new boolean[LAG];
	private static boolean on = true;
	private static int low, sinceProbe;
	private static boolean decided, decision;
	private static VkCommandBuffer cb;
	private static boolean shading;

	/** Level render start: reads the queries of {@link #LAG} frames ago (complete by then: that many frames are in flight at most). */
	public static void newFrame() {
		decided = false;
		if (pool == 0) return;
		slot = (slot + 1) % LAG;
		if (written[slot]) {
			read(slot);
			written[slot] = false;
		}
	}

	/** Before the opaque terrain: true to draw it with the prepass. {@code commandBuffer}: the render pass's command buffer. */
	public static boolean begin(@org.jspecify.annotations.Nullable VkCommandBuffer commandBuffer) {
		// TerrainStats has its own pipeline-statistics queries open then (one active query per type)
		// with attachment groups a pipeline bind may end the rendering instance, and a query must not span that
		if (commandBuffer == null || unsupported || dev.emrion.shaders.mod.debug.TerrainStats.ENABLED || dev.emrion.shaders.mod.runtime.GeometryHooks.attachmentGroups
				|| "false".equals(System.getProperty("emrion.perf.prepassAdaptive"))) return true;
		if (decided) return decision; // a second opaque pass this frame: the same choice, no new measurement
		decided = true;
		decision = true;
		if (pool == 0 && !create()) return true;
		decision = on || ++sinceProbe >= PROBE;
		if (!decision) return false;
		sinceProbe = 0;
		cb = commandBuffer;
		written[slot] = true;
		vkCmdBeginQuery(cb, pool, slot * 2, 0);
		return true;
	}

	/** Between the prepass and the shading pass. */
	public static void main() {
		if (cb == null) return;
		vkCmdEndQuery(cb, pool, slot * 2);
		vkCmdBeginQuery(cb, pool, slot * 2 + 1, 0);
		shading = true;
	}

	/** After the shading pass (also when a draw threw: ends whichever query is open). */
	public static void end() {
		if (cb == null) return;
		vkCmdEndQuery(cb, pool, slot * 2 + (shading ? 1 : 0));
		cb = null;
		shading = false;
	}

	private static void read(int s) {
		var ctx = VulkanContext.get();
		long[] r = new long[4];
		int res = vkGetQueryPoolResults(ctx.vk, pool, s * 2, 2, r, 16, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
		VK12.vkResetQueryPool(ctx.vk, pool, s * 2, 2);
		if (res != VK_SUCCESS || r[1] == 0 || r[3] == 0) return;
		double overdraw = r[2] == 0 ? 0 : (double) r[0] / r[2];
		observe(overdraw);
		if (DEBUG) dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Terrain prepass: overdraw {} ({} / {} fragments) -> {}",
				String.format("%.3f", overdraw), r[0], r[2], on ? "on" : "off");
	}

	/** One measured frame: fragments with / without the prepass's help. Package-private for the test. */
	static boolean observe(double overdraw) {
		if (on) {
			low = overdraw < OFF ? low + 1 : 0;
			if (low >= LOW_FRAMES) on = false;
		} else if (overdraw >= ON) {
			on = true;
			low = 0;
		}
		return on;
	}

	private static boolean create() {
		var ctx = VulkanContext.get();
		if (!ctx.pipelineStatisticsQuery) {
			unsupported = true;
			return false;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_PIPELINE_STATISTICS).queryCount(LAG * 2)
					.pipelineStatistics(VK_QUERY_PIPELINE_STATISTIC_FRAGMENT_SHADER_INVOCATIONS_BIT);
			var p = stack.mallocLong(1);
			if (vkCreateQueryPool(ctx.vk, info, null, p) != VK_SUCCESS) {
				unsupported = true;
				return false;
			}
			pool = p.get(0);
			VK12.vkResetQueryPool(ctx.vk, pool, 0, LAG * 2);
		}
		return true;
	}
}
