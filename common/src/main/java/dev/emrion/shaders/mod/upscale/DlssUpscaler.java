package dev.emrion.shaders.mod.upscale;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * NVIDIA DLSS Super Resolution, or DLSS Ray Reconstruction (docs/UPSCALING.md §11), through NGX via the shim ({@link Dlss}).
 * One NGX feature per (render size, display size, preset, RR); our images are all in GENERAL layout, which NGX accepts for
 * its inputs and output.
 */
final class DlssUpscaler extends Upscaler {
	private static final int FLAG_MV_LOW_RES = 1 << 1;
	private final long handle;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment images = arena.allocate(ValueLayout.JAVA_LONG, 18), formats = arena.allocate(ValueLayout.JAVA_INT, 9),
			matrices = arena.allocate(ValueLayout.JAVA_FLOAT, 32);
	private final java.nio.FloatBuffer matrixBuffer = matrices.asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
	/** GL clip space to the D3D convention NGX assumes: z from [-1, 1] to [0, 1], and y flipped (our images keep GL's row order). */
	private static final Matrix4fc TO_NGX_CLIP = new Matrix4f().scaling(1f, -1f, 0.5f).translateLocal(0f, 0f, 0.5f);
	private final Matrix4f viewToClip = new Matrix4f();
	private boolean first = true;
	/** Ray Reconstruction: its guide pass (null for Super Resolution). */
	private final @org.jspecify.annotations.Nullable RrGuides guides;

	DlssUpscaler(VulkanContext ctx, int rw, int rh, int dw, int dh, boolean rayReconstruction) {
		super(ctx, rw, rh, dw, dh);
		String why = Dlss.ensureInit(ctx);
		if (why != null) {
			destroyParts();
			throw new IllegalStateException("DLSS unavailable: " + why);
		}
		int quality = switch (UpscaleConfig.preset) {
			case NATIVE -> 5; // DLAA
			case QUALITY -> 2; // MaxQuality
			case BALANCED -> 1;
			case PERFORMANCE -> 0; // MaxPerf
			case ULTRA -> 3; // UltraPerformance
		};
		String rrWhy = rayReconstruction ? Dlss.rrUnavailable() : null;
		if (rrWhy != null) {
			destroyParts();
			Dlss.failRr(rrWhy);
			throw new IllegalStateException("DLSS Ray Reconstruction unavailable: " + rrWhy);
		}
		try {
			guides = rayReconstruction ? new RrGuides(ctx, rw, rh) : null;
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			VkCommandBuffer cb = RawCommands.begin(ctx);
			if (!rayReconstruction && Dlss.setPreset != null) Dlss.setPreset.invokeExact(UpscaleConfig.dlssPreset());
			int r = Dlss.onBigStack(() -> (int) (rayReconstruction ? Dlss.rrCreate : Dlss.create).invokeExact(cb.address(), rw, rh, dw, dh, quality,
					FLAG_MV_LOW_RES, out));
			RawCommands.submit(ctx, cb);
			if (Dlss.failed(r) && rayReconstruction) {
				Dlss.failRr("feature creation failed: " + Dlss.describe(r));
				dev.emrion.shaders.mod.EmrionShadersClient.LOG.error("DLSS RR {}x{} -> {}x{}: {}; NGX log:\n{}", rw, rh, dw, dh, Dlss.rrUnavailable(), Dlss.ngxLog());
				throw new IllegalStateException("DLSS Ray Reconstruction " + Dlss.rrUnavailable());
			}
			if (Dlss.failed(r)) {
				Dlss.failCreate("feature creation failed: " + Dlss.describe(r));
				dev.emrion.shaders.mod.EmrionShadersClient.LOG.error("DLSS {}x{} -> {}x{}: {}; NGX log:\n{}", rw, rh, dw, dh, Dlss.unavailable(), Dlss.ngxLog());
				throw new IllegalStateException("DLSS " + Dlss.unavailable());
			}
			handle = out.get(ValueLayout.ADDRESS, 0).address();
			Dlss.features.add(handle);
		} catch (RuntimeException e) {
			destroyParts();
			throw e;
		} catch (Throwable t) {
			destroyParts();
			Dlss.failCreate("feature creation failed: " + t);
			throw new IllegalStateException("DLSS feature creation failed", t);
		}
	}

	/** A failed construction: what was made so far ({@link #guides} is null when its own construction threw). */
	private void destroyParts() {
		super.destroyLater();
		if (guides != null) guides.destroyLater();
		arena.close();
	}

	@Override
	public boolean rayReconstruction() {
		return guides != null;
	}

