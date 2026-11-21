package dev.emrion.shaders.mod.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.VulkanQueryPoolAccessor;
import dev.emrion.shaders.mod.vk.UboRing;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;
import dev.emrion.shaders.mod.platform.Platform;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * Always-on stutter log: a render frame longer than max(2 × median, median + 8 ms) and 25 ms gets one line in
 * {@code logs/emberlight-hitches.log} with what happened since the previous frame. Call sites only add counters.
 */
public final class HitchLog {
	public static final int GPU_WAIT = 0, ACQUIRE = 1, PRESENT = 2, FG_WAIT = 3, GC = 4, COMPILE = 5, LATE_PIPELINE = 6, PACK_SWAP = 7,
			TARGETS = 8, VRAM_RETRY = 9, BLAS = 10, BLAS_BATCH = 11, TLAS = 12, ENTITY_BLAS = 13, BUFFER = 14, CHUNKS = 15, UPSCALER = 16, RT_LIGHTS = 17, N = 18;
	private static final String[] NAMES = {"gpu-wait", "acquire", "present", "fg-wait", "GC", "pipeline compile", "late pipeline", "pack swap",
			"targets", "vram retry", "BLAS", "BLAS batches", "TLAS", "entity BLAS", "buffer alloc", "chunk upload", "upscaler", "RT lights"};
	private static final long MS = 1_000_000L;
	private static final int LAG = UboRing.REGIONS;

	/** {@code profiler.hitchLog} in config/emberlight.properties. */
	public static volatile boolean enabled = true;

	private static final AtomicLongArray NS = new AtomicLongArray(N), COUNT = new AtomicLongArray(N);
	private static final long[] frameNs = new long[N], frameCount = new long[N];
	private static final Window window = new Window(120);
	private static final List<GarbageCollectorMXBean> GCS = ManagementFactory.getGarbageCollectorMXBeans();
	private static long gcCount, gcMs, lastStart;
	private static boolean gcPolled;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

	// GPU frame time: timestamps at render HEAD and TAIL, read LAG frames later; a hitch line waits for its frame's result
	private static GpuQueryPool pool;
	private static long vkPool;
	private static double nsPerTick;
	private static LongBuffer results;
	private static int slot;
	private static final boolean[] written = new boolean[LAG];
	private static boolean skipTimestamps; // the slot's previous queries are still in flight: writing would reset them
	private static final String[] pending = new String[LAG];

	// rate limit and summary
	private static long second, minute;
	private static int linesThisSecond, hitches, suppressed;
	private static double worst;
	private static final int[] topCounts = new int[N];
	private static Writer out;

	private HitchLog() {}

	public static void add(int id, long startNs) {
		add(id, startNs, 1);
	}

	public static void add(int id, long startNs, long count) {
		if (!enabled) return;
		NS.addAndGet(id, System.nanoTime() - startNs);
		COUNT.addAndGet(id, count);
	}

	public static void count(int id, long count) {
		if (enabled) COUNT.addAndGet(id, count);
	}

