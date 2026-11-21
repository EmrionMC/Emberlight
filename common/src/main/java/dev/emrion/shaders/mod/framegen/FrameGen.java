package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.upscale.MotionVectors;
import dev.emrion.shaders.mod.upscale.Upscaling;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/** Render-thread side of frame generation; every entry point returns at once while the mode is Off. */
public final class FrameGen {
	private FrameGen() {}

	private static final int SLOTS = 4; // = Presenter.SLOTS: frame N may overwrite the slot of N-4 once its copy is submitted
	private static @Nullable Generator generator;
	private static FrameGenConfig.Mode generatorMode = FrameGenConfig.Mode.OFF;
	private static int genRW, genRH, genDW, genDH;
	private static @Nullable RawImage hudless;
	private static final RawImage[] slots = new RawImage[SLOTS];
	/** The frame number that last wrote each slot (0 = none): the presenter's copy of it must be done before the slot is reused. */
	private static final long[] slotFrames = new long[SLOTS];
	private static @Nullable MotionVectors ownMotion;
	private static long frameNumber;
	private static boolean resetNext = true;
	private static volatile @Nullable String failure;
	/** Rendered rate already at or above the display's refresh: interpolated frames could not be shown, so none are made. */
	private static volatile boolean bypass;
	private static long lastBlit;
	private static double avgFrameNanos;

	// this frame
	private static boolean captured;
	private static int renderW, renderH, depthFormat;
	private static long depthImage, depthView;
	private static @Nullable MotionVectors upscalerMotion;
	private static final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f(), clipToPrev = new Matrix4f();
	private static float jitterX, jitterY;
	private static boolean hudlessTaken;
	/** Set in blitFromTexture for the present hook: hand this frame to the presenter, with this interpolated image (or null). */
	private static boolean presentThisFrame;
	private static @Nullable RawImage interpThisFrame;

	/** Why frame generation stopped (generator creation failed), or null. */
	public static @Nullable String failure() {
		return failure;
	}

	/** Why selected frame generation is not producing frames for another reason (stopped presenter, waiting for VRAM), or null. */
	public static @Nullable String stalled() {
		return Presenter.wedged() ? "stopped after a present error" : vramWaiting ? "waiting for GPU memory" : null;
	}

	/** Restarts the interpolation history (pack swap, camera cut, mode change). */
	public static void requestReset() {
		resetNext = true;
	}

	/** FrameExecutor.finalPass: the pack rendered this frame; remember the inputs. */
	public static void capture(int rw, int rh, long depthImg, long depthSampled, int depthVkFormat, @Nullable MotionVectors upscalerMv,
			Matrix4fc gbufferModelView) {
		if (FrameGenConfig.effective() == FrameGenConfig.Mode.OFF) return;
		captured = true;
		renderW = rw;
		renderH = rh;
		depthImage = depthImg;
		depthView = depthSampled;
		depthFormat = depthVkFormat;
		upscalerMotion = upscalerMv;
		modelView.set(gbufferModelView);
		projection.set(Upscaling.unjittered);
		jitterX = Upscaling.active() ? Upscaling.jitterX() : 0f;
		jitterY = Upscaling.active() ? Upscaling.jitterY() : 0f;
	}

	/** True when the pack rendered this frame with frame generation on (the HUD-less copy is wanted). */
	public static boolean wantsHudless() {
		return captured;
	}

	/** GameRenderer.render, after the level and vanilla post effects, before the GUI: copy MC's main colour (HUD-less). */
	public static void beforeGui(long mainColorImage, int width, int height) {
		if (!captured || bypass) return;
		VulkanContext ctx = VulkanContext.get();
		if (!ensure(ctx, renderW, renderH, width, height)) {
			captured = false;
			return;
		}
		var cb = RawCommands.begin(ctx);
		copy(cb, mainColorImage, hudless, width, height);
		RawCommands.submit(ctx, cb);
		hudlessTaken = true;
	}

