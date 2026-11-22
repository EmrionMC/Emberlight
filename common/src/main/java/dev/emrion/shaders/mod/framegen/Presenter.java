package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.autotest.FrameTimeLogger;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSubmitInfo2;

/**
 * Frame-generation presenter thread: presents the interpolated image, then the real one half a frame later (AMD pacing).
 * All swapchain calls (and, without our own queue, all queue calls) go through {@link #LOCK}.
 */
public final class Presenter {
	private Presenter() {}

	/** Vulkan's external synchronisation of the swapchain (acquire/present) and, in shared-queue mode, of the graphics queue. */
	public static final ReentrantLock LOCK = new ReentrantLock();
	/** Presents per second as frame times, recorded on the presenter thread (the benchmark reads it after stopping). */
	public static final FrameTimeLogger PRESENTS = new FrameTimeLogger(1 << 17);
	/** Our own queue in MC's graphics family (VulkanBackendQueueMixin), or -1: then the presenter shares MC's queue. */
	public static volatile int queueFamily = -1, queueIndex = -1;

	/** One rendered frame: MC's swapchain image to present after the interpolated image ({@code interp} may be null). */
	record Frame(long swapchain, long[] images, int width, int height, int imageIndex, long presentSemaphore, @Nullable RawImage interp,
			long timelineValue, long reflexId) {}

	/** Interpolation slots = most MC frames handed over but not yet presented (back-pressure in {@link #submit}). */
	public static final int SLOTS = 4;
	private static final LinkedBlockingDeque<Frame> queue = new LinkedBlockingDeque<>();
	private static @Nullable Thread thread;
	private static volatile boolean stop;
	/** Set when the presenter did not finish in time: frame generation stays off, its objects are leaked, not freed in use. */
	private static volatile boolean wedged;
	/** Frames handed over and not yet fully presented (incremented before the enqueue, so drain never misses a dequeued frame). */
	private static final AtomicInteger outstanding = new AtomicInteger();
	private static volatile long copiedUpTo;
	/** The interpolated frame number whose slot is no longer read once this presenter's {@link #copied} reaches it; 0 before. */
	private static volatile long firstValue;
	private static final AtomicInteger lastResult = new AtomicInteger(VK_SUCCESS);
	private static VkQueue vkQueue;
	private static boolean ownQueue;
	/** {@code copied}: timeline the presenter signals (value = frame number) once the GPU read that frame's interpolation slot. */
	private static long timeline, copied, pool;
	private static final VkCommandBuffer[] cbs = new VkCommandBuffer[SLOTS];
	private static final long[] fences = new long[SLOTS], acquireSemaphores = new long[SLOTS];
	private static long[] presentSemaphores = new long[0];
	private static long semaphoreSwapchain;
	private static int slot;
	private static long copySignaled; // presenter thread: last value signalled on copied
	private static boolean warnedAcquire;
	// pacing state (presenter thread only)
	private static final Pacing pacing = new Pacing();
	private static long lastPresent;
	private static long spinNanos = 200_000;
	public static volatile long presents, interpolatedPresents, skipped;
	/** Presents in the last full second (0 while stopped): what the screen gets, as opposed to MC's rendered FPS. */
	public static volatile int presentedFps;
	private static long fpsStart, fpsPresents;

	public static boolean running() {
		return thread != null;
	}

	/** True when the presenter submits on MC's queue: then MC's queue calls take {@link #LOCK} too. */
	public static boolean sharedQueue() {
		return thread != null && !ownQueue;
	}

	/** The timeline semaphore the frame's submission signals (value = frame number) once the frame is done on the GPU. */
	static long timeline() {
		return timeline;
	}

	/** True when the presenter got stuck and was abandoned: frame generation stays off until restart. */
	static boolean wedged() {
		return wedged;
	}

