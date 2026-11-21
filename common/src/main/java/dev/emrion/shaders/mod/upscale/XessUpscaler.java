package dev.emrion.shaders.mod.upscale;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;

/**
 * Intel XeSS Super Resolution through libxess.dll's Vulkan API ({@link Xess}). One context per (render size, display size,
 * preset). XeSS wants its inputs in SHADER_READ_ONLY_OPTIMAL and the output in GENERAL; ours are all GENERAL, so the inputs
 * are transitioned around the call.
 */
final class XessUpscaler extends Upscaler {
	// xess.h / xess_vk.h, 8-byte packing (offsets checked against the headers with offsetof)
	private static final int VIEW_SIZE = 48, COLOR = 0, VELOCITY = 48, DEPTH = 96, OUTPUT = 240, JITTER_X = 288, JITTER_Y = 292,
			EXPOSURE = 296, RESET = 300, INPUT_W = 304, INPUT_H = 308, EXEC_SIZE = 360;
	private static final int FLAG_LDR_INPUT_COLOR = 1 << 6;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment params = arena.allocate(EXEC_SIZE, 8);
	private final long handle;
	private boolean first = true;

	XessUpscaler(VulkanContext ctx, int rw, int rh, int dw, int dh) {
		super(ctx, rw, rh, dw, dh);
		String why = Xess.load();
		if (why != null) {
			cleanup();
			throw new IllegalStateException("XeSS unavailable: " + why);
		}
		MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
		var dev = ctx.vk;
		var pd = dev.getPhysicalDevice();
		long h = 0;
		try {
			int r = Dlss.onBigStack(() -> (int) Xess.createContext.invokeExact(pd.getInstance().address(), pd.address(), dev.address(), out));
			check(r, "context creation");
			h = out.get(ValueLayout.ADDRESS, 0).address();
			// our motion vectors are UV offsets (current to previous); XeSS wants render pixels
			check((int) Xess.setVelocityScale.invokeExact(h, (float) rw, (float) rh), "velocity scale");
			MemorySegment init = arena.allocate(64, 8);
			init.set(ValueLayout.JAVA_INT, 0, dw);
			init.set(ValueLayout.JAVA_INT, 4, dh);
			init.set(ValueLayout.JAVA_INT, 8, quality(UpscaleConfig.preset));
			init.set(ValueLayout.JAVA_INT, 12, FLAG_LDR_INPUT_COLOR); // tonemapped colour (§1), forward depth, low-res motion vectors
			long ch = h;
			check(Dlss.onBigStack(() -> (int) Xess.init.invokeExact(ch, init)), "init");
		} catch (RuntimeException e) {
			destroyContext(h);
			cleanup();
			Xess.failCreate(e.getMessage());
			throw e;
		} catch (Throwable t) {
			destroyContext(h);
			cleanup();
			Xess.failCreate("context creation failed: " + t);
			throw new IllegalStateException("XeSS context creation failed", t);
		}
		handle = h;
	}

	/**
	 * The XeSS preset whose dynamic input range covers our ratio (XeSS 1.3+ ratios: Ultra Quality 1.5, Quality 1.7, Balanced 2.0,
	 * Ultra Performance 3.0); the render size stays the one FSR and DLSS use, which XeSS accepts as a dynamic input resolution.
	 */
	static int quality(UpscaleConfig.Preset p) {
		return switch (p) {
			case NATIVE -> 106; // AA
			case QUALITY -> 104; // ULTRA_QUALITY
			case BALANCED -> 103; // QUALITY
			case PERFORMANCE -> 102; // BALANCED
			case ULTRA -> 100; // ULTRA_PERFORMANCE
		};
	}

	private static void check(int r, String what) {
		if (r < 0) throw new IllegalStateException("XeSS " + what + " failed: " + Xess.describe(r));
	}