	/** VulkanGpuSurface.blitFromTexture HEAD (after the GUI): the frame-generation dispatch for this frame. */
	public static void beforeBlit(long backbufferImage, long backbufferView) {
		boolean on = FrameGenConfig.effective() != FrameGenConfig.Mode.OFF;
		presentThisFrame = on;
		interpThisFrame = null;
		boolean had = captured;
		captured = false;
		if (on) updateBypass();
		if (!on || !hudlessTaken || !had || generator == null) {
			hudlessTaken = false;
			if (on) resetNext = true; // a gap in the frame sequence (menu, pack off)
			return;
		}
		hudlessTaken = false;
		VulkanContext ctx = VulkanContext.get();
		int si = (int) ((frameNumber + 1) % SLOTS);
		if (!Presenter.copyDone(ctx, slotFrames[si])) { // the presenter's copy of that frame is not done: skip this interpolated frame
			EmrionShadersClient.LOG.warn("Frame generation: slot of frame {} still in use, interpolated frame skipped", slotFrames[si]);
			resetNext = true;
			return;
		}
		RawImage out = slots[si];
		var cb = RawCommands.begin(ctx);
		try {
			MotionVectors mv;
			if (upscalerMotion != null) {
				mv = upscalerMotion;
			} else {
				if (ownMotion == null || ownMotion.width != renderW || ownMotion.height != renderH) {
					if (ownMotion != null) ownMotion.destroyLater();
					ownMotion = new MotionVectors(ctx, renderW, renderH);
				}
				mv = ownMotion;
				mv.dispatch(cb, depthView, depthView, 0, 0, projection, modelView);
				dev.emrion.shaders.mod.upscale.MotionCapture.record(ctx, cb, mv, null);
			}
			clipToPrev.set(mv.clipToPrevClip());
			boolean reset = resetNext || mv.reset;
			resetNext = false;
			var in = new Generator.Inputs(renderW, renderH, genDW, genDH, depthImage, depthView, depthFormat, mv.image.image, mv.image.view,
					jitterX, jitterY, projection, clipToPrev, mv.deltaMs, reset);
			generator.dispatch(cb, in, hudless, backbufferImage, backbufferView, out);
			Profiler.mark(cb, "frame generation");
			interpThisFrame = out;
			FfxPasses.barrier(cb);
			FrameCapture.record(ctx, cb, reset ? 0 : out.image, backbufferImage, genDW, genDH); // a reset frame's "interpolation" is a copy of this frame
			if (dev.emrion.shaders.mod.hdr.Hdr.active()) interpThisFrame = dev.emrion.shaders.mod.hdr.Hdr.encodeInterpolated(ctx, cb, si, out, backbufferView, hudless);
			slotFrames[si] = frameNumber + 1; // last: on a throw above no presenter copy of this slot will ever be signalled
		} catch (RuntimeException e) {
			fail(generatorMode.label + " failed: " + e.getMessage(), e);
			interpThisFrame = null;
		}
		RawCommands.submit(ctx, cb);
		if (failure != null && generator != null) destroy(ctx); // stays off until the mode changes (ensure)
	}

	/** True while frame generation is paused because the rendered rate already reaches the refresh rate. */
	public static boolean bypassed() {
		return bypass && FrameGenConfig.effective() != FrameGenConfig.Mode.OFF;
	}

	/** Pauses interpolation while rendered FPS reaches the refresh rate; resumes below 90 % of it. */
	private static void updateBypass() {
		long now = System.nanoTime();
		double d = now - lastBlit;
		lastBlit = now;
		if (d <= 0 || d > 250_000_000L) return; // menus, loading
		avgFrameNanos = avgFrameNanos == 0 ? d : avgFrameNanos * 0.95 + d * 0.05;
		var monitor = net.minecraft.client.Minecraft.getInstance().getWindow().findBestMonitor();
		float hz = monitor != null ? monitor.currentMode().getRefreshRate() : 0;
		if (!FrameGenConfig.autoPause || hz <= 0 || !FrameCapture.done()) { // autotest fgshots needs interpolated frames
			bypass = false;
			return;
		}
		double fps = 1e9 / avgFrameNanos;
		boolean was = bypass;
		if (!bypass && fps >= hz) bypass = true;
		else if (bypass && fps < hz * 0.9) bypass = false; // the next frame has no HUD-less copy yet: that gap resets the history
		if (bypass != was) EmrionShadersClient.LOG.info(bypass
				? "Frame generation paused: {} rendered FPS reach the {} Hz refresh rate (auto pause, framegen.autoPause=false keeps interpolating)"
				: "Frame generation resumed: {} rendered FPS below 90 % of the {} Hz refresh rate", String.format(java.util.Locale.ROOT, "%.0f", fps), String.format(java.util.Locale.ROOT, "%.0f", hz));
	}

	/** blitFromTexture TAIL: the frame's submission signals our timeline semaphore once the GPU is done with it. */
	public static void afterBlit(VulkanCommandEncoder encoder) {
		if (!presentThisFrame) return;
		VulkanContext ctx = VulkanContext.get();
		if (!Presenter.running()) Presenter.start(ctx, frameNumber);
		encoder.signalSemaphore(Presenter.timeline(), ++frameNumber, 65536L /* ALL_COMMANDS */);
	}

	/** VulkanGpuSurface.present HEAD; false = MC presents itself (FG off). */
	public static boolean present(long swapchain, long[] images, int width, int height, int imageIndex, long presentSemaphore) {
		if (!presentThisFrame) {
			if (Presenter.running()) {
				VulkanContext ctx = VulkanContext.get();
				Presenter.stop(ctx);
				// FG switched off: give the generator and slots back (ensure() rebuilds them); a photo only pauses it
				if (!dev.emrion.shaders.mod.photo.PhotoMode.frozen()) destroy(ctx);
			}
			return false;
		}
		presentThisFrame = false;
		Presenter.submit(new Presenter.Frame(swapchain, images, width, height, imageIndex, presentSemaphore, interpThisFrame, frameNumber, Reflex.currentFrame()));
		return true;
	}

