package dev.emrion.shaders.mod.vk;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

/**
 * VMA image that MC never sees: 2D or 3D, any usage (storage included). Transitioned to GENERAL once at creation, so it
 * must be created outside an open MC render pass.
 */
public final class RawImage {
	private final VulkanContext ctx;
	public final long image;
	public final long view;
	public final int format, width, height, depth, mipLevels;
	private final long allocation;

	public RawImage(VulkanContext ctx, int format, int width, int height, int depth, int mipLevels, int vkUsage, String label) {
		this.ctx = ctx;
		this.format = format;
		this.width = width;
		this.height = height;
		this.depth = depth;
		this.mipLevels = mipLevels;
		boolean is3d = depth > 1;
		int aspect = isDepth(format) ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkImageCreateInfo.calloc(stack).sType$Default()
					.imageType(is3d ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D).format(format).mipLevels(mipLevels).arrayLayers(1)
					.samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(vkUsage)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			info.extent().set(width, height, depth);
			var alloc = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer p = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VulkanContext.check(vmaCreateImage(ctx.vma, info, alloc, p, pAlloc, null), "vmaCreateImage " + label);
			image = p.get(0);
			allocation = pAlloc.get(0);

			var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
					.viewType(is3d ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(format);
			viewInfo.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
			VulkanContext.check(vkCreateImageView(ctx.vk, viewInfo, null, p), "vkCreateImageView " + label);
			view = p.get(0);
		}
		var cb = RawCommands.begin(ctx);
		Barriers.initGeneral(cb, image, aspect, mipLevels, 1);
		RawCommands.submit(ctx, cb);
	}

	private static boolean isDepth(int format) {
		return format == VK_FORMAT_D32_SFLOAT || format == VK_FORMAT_D16_UNORM || format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
	}

	public void destroyLater() {
		ctx.destroyLater(() -> {
			vkDestroyImageView(ctx.vk, view, null);
			vmaDestroyImage(ctx.vma, image, allocation);
		});
	}
}