	private static void destroyContext(long h) {
		if (h == 0) return;
		try {
			int r = (int) Xess.destroyContext.invokeExact(h);
		} catch (Throwable ignored) {
			// nothing to recover
		}
	}

	private void cleanup() {
		super.destroyLater();
		arena.close();
	}

	@Override
	protected void evaluate(VkCommandBuffer cb, long colorImage, long colorView, int colorFormat, long depthImage, long depthView, boolean reset) {
		view(COLOR, colorImage, colorView, VK_IMAGE_ASPECT_COLOR_BIT, colorFormat, renderW, renderH);
		view(VELOCITY, motion.image, motion.view, VK_IMAGE_ASPECT_COLOR_BIT, motion.format, renderW, renderH);
		view(DEPTH, depthImage, depthView, VK_IMAGE_ASPECT_DEPTH_BIT, VK_FORMAT_D32_SFLOAT, renderW, renderH);
		view(OUTPUT, output.image, output.view, VK_IMAGE_ASPECT_COLOR_BIT, output.format, displayW, displayH);
		params.set(ValueLayout.JAVA_FLOAT, JITTER_X, Upscaling.jitterX()); // render pixels, FSR's sign (XeSS documents the same shear)
		params.set(ValueLayout.JAVA_FLOAT, JITTER_Y, Upscaling.jitterY());
		params.set(ValueLayout.JAVA_FLOAT, EXPOSURE, 1f);
		params.set(ValueLayout.JAVA_INT, RESET, reset || first ? 1 : 0);
		params.set(ValueLayout.JAVA_INT, INPUT_W, renderW);
		params.set(ValueLayout.JAVA_INT, INPUT_H, renderH);
		long[] inputs = {colorImage, motion.image, depthImage};
		int[] aspects = {VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_ASPECT_DEPTH_BIT};
		layouts(cb, inputs, aspects, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
		int r;
		try {
			r = (int) Xess.execute.invokeExact(handle, cb.address(), params);
		} catch (Throwable t) {
			throw new IllegalStateException("XeSS execute failed", t);
		}
		layouts(cb, inputs, aspects, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
		first = false;
		if (r < 0) throw new IllegalStateException("XeSS execute failed: " + Xess.describe(r));
		Profiler.mark(cb, "XeSS");
	}

	/** One xess_vk_image_view_info: mip 0, layer 0 of a 2D image. */
	private void view(int at, long image, long view, int aspect, int format, int w, int h) {
		params.asSlice(at, VIEW_SIZE).fill((byte) 0);
		params.set(ValueLayout.JAVA_LONG, at, view);
		params.set(ValueLayout.JAVA_LONG, at + 8, image);
		params.set(ValueLayout.JAVA_INT, at + 16, aspect);
		params.set(ValueLayout.JAVA_INT, at + 24, 1); // levelCount (baseMipLevel 0 at +20)
		params.set(ValueLayout.JAVA_INT, at + 32, 1); // layerCount (baseArrayLayer 0 at +28)
		params.set(ValueLayout.JAVA_INT, at + 36, format);
		params.set(ValueLayout.JAVA_INT, at + 40, w);
		params.set(ValueLayout.JAVA_INT, at + 44, h);
	}

	/** Layout change of mip 0 of each input, ordered against everything before and after it (XeSS reads in compute). */
	private static void layouts(VkCommandBuffer cb, long[] images, int[] aspects, int from, int to) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var b = VkImageMemoryBarrier2.calloc(images.length, stack);
			for (int i = 0; i < images.length; i++) {
				b.get(i).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).srcAccessMask(VK_ACCESS_2_MEMORY_WRITE_BIT)
						.dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
						.oldLayout(from).newLayout(to).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(images[i]);
				b.get(i).subresourceRange().aspectMask(aspects[i]).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			}
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
		}
	}

	@Override
	public void destroyLater() {
		super.destroyLater();
		long h = handle;
		ctx.destroyLater(() -> {
			destroyContext(h);
			arena.close();
		});
	}
}
