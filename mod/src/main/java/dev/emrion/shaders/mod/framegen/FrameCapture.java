package dev.emrion.shaders.mod.framegen;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Autotest {@code fgshots}: reads back the next N frames exactly as the presenter shows them, the interpolated image and the
 * real one (with GUI), and saves them as {@code NAME-NN-a-interp.png} / {@code NAME-NN-b-real.png} (display order).
 */
public final class FrameCapture {
	private FrameCapture() {}

	private record Pending(RawBuffer interp, RawBuffer real, int w, int h, int index, long frame) {}

	private static int remaining, index;
	private static Path dir;
	private static String name;
	private static long frame;
	private static final ArrayDeque<Pending> pending = new ArrayDeque<>();

	public static void request(int frames, Path directory, String prefix) {
		remaining = frames;
		index = 0;
		dir = directory;
		name = prefix;
	}

	public static boolean done() {
		return remaining == 0 && pending.isEmpty();
	}

	/** In the frame-generation command buffer after the dispatch; {@code interpImage} may be 0 (no interpolated frame). */
	static void record(VulkanContext ctx, VkCommandBuffer cb, long interpImage, long realImage, int w, int h) {
		frame++;
		save(3); // earlier captures are finished after MC's two frames in flight
		if (remaining == 0 || interpImage == 0) return;
		remaining--;
		long size = (long) w * h * 4;
		var a = new RawBuffer(ctx, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "fg capture interp");
		var b = new RawBuffer(ctx, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, "fg capture real");
		copy(cb, interpImage, a, w, h);
		copy(cb, realImage, b, w, h);
		pending.add(new Pending(a, b, w, h, index++, frame));
	}

	private static void copy(VkCommandBuffer cb, long image, RawBuffer dst, int w, int h) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var region = VkBufferImageCopy.calloc(1, stack);
			region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.imageExtent().set(w, h, 1);
			vkCmdCopyImageToBuffer(cb, image, VK_IMAGE_LAYOUT_GENERAL, dst.handle, region);
		}
	}

	/** Frame generation stopped (presenter stopped): finishes the captures still in flight instead of keeping them forever. */
	static void flush(VulkanContext ctx) {
		if (pending.isEmpty()) return;
		vkQueueWaitIdle(ctx.graphicsQueue.vkQueue()); // render thread, presenter gone: nobody else submits to MC's queue now
		save(-1);
		remaining = 0;
	}

	/** Writes the captures older than {@code age} frames. */
	private static void save(int age) {
		while (!pending.isEmpty() && frame - pending.peek().frame() > age) {
			Pending p = pending.poll();
			write(p.interp(), p.w(), p.h(), String.format(java.util.Locale.ROOT, "%s-%02d-a-interp.png", name, p.index()));
			write(p.real(), p.w(), p.h(), String.format(java.util.Locale.ROOT, "%s-%02d-b-real.png", name, p.index()));
			p.interp().destroyLater();
			p.real().destroyLater();
		}
	}

	private static void write(RawBuffer buf, int w, int h, String file) {
		ByteBuffer px = MemoryUtil.memByteBuffer(buf.mapped, w * h * 4);
		var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) { // our images keep GL row order: row 0 is the bottom
			int row = (h - 1 - y) * w * 4;
			for (int x = 0; x < w; x++) {
				int o = row + x * 4;
				img.setRGB(x, y, (px.get(o) & 0xFF) << 16 | (px.get(o + 1) & 0xFF) << 8 | px.get(o + 2) & 0xFF);
			}
		}
		try {
			Files.createDirectories(dir);
			javax.imageio.ImageIO.write(img, "png", dir.resolve(file).toFile());
		} catch (Exception e) {
			EmrionShadersClient.LOG.warn("fgshots: cannot write {}", file, e);
		}
	}
}
