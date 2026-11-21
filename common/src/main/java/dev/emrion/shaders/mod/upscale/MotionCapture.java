package dev.emrion.shaders.mod.upscale;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Autotest {@code mvshot NAME}: the next frame's motion vectors as {@code NAME-motion.png} (128 = still, ±1 per 1/16 render
 * pixel; red = x, green = y) and, when the pack provides them, the upscaler masks as {@code NAME-masks.png} (red = reactive,
 * green = transparency/composition).
 */
public final class MotionCapture {
	private MotionCapture() {}

	private static Path dir;
	private static String name;
	private static boolean requested;
	private static RawBuffer motion, masks;
	private static int w, h, maskFormat, wait;

	public static void request(Path directory, String prefix) {
		dir = directory;
		name = prefix;
		requested = true;
	}

	public static boolean done() {
		return !requested && motion == null;
	}

	public static void record(VulkanContext ctx, VkCommandBuffer cb, MotionVectors mv, Upscaler.@Nullable Masks m) {
		save();
		if (!requested || motion != null) return;
		requested = false;
		w = mv.width;
		h = mv.height;
		motion = new RawBuffer(ctx, (long) w * h * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "motion capture");
		dev.emrion.shaders.mod.vk.Barriers.memory(cb, dev.emrion.shaders.mod.vk.Barriers.COLOR_OUT | dev.emrion.shaders.mod.vk.Barriers.COMPUTE,
				dev.emrion.shaders.mod.vk.Barriers.COLOR_WRITE | dev.emrion.shaders.mod.vk.Barriers.STORAGE_WRITE, dev.emrion.shaders.mod.vk.Barriers.TRANSFER,
				dev.emrion.shaders.mod.vk.Barriers.TRANSFER_READ);
		copy(cb, mv.image.image, motion);
		if (m != null && m.format() == VK_FORMAT_R16G16B16A16_SFLOAT) {
			masks = new RawBuffer(ctx, (long) w * h * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "mask capture");
			copy(cb, m.image(), masks);
		}
		wait = 4;
	}

	private static void copy(VkCommandBuffer cb, long image, RawBuffer dst) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.imageExtent().set(w, h, 1);
			vkCmdCopyImageToBuffer(cb, image, VK_IMAGE_LAYOUT_GENERAL, dst.handle, region);
		}
	}

	private static void save() {
		if (motion == null || --wait > 0) return; // after MC's frames in flight
		ByteBuffer px = MemoryUtil.memByteBuffer(motion.mapped, w * h * 4).order(ByteOrder.nativeOrder());
		var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int moving = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int o = ((h - 1 - y) * w + x) * 4; // GL row order: row 0 is the bottom
				float mx = Float.float16ToFloat(px.getShort(o)) * w * 16, my = Float.float16ToFloat(px.getShort(o + 2)) * h * 16;
				if (Math.abs(mx) > 8 || Math.abs(my) > 8) moving++;
				img.setRGB(x, y, Math.clamp(Math.round(128 + mx), 0, 255) << 16 | Math.clamp(Math.round(128 + my), 0, 255) << 8);
			}
		}
		write(img, name + "-motion.png");
		EmrionShadersClient.LOG.info("mvshot {}: {} of {} pixels move more than half a render pixel", name, moving, w * h);
		if (masks != null) {
			ByteBuffer m = MemoryUtil.memByteBuffer(masks.mapped, w * h * 8).order(ByteOrder.nativeOrder());
			var mi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++) {
					int o = ((h - 1 - y) * w + x) * 8;
					int r = Math.clamp(Math.round(Float.float16ToFloat(m.getShort(o)) * 255), 0, 255);
					int g = Math.clamp(Math.round(Float.float16ToFloat(m.getShort(o + 2)) * 255), 0, 255);
					mi.setRGB(x, y, r << 16 | g << 8);
				}
			write(mi, name + "-masks.png");
			masks.destroyLater();
			masks = null;
		}
		motion.destroyLater();
		motion = null;
	}

	private static void write(BufferedImage img, String file) {
		try {
			Files.createDirectories(dir);
			javax.imageio.ImageIO.write(img, "png", dir.resolve(file).toFile());
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("mvshot: cannot write {}", file, e);
		}
	}
}