	/** {@code lastValue}: the frame number before the first one this presenter gets. */
	static void start(VulkanContext ctx, long lastValue) {
		if (thread != null || wedged) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ownQueue = queueFamily >= 0;
			if (ownQueue) {
				PointerBuffer p = stack.mallocPointer(1);
				vkGetDeviceQueue(ctx.vk, queueFamily, queueIndex, p);
				vkQueue = new VkQueue(p.get(0), ctx.vk);
				Reflex.outOfBandQueue(vkQueue);
			} else {
				vkQueue = ctx.graphicsQueue.vkQueue();
			}
			var lp = stack.mallocLong(1);
			var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
			check(vkCreateSemaphore(ctx.vk, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type), null, lp), "timeline semaphore");
			timeline = lp.get(0);
			check(vkCreateSemaphore(ctx.vk, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type), null, lp), "copy timeline semaphore");
			copied = lp.get(0);
			int family = ownQueue ? queueFamily : ctx.graphicsQueue.queueFamilyIndex();
			check(vkCreateCommandPool(ctx.vk, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lp), "command pool");
			pool = lp.get(0);
			PointerBuffer pb = stack.mallocPointer(SLOTS);
			check(vkAllocateCommandBuffers(ctx.vk, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
					.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS), pb), "command buffers");
			for (int i = 0; i < SLOTS; i++) {
				cbs[i] = new VkCommandBuffer(pb.get(i), ctx.vk);
				check(vkCreateFence(ctx.vk, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, lp), "fence");
				fences[i] = lp.get(0);
				check(vkCreateSemaphore(ctx.vk, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp), "semaphore");
				acquireSemaphores[i] = lp.get(0);
			}
		}
		stop = false;
		copiedUpTo = 0;
		copySignaled = 0;
		firstValue = lastValue + 1;
		outstanding.set(0);
		pacing.reset();
		lastPresent = 0;
		Thread t = new Thread(() -> loop(ctx), "Emberlight FG presenter");
		t.setDaemon(true);
		// one step above normal: MAX could starve audio and voice apps on a busy CPU (Windows only, Linux ignores Java priorities)
		t.setPriority(Thread.NORM_PRIORITY + 1);
		thread = t;
		t.start();
		EmrionShadersClient.LOG.info("Frame generation presenter started ({} queue)", ownQueue ? "own present" : "shared graphics");
	}

	/** Present hook: waits until frame N-2's copy is submitted, so the interpolation slot frame N+1 writes is free. */
	static void submit(Frame f) {
		long start = System.nanoTime(), deadline = start + 2_000_000_000L;
		// shortcut: after 2 s the frame is queued anyway; slots stay safe (copyDone) and MC holds every queued image, so the
		// queue cannot outgrow the swapchain (MC's acquire throws on its 5 s timeout)
		while (copiedUpTo < f.timelineValue() - (SLOTS - 1) && thread != null && System.nanoTime() < deadline) LockSupport.parkNanos(50_000);
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.FG_WAIT, start);
		outstanding.incrementAndGet();
		queue.addLast(f);
	}

	/** The worst present/acquire result since the last call (the surface turns it into MC's own flags). */
	static int takeResult() {
		return lastResult.getAndSet(VK_SUCCESS);
	}

	/**
	 * Waits (up to 1 s) until the GPU no longer reads the interpolation slot frame {@code value} wrote: its copy finished or
	 * was skipped. False on timeout: the slot may still be read, the caller must not overwrite it.
	 */
	static boolean copyDone(VulkanContext ctx, long value) {
		if (thread == null || value < firstValue) return true; // no presenter, or the slot was written for a stopped one
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1).pSemaphores(stack.longs(copied)).pValues(stack.longs(value));
			return vkWaitSemaphores(ctx.vk, info, 1_000_000_000L) == VK_SUCCESS;
		}
	}

	/** Waits until every queued frame is presented; false (and frame generation abandoned) when that took over 3 s. */
	public static boolean drain() {
		if (wedged) return false;
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (thread != null && outstanding.get() > 0) {
			if (System.nanoTime() > deadline) {
				abandon(outstanding.get() + " frames still queued after 3 s");
				return false;
			}
			LockSupport.parkNanos(100_000);
		}
		return true;
	}

	private static void abandon(String why) {
		if (!wedged) EmrionShadersClient.LOG.error("Frame generation presenter stuck ({}): frame generation off until restart, its Vulkan objects are leaked", why);
		wedged = true;
		// under the lock: a swapchain call in progress finishes first, every later one sees stop (the caller destroys the swapchain next)
		boolean locked = false;
		try {
			locked = LOCK.tryLock(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		stop = true;
		if (locked) LOCK.unlock();
	}

	/** Before MC destroys the swapchain: it idles only its own queue, but our presents still wait on its semaphores. */
	public static void waitIdle() {
		if (thread == null || !ownQueue) return;
		LOCK.lock();
		try {
			vkQueueWaitIdle(vkQueue);
		} finally {
			LOCK.unlock();
		}
	}

	/** Drains, stops the thread and frees its objects (render thread). */
	public static void stop(VulkanContext ctx) {
		Thread t = thread;
		if (t == null) return;
		boolean drained = drain();
		stop = true;
		t.interrupt();
		try {
			t.join(3000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		thread = null;
		presentedFps = 0;
		fpsStart = 0;
		if (!drained || t.isAlive()) {
			abandon("thread did not exit");
			return; // leak: the thread may still use the queue, command buffers and semaphores
		}
		queue.clear();
		int idle;
		LOCK.lock();
		try {
			idle = vkQueueWaitIdle(vkQueue);
		} finally {
			LOCK.unlock();
		}
		FrameCapture.flush(ctx);
		if (idle != VK_SUCCESS) {
			abandon("queue wait idle " + idle);
			return;
		}
		for (int i = 0; i < SLOTS; i++) {
			vkDestroyFence(ctx.vk, fences[i], null);
			vkDestroySemaphore(ctx.vk, acquireSemaphores[i], null);
		}
		for (long s : presentSemaphores) vkDestroySemaphore(ctx.vk, s, null);
		presentSemaphores = new long[0];
		semaphoreSwapchain = 0;
		vkDestroyCommandPool(ctx.vk, pool, null);
		// the timeline semaphore stays alive until MC's in-flight submissions that signal it are done
		long tl = timeline;
		long cp = copied;
		ctx.destroyLater(() -> {
			vkDestroySemaphore(ctx.vk, tl, null);
			vkDestroySemaphore(ctx.vk, cp, null);
		});
		EmrionShadersClient.LOG.info("Frame generation presenter stopped ({} presents, {} interpolated, {} interpolated frames skipped)", presents, interpolatedPresents, skipped);
	}

	private static void loop(VulkanContext ctx) {
		while (!stop) {
			Frame f;
			try {
				f = queue.pollFirst(100, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				continue;
			}
			if (f == null) continue;
			try {
				present(ctx, f);
			} catch (Throwable t) {
				EmrionShadersClient.LOG.error("Frame generation presenter failed", t);
				note(VK_ERROR_OUT_OF_DATE_KHR); // makes MC reconfigure; the next frames start over
				try {
					if (f.interp() != null && copySignaled < f.timelineValue()) signalCopied(f.timelineValue());
				} catch (RuntimeException e) {
					EmrionShadersClient.LOG.error("Frame generation presenter: cannot release the interpolation slot", e);
				}
			} finally {
				copiedUpTo = Math.max(copiedUpTo, f.timelineValue());
				outstanding.decrementAndGet();
			}
		}
	}

	private static void present(VulkanContext ctx, Frame f) {
		if (f.swapchain() != semaphoreSwapchain || f.images().length != presentSemaphores.length) newSwapchain(ctx, f); // handles get reused
		long delta = 0;
		if (f.interp() != null && !waitTimeline(ctx, f.timelineValue())) {
			// the real frame is not done after 5 s (or we are stopping): a copy would only wait for it on the GPU
			skipped++;
			signalCopied(f.timelineValue());
			copiedUpTo = f.timelineValue();
		} else if (f.interp() != null) {
			delta = pacing.update(System.nanoTime());
			if (behind(ctx)) delta = 0;
			int s = slot;
			slot = (slot + 1) % SLOTS;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				// the copy of 4 interpolated frames ago must be done before its command buffer and fence are reused
				int fr = vkWaitForFences(ctx.vk, stack.longs(fences[s]), true, 1_000_000_000L);
				var index = stack.mallocInt(1);
				long t0 = System.nanoTime();
				int r = fr != VK_SUCCESS ? fr : acquire(ctx.vk, f.swapchain(), acquireSemaphores[s], index);
				if ((r == VK_NOT_READY || r == VK_TIMEOUT) && !warnedAcquire) {
					warnedAcquire = true;
					EmrionShadersClient.LOG.warn("Frame generation: no swapchain image for an interpolated frame after {} ms ({} frames queued, {} images); skipped",
							(System.nanoTime() - t0) / 1_000_000, queue.size(), f.images().length);
				}
				if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) {
					vkResetFences(ctx.vk, stack.longs(fences[s]));
					int i = index.get(0);
					recordCopy(cbs[s], f.interp(), f.images()[i], f.width(), f.height());
					submitCopy(stack, cbs[s], acquireSemaphores[s], f.timelineValue(), presentSemaphores[i], fences[s]);
					copySignaled = f.timelineValue();
					waitUntil(lastPresent + delta);
					note(queuePresent(f, i, presentSemaphores[i], false));
					interpolatedPresents++;
					stamp();
				} else {
					if (fr != VK_SUCCESS) EmrionShadersClient.LOG.warn("Frame generation: copy fence wait returned {}; interpolated frame skipped", fr);
					note(r);
					skipped++;
					signalCopied(f.timelineValue()); // nothing reads this slot
				}
			}
			// only now: the render thread then acquires one more image, and we must not hold one ourselves (FrameGenSurfaceMixin count)
			copiedUpTo = f.timelineValue();
			waitUntil(lastPresent + (behind(ctx) ? 0 : delta));
		}
		note(queuePresent(f, f.imageIndex(), f.presentSemaphore(), true));
		Reflex.presented(f.reflexId());
		stamp();
	}

	/** GPU already finished the next frame: skip pacing, else back-pressure ratchets the frame rate down. */
	private static boolean behind(VulkanContext ctx) {
		Frame next = queue.peekFirst();
		if (next == null) return false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var v = stack.mallocLong(1);
			return vkGetSemaphoreCounterValue(ctx.vk, timeline, v) == VK_SUCCESS && v.get(0) >= next.timelineValue();
		}
	}

	private static void stamp() {
		lastPresent = System.nanoTime();
		presents++;
		PRESENTS.frame();
		if (lastPresent - fpsStart >= 1_000_000_000L) {
			if (fpsStart != 0) presentedFps = (int) Math.round((presents - fpsPresents) * 1e9 / (lastPresent - fpsStart));
			fpsStart = lastPresent;
			fpsPresents = presents;
		}
	}

	/** "120 FPS (60 rendered)" while frame generation presents, else MC's "60 FPS". */
	public static String fpsLabel(int rendered) {
		int shown = thread != null ? presentedFps : 0;
		if (FrameGen.bypassed()) return rendered + " FPS (frame generation paused: at refresh rate)";
		return shown > 0 ? shown + " FPS (" + rendered + " rendered)" : rendered + " FPS";
	}

	/** Keeps the most severe result (OUT_OF_DATE over SUBOPTIMAL over SUCCESS) until {@link #takeResult}. */
	private static void note(int r) {
		if (r == VK_ERROR_OUT_OF_DATE_KHR) lastResult.set(r);
		else if (r == VK_SUBOPTIMAL_KHR) lastResult.compareAndSet(VK_SUCCESS, r);
		else if (r < 0) EmrionShadersClient.LOG.warn("Frame generation present/acquire failed: {}", r);
	}

	/** Advances {@link #copied} in queue order without a copy (an empty submit: a host signal could undercut a pending one). */
	private static void signalCopied(long value) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var signal = VkSemaphoreSubmitInfo.calloc(1, stack);
			signal.get(0).sType$Default().semaphore(copied).value(value).stageMask(org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
			var submit = VkSubmitInfo2.calloc(1, stack).sType$Default().pSignalSemaphoreInfos(signal);
			LOCK.lock();
			try {
				if (stop) return; // abandoned: MC no longer takes LOCK, so the queue is not ours to submit to
				check(KHRSynchronization2.vkQueueSubmit2KHR(vkQueue, submit, VK_NULL_HANDLE), "frame generation copy signal");
			} finally {
				LOCK.unlock();
			}
			copySignaled = value;
		}
	}

	private static void newSwapchain(VulkanContext ctx, Frame f) {
		// the old swapchain is gone (MC waited for its queue to idle after our drain): its semaphores are free again
		for (long s : presentSemaphores) vkDestroySemaphore(ctx.vk, s, null);
		presentSemaphores = new long[f.images().length];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var lp = stack.mallocLong(1);
			for (int i = 0; i < presentSemaphores.length; i++) {
				check(vkCreateSemaphore(ctx.vk, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp), "present semaphore");
				presentSemaphores[i] = lp.get(0);
			}
		}
		semaphoreSwapchain = f.swapchain();
		pacing.reset();
	}

	private static boolean waitTimeline(VulkanContext ctx, long value) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkSemaphoreWaitInfo.calloc(stack).sType$Default().semaphoreCount(1).pSemaphores(stack.longs(timeline)).pValues(stack.longs(value));
			for (int i = 0; i < 50 && !stop; i++) {
				int r = vkWaitSemaphores(ctx.vk, info, 100_000_000L);
				if (r == VK_SUCCESS) return true;
				// device lost etc.: the real frame's semaphore may never signal either, so no present; loop()'s catch
				// releases the slot and reports OUT_OF_DATE, which makes MC recreate the swapchain
				if (r != VK_TIMEOUT) throw new IllegalStateException("frame generation timeline wait failed: " + r);
			}
		}
		return false; // timeout on a frame MC already submitted, or stopping
	}

	/** vkAcquireNextImageKHR without ever blocking inside the lock (the other side may need it to free an image). */
	static int acquire(org.lwjgl.vulkan.VkDevice device, long swapchain, long semaphore, java.nio.IntBuffer index) {
		// with VSync (FIFO) this waits for the next vblank to free an image; after 50 ms (1.5 vblanks at 30 Hz) the interpolated
		// frame is skipped: the real frame waits behind it, and on a swapchain with too few images every frame would stall
		return pollAcquire(() -> stop ? VK_ERROR_OUT_OF_DATE_KHR : vkAcquireNextImageKHR(device, swapchain, 0L, semaphore, VK_NULL_HANDLE, index), 50_000_000L);
	}

	/** Polls {@code acquire} (one try with timeout 0) under the lock until it is no longer NOT_READY/TIMEOUT, for up to 5 s. */
	public static int pollAcquire(IntSupplier acquire) {
		return pollAcquire(acquire, 5_000_000_000L);
	}

	static int pollAcquire(IntSupplier acquire, long timeoutNanos) {
		long deadline = System.nanoTime() + timeoutNanos;
		while (true) {
			int r;
			LOCK.lock();
			try {
				r = acquire.getAsInt();
			} finally {
				LOCK.unlock();
			}
			if (r != VK_NOT_READY && r != VK_TIMEOUT || System.nanoTime() > deadline) return r;
			LockSupport.parkNanos(100_000);
		}
	}

	/** {@code real}: the rendered frame (carries its Reflex present ID); both get Reflex's out-of-band present markers. */
	private static int queuePresent(Frame f, int index, long waitSemaphore, boolean real) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(waitSemaphore)).swapchainCount(1)
					.pSwapchains(stack.longs(f.swapchain())).pImageIndices(stack.ints(index));
			if (real) Reflex.chainPresent(info, f.reflexId(), stack);
			LOCK.lock();
			try {
				if (stop) return VK_ERROR_OUT_OF_DATE_KHR; // abandoned: the swapchain may be destroyed (the markers use it too)
				Reflex.oobMarker(f.swapchain(), f.reflexId(), true);
				int r = vkQueuePresentKHR(vkQueue, info);
				Reflex.oobMarker(f.swapchain(), f.reflexId(), false);
				return r;
			} finally {
				LOCK.unlock();
			}
		}
	}

	/** Blit like MC's swapchain blit (VulkanGpuSurface.blitFromTexture): Y flip, format conversion to the swapchain's. */
	private static void recordCopy(VkCommandBuffer cb, RawImage src, long dst, int dstW, int dstH) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkResetCommandBuffer(cb, 0);
			vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
			// stages as MC's blit: chained to the acquire wait (BLIT) and done before the present semaphore signal
			barrier(cb, stack, dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BLIT_BIT, 0,
					org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BLIT_BIT, org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
			int w = Math.min(dstW, src.width), h = Math.min(dstH, src.height);
			var region = VkImageBlit.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.srcOffsets(0).set(0, 0, 0);
			region.srcOffsets(1).set(w, h, 1);
			region.dstOffsets(0).set(0, h, 0);
			region.dstOffsets(1).set(w, 0, 1);
			vkCmdBlitImage(cb, src.image, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
			barrier(cb, stack, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
					org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BLIT_BIT, org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
					org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, 0);
			vkEndCommandBuffer(cb);
		}
	}

	private static void barrier(VkCommandBuffer cb, MemoryStack stack, long image, int from, int to, long srcStage, long srcAccess, long dstStage, long dstAccess) {
		var b = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage)
				.dstAccessMask(dstAccess).oldLayout(from).newLayout(to).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
		b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
	}

	private static void submitCopy(MemoryStack stack, VkCommandBuffer cb, long acquired, long timelineValue, long presentSemaphore, long fence) {
		long blit = org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_BLIT_BIT;
		var waits = VkSemaphoreSubmitInfo.calloc(2, stack);
		waits.get(0).sType$Default().semaphore(acquired).stageMask(blit);
		waits.get(1).sType$Default().semaphore(timeline).value(timelineValue).stageMask(blit);
		var signal = VkSemaphoreSubmitInfo.calloc(2, stack);
		signal.get(0).sType$Default().semaphore(presentSemaphore).stageMask(blit);
		signal.get(1).sType$Default().semaphore(copied).value(timelineValue).stageMask(blit);
		var cbi = VkCommandBufferSubmitInfo.calloc(1, stack);
		cbi.get(0).sType$Default().commandBuffer(cb);
		var submit = VkSubmitInfo2.calloc(1, stack).sType$Default().pWaitSemaphoreInfos(waits).pCommandBufferInfos(cbi).pSignalSemaphoreInfos(signal);
		LOCK.lock();
		try {
			if (stop) throw new IllegalStateException("frame generation presenter abandoned"); // the acquired image may be destroyed
			check(KHRSynchronization2.vkQueueSubmit2KHR(vkQueue, submit, fence), "frame generation copy submit");
		} finally {
			LOCK.unlock();
		}
	}

	/** Sleeps until {@code nanos}: parks until {@link #spinNanos} before it, spins the rest (parkNanos oversleeps by the timer slack). */
	private static void waitUntil(long nanos) {
		long now;
		while ((now = System.nanoTime()) < nanos) {
			long left = nanos - now;
			if (left > spinNanos + 20_000) {
				long wake = nanos - spinNanos;
				LockSupport.parkNanos(wake - now);
				long late = System.nanoTime() - wake;
				// margin = worst recent oversleep + 25 %, decaying 1 % per park: spins only as long as this machine's wake-ups need
				spinNanos = Math.clamp(Math.max(late + late / 4, spinNanos - spinNanos / 100), 30_000L, 1_000_000L);
			} else {
				Thread.onSpinWait();
			}
		}
	}

	private static void check(int r, String what) {
		if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + r);
	}

	/** AMD's frame pacing (FrameInterpolationSwapchainVK): half-frame delay = avg/2 - 0.1·stddev - 0.1 ms over the last 10 intervals. */
	static final class Pacing {
		private static final int SIZE = 10;
		private static final long RESET_NANOS = 100_000_000L, SAFETY_NANOS = 100_000L;
		private static final double VARIANCE_FACTOR = 0.1;
		private final double[] history = new double[SIZE];
		private int count;
		private long previous;

		void reset() {
			count = 0;
			previous = 0;
		}

		/** Called with the time a frame finished on the GPU; returns the delay between its two presents (ns). */
		long update(long now) {
			double delta = previous > 0 ? now - previous : 0;
			previous = now;
			if (delta > RESET_NANOS || delta <= 0) {
				count = 0;
			} else {
				history[count % SIZE] = delta;
				count++;
			}
			if (count < SIZE) return 0;
			double avg = 0, var = 0;
			for (double d : history) avg += d;
			avg /= SIZE;
			for (double d : history) var += (d - avg) * (d - avg);
			double conservative = avg * 0.5 - Math.sqrt(var / SIZE) * VARIANCE_FACTOR;
			return conservative > SAFETY_NANOS ? (long) (conservative - SAFETY_NANOS) : 0;
		}
	}
}