	/** GameRenderer.render HEAD (render thread). */
	public static void frameStart() {
		if (!enabled) {
			if (pool != null) close();
			lastStart = 0;
			return;
		}
		long now = System.nanoTime();
		for (int i = 0; i < N; i++) {
			frameNs[i] = NS.getAndSet(i, 0);
			frameCount[i] = COUNT.getAndSet(i, 0);
		}
		pollGc();
		if (pool == null) open();
		int hitchSlot = slot;
		slot = (slot + 1) % LAG;
		skipTimestamps = !readBack(slot);
		if (lastStart != 0) {
			long frame = now - lastStart;
			if (frame > 25 * MS && window.full()) {
				long median = window.median();
				if (Window.isHitch(frame, median)) hitch(frame, median, hitchSlot);
			}
			window.add(frame);
		}
		lastStart = now;
		if (minute == 0) minute = now;
		else if (now - minute >= 60_000 * MS) summary(now);
		if (!skipTimestamps) RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * 2);
	}

	/** GameRenderer.render TAIL. */
	public static void frameEnd() {
		if (pool == null || skipTimestamps) return;
		RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * 2 + 1);
		written[slot] = true;
	}

	private static void pollGc() {
		long count = 0, ms = 0;
		for (GarbageCollectorMXBean b : GCS) {
			String n = b.getName();
			// ZGC "Cycles" and G1 "Concurrent GC" run beside the game: count them, but only pauses are time
			if (!n.contains("Pauses")) count += Math.max(0, b.getCollectionCount());
			if (!n.contains("Cycles") && !n.contains("Concurrent")) ms += Math.max(0, b.getCollectionTime());
		}
		if (gcPolled) {
			frameCount[GC] = count - gcCount;
			frameNs[GC] = (ms - gcMs) * MS;
		}
		gcCount = count;
		gcMs = ms;
		gcPolled = true;
	}

	private static void hitch(long frame, long median, int frameSlot) {
		long now = System.nanoTime();
		if (now - second >= 1000 * MS) {
			second = now;
			linesThisSecond = 0;
		}
		hitches++;
		worst = Math.max(worst, frame / 1e6);
		int[] top = top3();
		if (top[0] >= 0) topCounts[top[0]]++;
		if (++linesThisSecond > 5) {
			suppressed++;
			return;
		}
		StringBuilder b = new StringBuilder(160).append(LocalTime.now().format(TIME))
				.append(String.format(Locale.ROOT, " hitch %.1f ms (median %.1f):", frame / 1e6, median / 1e6));
		boolean gcShown = false;
		for (int i = 0; i < 3 && top[i] >= 0; i++) {
			b.append(i == 0 ? " " : ", ").append(entry(top[i]));
			gcShown |= top[i] == GC;
		}
		if (!gcShown) b.append(top[0] >= 0 ? ", " : " ").append(entry(GC));
		for (int i = 0; i < N; i++) // counters without time (batches, VRAM retries) and nothing else in the top 3
			if (frameCount[i] > 0 && frameNs[i] == 0 && i != GC) b.append(", ").append(NAMES[i]).append(' ').append(frameCount[i]);
		if (pool != null && written[frameSlot]) pending[frameSlot] = b.toString();
		else write(b.append(" | gpu ?").toString());
	}

	/** The three ids with the most time this frame (-1 when fewer). */
	private static int[] top3() {
		int[] top = {-1, -1, -1};
		for (int i = 0; i < N; i++) {
			if (frameNs[i] <= 0) continue;
			for (int k = 0; k < 3; k++) {
				if (top[k] < 0 || frameNs[i] > frameNs[top[k]]) {
					System.arraycopy(top, k, top, k + 1, 2 - k);
					top[k] = i;
					break;
				}
			}
		}
		return top;
	}

	private static String entry(int i) {
		String n = frameCount[i] > 1 ? NAMES[i] + " " + frameCount[i] + "x" : NAMES[i];
		return frameNs[i] > 0 ? String.format(Locale.ROOT, "%s %.1f ms", n, frameNs[i] / 1e6) : NAMES[i] + " " + frameCount[i];
	}

	private static void summary(long now) {
		if (hitches > 0) {
			int common = 0;
			for (int i = 1; i < N; i++) if (topCounts[i] > topCounts[common]) common = i;
			write(String.format(Locale.ROOT, "%s summary (60 s): %d hitches, worst %.1f ms, most often led by %s%s", LocalTime.now().format(TIME),
					hitches, worst, topCounts[common] > 0 ? NAMES[common] : "nothing measured", suppressed > 0 ? ", " + suppressed + " lines rate-limited" : ""));
		}
		minute = now;
		hitches = suppressed = 0;
		worst = 0;
		Arrays.fill(topCounts, 0);
	}

	private static void write(String line) {
		EmrionShadersClient.LOG.info("[hitch] {}", line);
		try {
			if (out == null) {
				Path p = Platform.get().gameDir().resolve("logs").resolve("emberlight-hitches.log");
				Files.createDirectories(p.getParent());
				out = Files.newBufferedWriter(p);
				out.write("# Emberlight hitch log, started " + LocalDateTime.now().withNano(0) + "\n");
			}
			out.write(line);
			out.write('\n');
			out.flush();
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("Could not write the hitch log, logging to latest.log only", e);
			out = Writer.nullWriter();
		}
	}

	// ---- GPU timestamps ----------------------------------------------------------------------------------------------------

	private static void open() {
		pool = RenderSystem.getDevice().createTimestampQueryPool(2 * LAG);
		vkPool = ((VulkanQueryPoolAccessor) pool).emrion$vkQueryPool();
		nsPerTick = RenderSystem.getDevice().getDeviceInfo().timestampPeriod();
		results = MemoryUtil.memAllocLong(4);
		Arrays.fill(written, false);
	}

	/** Client stopping: the query pool must go before MC destroys the device (frames of the exit save stay unlogged). */
	public static void shutdown() {
		enabled = false;
		if (pool != null) close();
	}

	private static void close() {
		for (int s = 0; s < LAG; s++) flush(s, "?");
		pool.close();
		pool = null;
		MemoryUtil.memFree(results);
	}

	/** The slot written {@link #LAG} frames ago: its GPU time completes a waiting hitch line. False while its queries are still in flight. */
	private static boolean readBack(int s) {
		if (!written[s]) {
			flush(s, "?");
			return true;
		}
		int r = VK10.vkGetQueryPoolResults(dev.emrion.shaders.mod.vk.VulkanContext.get().vk, vkPool, s * 2, 2, results, 16,
				VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
		boolean ok = (r == VK10.VK_SUCCESS || r == VK10.VK_NOT_READY) && results.get(1) != 0 && results.get(3) != 0;
		if (!ok && r == VK10.VK_NOT_READY) return false; // keep the slot and its line for the next round
		written[s] = false;
		flush(s, ok ? String.format(Locale.ROOT, "%.1f ms", (results.get(2) - results.get(0)) * nsPerTick / 1e6) : "?");
		return true;
	}

	private static void flush(int s, String gpu) {
		if (pending[s] != null) write(pending[s] + " | gpu " + gpu);
		pending[s] = null;
	}

	/** Rolling median of frame times; allocation-free. */
	static final class Window {
		private final long[] ring, sorted;
		private int n, pos;

		Window(int size) {
			ring = new long[size];
			sorted = new long[size];
		}

		boolean full() {
			return n == ring.length;
		}

		void add(long ns) {
			ring[pos] = ns;
			pos = (pos + 1) % ring.length;
			n = Math.min(n + 1, ring.length);
		}

		long median() {
			System.arraycopy(ring, 0, sorted, 0, n);
			Arrays.sort(sorted, 0, n);
			return sorted[n / 2];
		}

		static boolean isHitch(long frame, long median) {
			return frame > 25 * MS && frame > Math.max(2 * median, median + 8 * MS);
		}
	}
}