	/** The worst present result the presenter saw since the last frame (MC's surface turns it into its own flags). */
	public static int takePresentResult() {
		return Presenter.takeResult();
	}

	/** Creates or replaces the generator and the images for these sizes and the current mode; false when it cannot run. */
	private static boolean ensure(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		var mode = FrameGenConfig.effective();
		if (generator != null && mode == generatorMode && rw == genRW && rh == genRH && dw == genDW && dh == genDH) return true;
		destroy(ctx);
		if (failure != null && mode == generatorMode) return false;
		failure = null;
		generatorMode = mode;
		if (mode == FrameGenConfig.Mode.DLSS && !vramFree(ctx, dw, dh)) return false;
		genRW = rw;
		genRH = rh;
		genDW = dw;
		genDH = dh;
		try {
			int usage = FfxPasses.USAGE;
			hudless = new RawImage(ctx, VK_FORMAT_R8G8B8A8_UNORM, dw, dh, 1, 1, usage, "fg hud-less colour");
			for (int i = 0; i < SLOTS; i++) slots[i] = new RawImage(ctx, VK_FORMAT_R8G8B8A8_UNORM, dw, dh, 1, 1, usage, "fg interpolated frame " + i);
			generator = mode == FrameGenConfig.Mode.DLSS ? new DlssFrameGen(ctx, rw, rh, dw, dh) : new FsrFrameGen(ctx, rw, rh, dw, dh);
			EmrionShadersClient.LOG.info("Frame generation {} {}x{} -> {}x{}", mode.label, rw, rh, dw, dh);
		} catch (RuntimeException e) {
			fail(mode.label + ": " + e.getMessage(), e);
			destroy(ctx);
			return false;
		}
		resetNext = true;
		return true;
	}

	private static boolean vramWaiting;

	/**
	 * NGX crashes (SIGSEGV) instead of failing when VRAM runs out while it creates DLSS FG, e.g. right after a pack reload
	 * while the old runtime is still queued for destruction: wait until there is room. Measured peak ~670 MiB at 2560x1386.
	 */
	private static boolean vramFree(VulkanContext ctx, int dw, int dh) {
		long free = dev.emrion.shaders.mod.debug.VramReport.freeDeviceLocalMiB(ctx), need = 256 + (long) dw * dh * 200 / 1048576;
		boolean ok = free < 0 || free >= need;
		if (!ok && !vramWaiting) EmrionShadersClient.LOG.warn("Frame generation: waiting for GPU memory before creating DLSS FG ({} MiB free, needs {} MiB)", free, need);
		vramWaiting = !ok;
		return ok;
	}

	private static void fail(String why, Throwable t) {
		failure = why;
		EmrionShadersClient.LOG.error("Frame generation unavailable: {}", why, t);
		if (generatorMode == FrameGenConfig.Mode.DLSS) DlssFrameGen.fail(why);
	}

	/** Frees everything (resize, mode change, shutdown); the presenter is drained first, it may still read the slots. */
	public static void destroy(VulkanContext ctx) {
		long last = 0;
		for (long v : slotFrames) last = Math.max(last, v);
		boolean slotsFree = Presenter.drain() && Presenter.copyDone(ctx, last);
		if (!slotsFree && slots[0] != null) EmrionShadersClient.LOG.error("Frame generation: presenter copies not finished, interpolation slots leaked");
		dev.emrion.shaders.mod.hdr.Hdr.destroyInterpolated(slotsFree);
		if (generator != null) generator.destroyLater();
		generator = null;
		if (hudless != null) hudless.destroyLater();
		hudless = null;
		for (int i = 0; i < SLOTS; i++) {
			if (slots[i] != null && slotsFree) slots[i].destroyLater();
			slots[i] = null;
			slotFrames[i] = 0;
		}
		if (ownMotion != null) ownMotion.destroyLater();
		ownMotion = null;
	}

	/** Client stopping: before NGX shuts down and MC destroys the device. */
	public static void shutdown(VulkanContext ctx) {
		FrameGenConfig.closed = true;
		Presenter.stop(ctx);
		destroy(ctx);
	}

	private static void copy(VkCommandBuffer cb, long src, RawImage dst, int w, int h) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(Math.min(w, dst.width), Math.min(h, dst.height), 1);
			vkCmdCopyImage(cb, src, VK_IMAGE_LAYOUT_GENERAL, dst.image, VK_IMAGE_LAYOUT_GENERAL, region);
		}
	}
}
