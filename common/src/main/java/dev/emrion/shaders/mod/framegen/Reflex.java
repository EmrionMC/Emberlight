package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.NVLowLatency2.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.emrion.shaders.mod.EmrionShadersClient;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import org.lwjgl.vulkan.VkOutOfBandQueueTypeInfoNV;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;

/**
 * NVIDIA Reflex through the plain Vulkan extension VK_NV_low_latency2 (no NVIDIA SDK). The render thread sleeps at the
 * start of each frame until the driver says the GPU will be ready for it, so input is sampled as late as possible and no
 * frames queue up. Frame IDs ("presentID" of the markers) are counted per started frame. Stored as {@code reflex.mode}.
 * MC's submits use the implicit frame attribution (one frame = the submits before its present): chaining
 * VkLatencySubmissionPresentIdNV made driver 615.78 book each frame's GPU work one frame late.
 */
public final class Reflex {
	private Reflex() {}

	public enum Mode {
		OFF("Off"), ON("On"), BOOST("On + Boost");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight Reflex", Set.of("VK_NV_low_latency2", "VK_KHR_present_id"),
			Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class), "presentId")));

	public static volatile Mode mode = Mode.OFF;
	/** MC enabled the extension on the device (VulkanBackendRtMixin); false on AMD, Intel, old NVIDIA drivers. */
	public static volatile boolean supported;
	/** Autotest only ("reflex fgoff"): frame generation without Reflex, to measure what forcing it buys. */
	public static volatile boolean fgForces = true;

	private static volatile VkDevice device;
	private static volatile long swapchain;
	private static long semaphore, sleepValue;
	private static boolean sleepBroken;
	// applied sleep mode; -1 = re-apply
	private static int appliedMode = -1, appliedIntervalUs;
	private static volatile long frameId;
	private static boolean inFrame;
	private static long lastPresentId;
	private static final long[] simStart = new long[64];
	// latency stats: simulation start -> vkQueuePresentKHR of the frame returned (ours), simulation start -> GPU done (driver)
	private static double presentSum, gpuSum;
	private static long presentCount, gpuCount, lastTimingId, lastTimingRead;
	private static volatile double shownPresentMs, shownGpuMs;
	private static double winPresentSum;
	private static long winPresentCount;

	public static void load(Properties p) {
		String v = System.getProperty("emrion.reflex.mode", p.getProperty("reflex.mode", "off"));
		try {
			mode = Mode.valueOf(v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			mode = Mode.OFF;
		}
	}

	public static void store(Properties p) {
		p.setProperty("reflex.mode", mode.name().toLowerCase(Locale.ROOT));
	}

	/** True when frame generation runs: then Reflex runs too, at least On (as in every game with frame generation). */
	public static boolean forcedByFrameGen() {
		return FrameGenConfig.effective() != FrameGenConfig.Mode.OFF;
	}

	/** The driver rejected Reflex this session: it stays off. */
	public static boolean broken() {
		return supported && sleepBroken;
	}

	public static Mode effective() {
		if (!supported || sleepBroken) return Mode.OFF;
		return mode == Mode.OFF && fgForces && forcedByFrameGen() ? Mode.ON : mode;
	}

	// ---- swapchain (VulkanGpuSurface.configure / close) ----------------------------------------------------------------

	/** Chains VkSwapchainLatencyCreateInfoNV (allocated on the caller's stack frame) into MC's swapchain create info. */
	public static void chainSwapchain(VkSwapchainCreateInfoKHR info) {
		if (!supported) return;
		var latency = VkSwapchainLatencyCreateInfoNV.calloc(MemoryStack.stackGet()).sType$Default().latencyModeEnable(true).pNext(info.pNext());
		info.pNext(latency.address());
	}

	public static void swapchainCreated(VkDevice dev, long handle) {
		if (!supported || shutDown) return; // MC still renders while it saves; the semaphore is gone
		device = dev;
		if (semaphore == 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
				var lp = stack.mallocLong(1);
				if (vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type), null, lp) != VK_SUCCESS) {
					supported = false;
					return;
				}
				semaphore = lp.get(0);
			}
		}
		appliedMode = -1;
		lastPresentId = 0;
		swapchain = handle;
	}

	public static void swapchainGone() {
		swapchain = 0;
	}

	// ---- render thread, per frame ------------------------------------------------------------------------------------

	/** Minecraft.run, before input is polled: the Reflex sleep, then the frame's first markers. */
	public static void beginFrame(int framerateLimit) {
		long sc = swapchain;
		if (sc == 0) return;
		Mode m = effective();
		// the frame cap moves into the driver's sleep (it then waits before input, not after present); not with frame
		// generation, whose extra presents the interval would count
		int interval = m != Mode.OFF && framerateLimit > 0 && framerateLimit < 260 && !forcedByFrameGen() ? 1_000_000 / framerateLimit : 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (m.ordinal() != appliedMode || interval != appliedIntervalUs) {
				int r = vkSetLatencySleepModeNV(device, sc, VkLatencySleepModeInfoNV.calloc(stack).sType$Default()
						.lowLatencyMode(m != Mode.OFF).lowLatencyBoost(m == Mode.BOOST).minimumIntervalUs(interval));
				if (r != VK_SUCCESS) {
					// not applied: keep MC's own limiter (capsFrameRate false) and stop trying for this session
					sleepBroken = true;
					appliedIntervalUs = 0;
					EmrionShadersClient.LOG.warn("Reflex: vkSetLatencySleepModeNV failed ({}); Reflex disabled for this session", r);
					return;
				}
				if (appliedMode != m.ordinal()) EmrionShadersClient.LOG.info("Reflex: {}", m.label);
				appliedMode = m.ordinal();
				appliedIntervalUs = interval;
			}
			if (m != Mode.OFF) {
				long t = System.nanoTime();
				sleep(stack, sc);
				sleepSum += (System.nanoTime() - t) / 1e6;
				sleepCount++;
			}
		}
		long id = ++frameId;
		simStart[(int) (id & 63)] = System.nanoTime();
		inFrame = true;
		marker(sc, id, VK_LATENCY_MARKER_SIMULATION_START_NV);
		marker(sc, id, VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
		if (simStart[(int) (id & 63)] - lastTimingRead > 250_000_000L) readTimings(sc);
	}

	private static void sleep(MemoryStack stack, long sc) {
		long value = ++sleepValue;
		int r = vkLatencySleepNV(device, sc, VkLatencySleepInfoNV.calloc(stack).sType$Default().signalSemaphore(semaphore).value(value));
		if (r != VK_SUCCESS) { // like the timeout below; also hands frame pacing back to MC's limiter right away
			sleepBroken = true;
			appliedIntervalUs = 0;
			EmrionShadersClient.LOG.warn("Reflex: latency sleep failed ({}); Reflex disabled for this session", r);
			return;
		}
		var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1).pSemaphores(stack.longs(semaphore)).pValues(stack.longs(value));
		// the driver signals within a frame; a missing signal (a layer that drops the call) must not stall the game
		if (vkWaitSemaphores(device, wait, 200_000_000L) == VK_TIMEOUT) {
			sleepBroken = true;
			appliedIntervalUs = 0;
			EmrionShadersClient.LOG.warn("Reflex: the driver did not end the latency sleep within 200 ms; Reflex disabled for this session");
		}
	}

	/** Minecraft.renderFrame, before GameRenderer.render: simulation (tick, extract) is done, Vulkan recording starts. */
	public static void beforeRender() {
		long sc = swapchain;
		if (sc == 0 || !inFrame) return;
		marker(sc, frameId, VK_LATENCY_MARKER_SIMULATION_END_NV);
		marker(sc, frameId, VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	/** Minecraft.renderFrame, before GpuSurface.present (MC's submit is done). */
	public static void beforePresent() {
		long sc = swapchain;
		if (sc == 0 || !inFrame) return;
		marker(sc, frameId, VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
		marker(sc, frameId, VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	/** Minecraft.renderFrame, after GpuSurface.present: with frame generation the real present follows on the presenter thread. */
	public static void afterPresent() {
		long sc = swapchain;
		if (sc == 0 || !inFrame) return;
		marker(sc, frameId, VK_LATENCY_MARKER_PRESENT_END_NV);
		inFrame = false;
	}

	/** The frame being built (for the FG presenter), or 0 when no Reflex frame is open. */
	static long currentFrame() {
		return swapchain != 0 && inFrame ? frameId : 0;
	}

	// Measured latency: a thread waits for MC's frame submit on its timeline semaphore and takes the time from simulation start.
	// It needs no driver attribution, so it checks the driver's report (sim->GPU done) independently.
	private static final java.util.concurrent.ArrayBlockingQueue<long[]> gpuWaits = new java.util.concurrent.ArrayBlockingQueue<>(16);
	private static Thread gpuWaiter;
	private static double measuredSum, sleepSum;
	private static long measuredCount, sleepCount;

	/** MC's frame submit (VulkanCommandEncoder.submit) will signal {@code value} on {@code semaphore}. */
	public static void submitted(long semaphore, long value) {
		if (swapchain == 0 || !inFrame || shutDown) return;
		if (gpuWaiter == null) {
			gpuWaiter = new Thread(Reflex::measureLoop, "Emberlight latency probe");
			gpuWaiter.setDaemon(true);
			gpuWaiter.start();
		}
		gpuWaits.offer(new long[] {semaphore, value, simStart[(int) (frameId & 63)]});
	}

	private static void measureLoop() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var sem = stack.longs(0);
			var val = stack.longs(0);
			var wait = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1).pSemaphores(sem).pValues(val);
			while (true) {
				long[] w = gpuWaits.take();
				sem.put(0, w[0]);
				val.put(0, w[1]);
				if (vkWaitSemaphores(device, wait, 1_000_000_000L) != VK_SUCCESS) continue;
				double ms = (System.nanoTime() - w[2]) / 1e6;
				synchronized (Reflex.class) {
					measuredSum += ms;
					measuredCount++;
				}
			}
		} catch (InterruptedException e) {
			// shutdown(): ends before MC destroys its semaphores and the device
		}
	}

	private static volatile boolean shutDown;

	/** Client stopping, before MC destroys the device: stop the probe (it waits on MC's semaphores) and free our semaphore. */
	public static void shutdown() {
		shutDown = true;
		swapchain = 0;
		Thread t = gpuWaiter;
		if (t != null) {
			t.interrupt();
			try {
				t.join(2000); // at most one 1 s semaphore wait in flight
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		gpuWaits.clear();
		if (semaphore != 0 && device != null) {
			vkDestroySemaphore(device, semaphore, null);
			semaphore = 0;
		}
	}

	/** Chains VkPresentIdKHR for the real present of frame {@code id} (0 = none); IDs only ever grow per swapchain. */
	static synchronized void chainPresent(VkPresentInfoKHR info, long id, MemoryStack stack) {
		if (id <= lastPresentId || swapchain == 0) return;
		lastPresentId = id;
		info.pNext(VkPresentIdKHR.calloc(stack).sType$Default().swapchainCount(1).pPresentIds(stack.longs(id)).pNext(info.pNext()).address());
	}

	/** MC's own present of the current frame (no frame generation). */
	public static void chainPresent(VkPresentInfoKHR info) {
		if (inFrame) chainPresent(info, frameId, MemoryStack.stackGet());
	}

	public static void presentedCurrent() {
		if (inFrame) presented(frameId);
	}

	/** The real image of frame {@code id} was handed to the swapchain. */
	static synchronized void presented(long id) {
		if (id == 0) return;
		double ms = (System.nanoTime() - simStart[(int) (id & 63)]) / 1e6;
		if (ms <= 0 || ms > 1000) return;
		presentSum += ms;
		presentCount++;
		winPresentSum += ms;
		winPresentCount++;
	}

	/** Out-of-band present markers around each presenter-thread present (spec: frame generation implementations). */
	static void oobMarker(long sc, long id, boolean start) {
		if (id != 0 && sc != 0 && supported) marker(sc, id, start ? VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV : VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV);
	}

	/** The presenter's own queue only copies and presents generated frames: keep it out of the latency evaluation. */
	static void outOfBandQueue(VkQueue queue) {
		if (!supported) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkQueueNotifyOutOfBandNV(queue, VkOutOfBandQueueTypeInfoNV.calloc(stack).sType$Default().queueType(VK_OUT_OF_BAND_QUEUE_TYPE_PRESENT_NV));
		}
	}

	private static void marker(long sc, long id, int marker) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkSetLatencyMarkerNV(device, sc, VkSetLatencyMarkerInfoNV.calloc(stack).sType$Default().presentID(id).marker(marker));
		}
	}

	private static void readTimings(long sc) {
		lastTimingRead = System.nanoTime();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var reports = VkLatencyTimingsFrameReportNV.calloc(64, stack);
			for (var r : reports) r.sType$Default();
			var info = VkGetLatencyMarkerInfoNV.calloc(stack).sType$Default().timingCount(64).pTimings(reports);
			vkGetLatencyTimingsNV(device, sc, info);
			double winGpu = 0;
			int n = 0;
			for (int i = 0; i < info.timingCount(); i++) {
				var r = reports.get(i);
				if (r.presentID() <= lastTimingId || r.simStartTimeUs() == 0 || r.gpuRenderEndTimeUs() <= r.simStartTimeUs()) continue;
				lastTimingId = r.presentID();
				double ms = (r.gpuRenderEndTimeUs() - r.simStartTimeUs()) / 1e3;
				if (ms > 1000) continue;
				winGpu += ms;
				n++;
			}
			synchronized (Reflex.class) {
				gpuSum += winGpu;
				gpuCount += n;
				shownGpuMs = n > 0 ? winGpu / n : 0;
				shownPresentMs = winPresentCount > 0 ? winPresentSum / winPresentCount : 0;
				winPresentSum = 0;
				winPresentCount = 0;
			}
		}
	}

	/** One overlay line, or null when the extension is missing. */
	public static String overlayLine() {
		if (!supported) return null;
		return String.format(Locale.ROOT, "Reflex %s: sim->present %.1f ms, sim->GPU done %.1f ms (driver)", effective().label, shownPresentMs, shownGpuMs);
	}

	/** Bench window (autotest): restart the averages / log them. */
	public static synchronized void statsReset() {
		presentSum = gpuSum = 0;
		presentCount = gpuCount = measuredCount = sleepCount = 0;
		measuredSum = sleepSum = 0;
	}

	public static synchronized void statsLog(String name) {
		if (!supported) return;
		EmrionShadersClient.LOG.info("Bench {}: Reflex {} sim->present {} ms ({} frames), sim->GPU done {} ms ({} driver reports), measured {} ms ({} frames), sleep {} ms",
				name, effective().label, String.format(Locale.ROOT, "%.2f", presentCount > 0 ? presentSum / presentCount : 0), presentCount,
				String.format(Locale.ROOT, "%.2f", gpuCount > 0 ? gpuSum / gpuCount : 0), gpuCount, String.format(Locale.ROOT, "%.2f", measuredCount > 0 ? measuredSum / measuredCount : 0),
				measuredCount, String.format(Locale.ROOT, "%.2f", sleepCount > 0 ? sleepSum / sleepCount : 0));
	}

	/** True while the driver's sleep enforces MC's frame cap (then MC's own limiter must not wait as well). */
	public static boolean capsFrameRate() {
		return swapchain != 0 && appliedIntervalUs > 0;
	}
}
