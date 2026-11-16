package dev.emrion.shaders.mod.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.VulkanQueryPoolAccessor;
import dev.emrion.shaders.mod.vk.UboRing;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * GPU timestamps around every pass plus a render-thread CPU breakdown, for the F7 overlay, timing logs and the autotest.
 * Off unless one of those wants it; timestamps are read {@link #LAG} frames later without waiting.
 */
public final class Profiler {
	private static final boolean LOG = Boolean.getBoolean("emrion.debug.timings");
	private static final long LOG_INTERVAL_NS = Long.getLong("emrion.debug.timings.seconds", 5) * 1_000_000_000L;
	private static final int LAG = UboRing.REGIONS;
	private static final int MAX_MARKS = 96;

	/** CPU sections of the render thread, ns per frame. */
	public static final int UNIFORMS = 0, RAW = 1, SHADOW = 2, WAIT = 3, CPU_SECTIONS = 4;
	private static final String[] CPU_NAMES = {"uniforms + phase blocks", "raw pass recording", "shadow pass (MC draw recording)", "blocked on GPU (fence/acquire)"};

	/** Toggled by the overlay key; {@code -Demrion.debug.overlay=true} starts with it shown. */
	public static boolean visible = Boolean.getBoolean("emrion.debug.overlay");
	/** Set by the autotest benchmark: accumulate over its window. */
	public static boolean benchmark;

	private static GpuQueryPool pool;
	private static long vkPool;
	private static double nsPerTick;
	private static LongBuffer results;
	private static final String[][] labels = new String[LAG][MAX_MARKS];
	private static final int[] markCount = new int[LAG];
	private static int slot, marks;
	private static boolean frameActive;

	// accumulated since the last report: per mark index (GPU ns to the previous mark), CPU sections, frames
	private static final String[] accLabels = new String[MAX_MARKS];
	private static final double[] accGpu = new double[MAX_MARKS];
	private static int accMarks, accGpuFrames, accFrames;
	private static final long[] cpuFrame = new long[CPU_SECTIONS];
	private static final double[] accCpu = new double[CPU_SECTIONS];
	private static double accFrameNs, accGpuTotal;
	private static long lastFrameStart, lastReport = System.nanoTime(), lastLog = System.nanoTime();
	private static final List<String> shown = new ArrayList<>();

	private Profiler() {}

	public static boolean enabled() {
		return visible || LOG || benchmark;
	}

	/** Render-thread clock for {@link #cpuEnd}; 0 when off. */
	public static long cpuStart() {
		return enabled() ? System.nanoTime() : 0;
	}

	public static void cpuEnd(int section, long start) {
		if (start != 0) cpuFrame[section] += System.nanoTime() - start;
	}

	/** Raw recording time so far this frame (to make the shadow section exclusive of the raw passes it runs). */
	public static long rawSoFar() {
		return cpuFrame[RAW];
	}

	public static void shadowEnd(long start, long rawBefore) {
		if (start != 0) cpuFrame[SHADOW] += System.nanoTime() - start - (cpuFrame[RAW] - rawBefore);
	}

	// ---- frame boundaries (GameRenderer.render HEAD / TAIL) --------------------------------------------------------------

	public static void frameStart() {
		TerrainStats.frame();
		long now = System.nanoTime();
		if (frameActive) { // close the previous frame's CPU accounting
			accFrameNs += now - lastFrameStart;
			for (int i = 0; i < CPU_SECTIONS; i++) accCpu[i] += cpuFrame[i];
			accFrames++;
		}
		java.util.Arrays.fill(cpuFrame, 0);
		lastFrameStart = now;
		frameActive = enabled();
		if (!frameActive) {
			if (pool != null) close();
			return;
		}
		if (pool == null) open();
		slot = (slot + 1) % LAG;
		readBack(slot);
		marks = 0;
		mark("frame start");
		report(now);
	}

	public static void frameEnd() {
		if (!frameActive) return;
		mark("MC: after the level (GUI, overlays)");
		markCount[slot] = marks;
	}

	// ---- GPU marks -------------------------------------------------------------------------------------------------------

	/** Timestamp through MC's encoder (outside a render pass). {@code label} names the work since the previous mark. */
	public static void mark(String label) {
		if (!frameActive || marks >= MAX_MARKS) return;
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * MAX_MARKS + marks);
		labels[slot][marks++] = label;
	}

	/** Timestamp in one of our command buffers (outside a render pass). */
	public static void mark(VkCommandBuffer cb, String label) {
		if (!frameActive || marks >= MAX_MARKS) return;
		int query = slot * MAX_MARKS + marks;
		VK12.vkResetQueryPool(cb.getDevice(), vkPool, query, 1);
		KHRSynchronization2.vkCmdWriteTimestamp2KHR(cb, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, vkPool, query);
		labels[slot][marks++] = label;
	}

	private static void open() {
		pool = RenderSystem.getDevice().createTimestampQueryPool(MAX_MARKS * LAG);
		vkPool = ((VulkanQueryPoolAccessor) pool).emrion$vkQueryPool();
		nsPerTick = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		results = MemoryUtil.memAllocLong(2 * MAX_MARKS);
		java.util.Arrays.fill(markCount, 0);
		resetAcc();
	}

	private static void close() {
		pool.close();
		pool = null;
		MemoryUtil.memFree(results);
		shown.clear();
	}

	/** The marks written {@link #LAG} frames ago into {@code s}: adds their deltas to the accumulators. */
	private static void readBack(int s) {
		int n = markCount[s];
		markCount[s] = 0;
		if (n < 2) return;
		int r = VK10.vkGetQueryPoolResults(dev.emrion.shaders.mod.vk.VulkanContext.get().vk, vkPool, s * MAX_MARKS, n, results, 16,
				VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
		if (r != VK10.VK_SUCCESS && r != VK10.VK_NOT_READY) return;
		for (int i = 0; i < n; i++) if (results.get(2 * i + 1) == 0) return; // not available: skip this frame
		long t0 = results.get(0);
		for (int i = 1; i < n; i++) { // by label: a pass that runs only on some frames (e.g. BLAS builds) keeps the window going
			int k = 1;
			while (k < accMarks && !labels[s][i].equals(accLabels[k])) k++;
			if (k == accMarks) {
				if (accMarks == MAX_MARKS) continue;
				accLabels[accMarks] = labels[s][i];
				accGpu[accMarks++] = 0;
			}
			accGpu[k] += (results.get(2 * i) - results.get(2 * i - 2)) * nsPerTick;
		}
		accGpuTotal += (results.get(2 * (n - 1)) - t0) * nsPerTick;
		accGpuFrames++;
	}

	private static void resetAcc() {
		java.util.Arrays.fill(accGpu, 0);
		java.util.Arrays.fill(accCpu, 0);
		accGpuTotal = accFrameNs = 0;
		accGpuFrames = accFrames = 0;
		accMarks = 1;
	}

	// ---- output ----------------------------------------------------------------------------------------------------------

	private static void report(long now) {
		boolean logNow = LOG && now - lastLog >= LOG_INTERVAL_NS;
		if (!logNow && !(visible && now - lastReport >= 500_000_000L)) return;
		if (accGpuFrames == 0 || accFrames == 0) return;
		lastReport = now;
		if (visible) {
			shown.clear();
			shown.addAll(format(true));
		}
		if (logNow) {
			lastLog = now;
			EmrionShadersClient.LOG.info("Profile:\n  {}", String.join("\n  ", format(false)));
		}
		if (!benchmark) resetAcc();
	}

	/** The averaged report: CPU breakdown, then GPU ms per pass. */
	/** {@code compact} (overlay): passes under 0.02 ms and MC's gaps between our passes summed up, labels shortened. */
	public static List<String> format(boolean compact) {
		List<String> out = new ArrayList<>();
		if (accFrames == 0 || accGpuFrames == 0) return out;
		double frame = accFrameNs / accFrames / 1e6, gpu = accGpuTotal / accGpuFrames / 1e6;
		double ours = 0;
		for (int i = 0; i < CPU_SECTIONS; i++) ours += accCpu[i];
		int shown = dev.emrion.shaders.mod.framegen.Presenter.running() ? dev.emrion.shaders.mod.framegen.Presenter.presentedFps : 0;
		if (shown > 0) out.add(String.format("frame generation: %d FPS shown, %.0f rendered", shown, 1000 / frame));
		String reflex = dev.emrion.shaders.mod.framegen.Reflex.overlayLine();
		if (reflex != null) out.add(reflex);
		out.add(String.format("frame %.2f ms (%.0f FPS)  GPU %.2f ms  -> %s", frame, 1000 / frame, gpu,
				accCpu[WAIT] / accFrames / 1e6 > 0.5 ? "GPU-bound" : "CPU-bound"));
		out.add("CPU (render thread, ms/frame):");
		for (int i = 0; i < CPU_SECTIONS; i++) out.add(String.format("  %6.2f  %s", accCpu[i] / accFrames / 1e6, CPU_NAMES[i]));
		out.add(String.format("  %6.2f  MC (everything else)", frame - ours / accFrames / 1e6));
		out.add("GPU (ms/frame):");
		double small = 0, gaps = 0;
		for (int i = 1; i < accMarks; i++) {
			double ms = accGpu[i] / accGpuFrames / 1e6;
			String label = accLabels[i];
			if (compact && label.startsWith("MC (between")) gaps += ms;
			else if (compact && ms < 0.02) small += ms;
			else if (ms >= 0.005 || !label.startsWith("MC")) out.add(String.format("  %6.2f  %s", ms, compact && label.length() > 44 ? label.substring(0, 42) + ".." : label));
		}
		if (compact) out.add(String.format("  %6.2f  MC between our passes + %.2f in passes < 0.02 ms", gaps, small));
		return out;
	}

	/** Overlay lines (refreshed twice a second). */
	public static List<String> lines() {
		return shown;
	}

	/** Benchmark window: start accumulating without periodic resets / log the totals. */
	public static void benchmarkStart() {
		benchmark = true;
		resetAcc();
	}

	public static void benchmarkEnd() {
		EmrionShadersClient.LOG.info("Benchmark profile:\n  {}", String.join("\n  ", format(false)));
		benchmark = false;
	}
}
