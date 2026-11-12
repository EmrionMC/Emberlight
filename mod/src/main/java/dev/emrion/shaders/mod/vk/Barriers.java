package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/**
 * Precise sync2 barriers for raw passes. Every image stays in GENERAL, so a global memory barrier with exact stages and
 * access masks is enough (no layout transitions after creation). MC's own ops are fenced by its ALL_COMMANDS barrier.
 */
public final class Barriers {
	private Barriers() {}

	public static final long FRAGMENT = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
	public static final long COMPUTE = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
	public static final long COLOR_OUT = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
	public static final long DEPTH = VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT;
	public static final long TRANSFER = VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;

	public static final long SAMPLED_READ = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
	public static final long STORAGE_READ = VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
	public static final long STORAGE_WRITE = VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
	public static final long COLOR_WRITE = VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
	public static final long COLOR_READ = VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT;
	public static final long DEPTH_WRITE = VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
	public static final long TRANSFER_WRITE = VK_ACCESS_2_TRANSFER_WRITE_BIT;

	public static void memory(VkCommandBuffer cb, long srcStage, long srcAccess, long dstStage, long dstAccess) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var b = VkMemoryBarrier2.calloc(1, stack).sType$Default()
					.srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(b));
		}
	}

	/** Render target written by a raw pass, then sampled by the next fragment pass. */
	public static void colorToSampled(VkCommandBuffer cb) {
		memory(cb, COLOR_OUT, COLOR_WRITE, FRAGMENT | COMPUTE, SAMPLED_READ);
	}

	/** Storage image/buffer written by compute, then read by fragment or compute. */
	public static void storageToRead(VkCommandBuffer cb) {
		memory(cb, COMPUTE, STORAGE_WRITE, FRAGMENT | COMPUTE, SAMPLED_READ | STORAGE_READ);
	}

	/** An image that a pass sampled is written next as a color target (write-after-read: execution dependency only). */
	public static void sampledToColor(VkCommandBuffer cb) {
		memory(cb, FRAGMENT | COMPUTE, 0, COLOR_OUT, COLOR_WRITE);
	}

	/** One-time UNDEFINED → GENERAL transition for a raw image, matching MC's model. */
	public static void initGeneral(VkCommandBuffer cb, long image, int aspect, int levels, int layers) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var b = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
					.srcStageMask(VK_PIPELINE_STAGE_2_NONE).srcAccessMask(0)
					.dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT).dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
					.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
			b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(levels).baseArrayLayer(0).layerCount(layers);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cb, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
		}
	}
}
