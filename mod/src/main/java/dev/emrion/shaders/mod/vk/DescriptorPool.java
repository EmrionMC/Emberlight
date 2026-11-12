package dev.emrion.shaders.mod.vk;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

/**
 * One pool per pack: the sets are pre-built at load (one per flip state, ARCHITECTURE §5.3) and never freed singly;
 * destroying the pool frees them all.
 */
public final class DescriptorPool {
	private final VulkanContext ctx;
	public final long handle;

	/** {@code typeCounts}: pairs of (VkDescriptorType, count). */
	public DescriptorPool(VulkanContext ctx, int maxSets, int... typeCounts) {
		this.ctx = ctx;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var sizes = VkDescriptorPoolSize.calloc(typeCounts.length / 2, stack);
			for (int i = 0; i < typeCounts.length; i += 2) sizes.get().type(typeCounts[i]).descriptorCount(typeCounts[i + 1]);
			sizes.flip();
			LongBuffer p = stack.mallocLong(1);
			VulkanContext.check(vkCreateDescriptorPool(ctx.vk, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(maxSets).pPoolSizes(sizes), null, p),
					"vkCreateDescriptorPool");
			handle = p.get(0);
		}
	}

	public long allocate(long setLayout) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			var info = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(handle).pSetLayouts(stack.longs(setLayout));
			VulkanContext.check(vkAllocateDescriptorSets(ctx.vk, info, p), "vkAllocateDescriptorSets");
			return p.get(0);
		}
	}

	public void destroyLater() {
		ctx.destroyLater(() -> vkDestroyDescriptorPool(ctx.vk, handle, null));
	}
}
