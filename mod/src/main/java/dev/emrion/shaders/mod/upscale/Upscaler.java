package dev.emrion.shaders.mod.upscale;

import static dev.emrion.shaders.mod.vk.Barriers.*;
import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.VulkanContext;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** One upscaler at one (render, display) size: camera motion vectors, then FSR, DLSS or XeSS into {@link #output}. */
public abstract class Upscaler {
	protected final VulkanContext ctx;
	public final int renderW, renderH, displayW, displayH;
	public final RawImage output;
	/** Camera motion vectors at render size (also frame generation's input). */
	public final MotionVectors mv;
	protected final RawImage motion;
	protected float deltaMs = 16.7f;
	/** This frame's reactive / transparency-and-composition masks (render size), or null. */
	protected @Nullable Masks masks;
	/** -Demberlight.upscale.masks=false (or the autotest step {@code upscaleopts}) drops the masks, for comparisons. */
	public static boolean masksOn = !"false".equals(System.getProperty("emberlight.upscale.masks"));

	/**
	 * Masks for the upscaler (docs/UPSCALING.md §2): {@code reactiveView} reads the reactive value in r, {@code compositionView}
	 * the transparency/composition value in r (a swizzled view of the same image).
	 */
	public record Masks(long image, int format, long reactiveView, long compositionView) {}

	/**
	 * What DLSS Ray Reconstruction's guide pass reads besides colour and depth: the block atlas, the pack's normal and
	 * smoothness targets (Complementary colortex4 / colortex6, used when {@code pack}), and this frame's jittered matrices.
	 */
	public record RrInputs(long atlasView, long normalView, long materialView, boolean pack, Matrix4fc projection, Matrix4fc modelView) {}

	/** This frame's guide inputs (null: no RR this frame). */
	protected @Nullable RrInputs rr;

	protected Upscaler(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		this.ctx = ctx;
		renderW = rw;
		renderH = rh;
		displayW = dw;
		displayH = dh;
		int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
		output = new RawImage(ctx, VK_FORMAT_R16G16B16A16_SFLOAT, dw, dh, 1, 1, usage, "upscaled colour");
		mv = new MotionVectors(ctx, rw, rh);
		motion = mv.image;
	}

	/** Whether this upscaler is DLSS Ray Reconstruction (it then wants {@link RrInputs}). */
	public boolean rayReconstruction() {
		return false;
	}

	/** Creates the configured upscaler; throws with a readable reason when it cannot run. */
	public static Upscaler create(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		return create(ctx, rw, rh, dw, dh, UpscaleConfig.rrActive());
	}

	/** {@code rayReconstruction} false: DLSS SR even when RR is selected (no pack: no RT signal and no guide inputs). */
	public static Upscaler create(VulkanContext ctx, int rw, int rh, int dw, int dh, boolean rayReconstruction) {
		long start = System.nanoTime();
		try {
			return switch (UpscaleConfig.effective()) {
				case FSR -> new Fsr3Upscaler(ctx, rw, rh, dw, dh);
				case DLSS -> new DlssUpscaler(ctx, rw, rh, dw, dh, rayReconstruction);
				case XESS -> new XessUpscaler(ctx, rw, rh, dw, dh);
				case OFF -> throw new IllegalStateException("upscaling is off");
			};
		} finally {
			dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.UPSCALER, start);
		}
	}

	/** Records motion vectors and the upscale; color and depth must be compute-readable, output is readable afterwards. */
	public final void dispatch(VkCommandBuffer cb, long colorImage, long colorView, int colorFormat, long depthImage, long depthView, long behindView,
			Matrix4fc modelView, @Nullable Masks masks, @Nullable RrInputs rr) {
		this.rr = rr;
		boolean reset = mv.dispatch(cb, depthView, behindView, masks != null ? masks.compositionView() : 0, Upscaling.unjittered, modelView);
		deltaMs = mv.deltaMs;
		this.masks = masksOn ? masks : null;
		evaluate(cb, colorImage, colorView, colorFormat, depthImage, depthView, reset);
		MotionCapture.record(ctx, cb, mv, this.masks);
		memory(cb, COMPUTE | TRANSFER, STORAGE_WRITE | TRANSFER_WRITE, FRAGMENT | COMPUTE | TRANSFER, SAMPLED_READ | STORAGE_READ | TRANSFER_READ);
	}

	/** The upscale proper; {@link #motion} is written and readable. */
	protected abstract void evaluate(VkCommandBuffer cb, long colorImage, long colorView, int colorFormat, long depthImage, long depthView, boolean reset);

	/** This frame's projection without the jitter (GL convention, possibly with an infinite far plane). */
	protected static Matrix4fc projection() {
		return Upscaling.unjittered;
	}

	static void image(VkWriteDescriptorSet w, MemoryStack stack, int binding, int type, long sampler, long view) {
		w.sType$Default().dstBinding(binding).descriptorType(type).descriptorCount(1)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
	}

	public void destroyLater() {
		output.destroyLater();
		mv.destroyLater();
	}
}
