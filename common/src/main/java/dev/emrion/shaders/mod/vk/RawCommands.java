package dev.emrion.shaders.mod.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Our own command buffers, spliced in order into MC's frame submit. Must be called outside an open MC render pass.
 * The buffer comes from MC's per-frame pool and is recycled two submits later: never keep it.
 */
public final class RawCommands {
	private RawCommands() {}

	public static VkCommandBuffer begin(VulkanContext ctx) {
		return ctx.encoder.allocateAndBeginTransientCommandBuffer();
	}

	/** Ends with MC's global barrier (its ops assume every earlier op is fenced) and appends the buffer to the frame. */
	public static void submit(VulkanContext ctx, VkCommandBuffer cb) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cb, stack);
		}
		VulkanContext.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
		ctx.encoder.execute(cb);
	}
}