	@Override
	protected void evaluate(VkCommandBuffer cb, long colorImage, long colorView, int colorFormat, long depthImage, long depthView, boolean reset) {
		set(0, colorImage, colorView, colorFormat);
		set(1, depthImage, depthView, VK_FORMAT_D32_SFLOAT);
		set(2, motion.image, motion.view, motion.format);
		set(3, output.image, output.view, output.format);
		if (guides != null) {
			evaluateRr(cb, colorFormat, reset);
			return;
		}
		boolean mask = masks != null && Dlss.evaluateMask != null;
		if (mask) set(4, masks.image(), masks.reactiveView(), masks.format());
		int r;
		try {
			// motion vectors are UV offsets: scale to render pixels
			r = (int) (mask ? Dlss.evaluateMask : Dlss.evaluate).invokeExact(cb.address(), handle, images, formats, renderW, renderH, displayW, displayH,
					Upscaling.jitterX(), Upscaling.jitterY(), (float) renderW, (float) renderH, reset || first ? 1 : 0, deltaMs);
		} catch (Throwable t) {
			throw new IllegalStateException("DLSS evaluate failed", t);
		}
		first = false;
		if (Dlss.failed(r)) throw new IllegalStateException("DLSS evaluate failed (0x" + Integer.toHexString(r) + ")");
		Profiler.mark(cb, "DLSS");
	}

	/** Slot {@code i} of the shim's image / view / format arrays. */
	private void set(int i, long image, long view, int format) {
		images.setAtIndex(ValueLayout.JAVA_LONG, 2 * i, image);
		images.setAtIndex(ValueLayout.JAVA_LONG, 2 * i + 1, view);
		formats.setAtIndex(ValueLayout.JAVA_INT, i, format);
	}

	/** Ray Reconstruction: guide pass, then DLSS-D with colour, depth, motion, guides and the bias mask (images 0-7 already set). */
	private void evaluateRr(VkCommandBuffer cb, int colorFormat, boolean reset) {
		if (rr == null || !guides.dispatch(cb, images.getAtIndex(ValueLayout.JAVA_LONG, 1), images.getAtIndex(ValueLayout.JAVA_LONG, 3),
				masks != null ? masks.compositionView() : 0, masks != null ? masks.reactiveView() : 0, rr)) {
			// no TLAS yet (the first frames of a world): black instead of an unwritten image, and RR starts over afterwards
			try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
				dev.emrion.shaders.mod.vk.Barriers.memory(cb, dev.emrion.shaders.mod.vk.Barriers.COMPUTE, 0, dev.emrion.shaders.mod.vk.Barriers.TRANSFER,
						dev.emrion.shaders.mod.vk.Barriers.TRANSFER_WRITE);
				vkCmdClearColorImage(cb, output.image, VK_IMAGE_LAYOUT_GENERAL, org.lwjgl.vulkan.VkClearColorValue.calloc(stack),
						org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
			}
			first = true;
			return;
		}
		set(0, guides.color.image, guides.color.view, guides.color.format);
		set(4, guides.diffuse.image, guides.diffuse.view, guides.diffuse.format);
		set(5, guides.specular.image, guides.specular.view, guides.specular.format);
		set(6, guides.normals.image, guides.normals.view, guides.normals.format);
		// our bias mask: the pack's reactive mask (rr_guides.comp)
		boolean mask = true;
		set(7, guides.bias.image, guides.bias.view, guides.bias.format);
		boolean particles = masks != null; // the particle mask comes from Complementary's composition mask
		set(8, particles ? guides.particles.image : 0, particles ? guides.particles.view : 0, guides.particles.format);
		// world (camera-relative, the normals' space) to view, and view to clip; JOML's column-major floats are the row-major
		// left-multiplication matrices NGX documents. The model may read them even without a specular hit distance (it crashed with null).
		rr.modelView().get(0, matrixBuffer);
		TO_NGX_CLIP.mul(Upscaling.unjittered, viewToClip).get(16, matrixBuffer);
		int r;
		try {
			r = (int) Dlss.rrEvaluate.invokeExact(cb.address(), handle, images, formats, renderW, renderH, displayW, displayH, Upscaling.jitterX(),
					Upscaling.jitterY(), (float) renderW, (float) renderH, reset || first ? 1 : 0, deltaMs, mask ? 1 : 0, matrices);
		} catch (Throwable t) {
			throw new IllegalStateException("DLSS RR evaluate failed", t);
		}
		first = false;
		if (Dlss.failed(r)) throw new IllegalStateException("DLSS RR evaluate failed (0x" + Integer.toHexString(r) + ")");
		Profiler.mark(cb, "DLSS RR");
	}

	@Override
	public void destroyLater() {
		super.destroyLater();
		if (guides != null) guides.destroyLater();
		long h = handle;
		ctx.destroyLater(() -> {
			Dlss.release(h);
			arena.close();
		});
	}
}
