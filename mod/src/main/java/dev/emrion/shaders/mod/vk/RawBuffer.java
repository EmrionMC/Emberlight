package dev.emrion.shaders.mod.vk;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

/** VMA buffer (SSBOs, UBO rings). {@code mapped != 0} for host-visible, persistently mapped buffers. */
public final class RawBuffer {
	private final VulkanContext ctx;
	public final long handle;
	public final long size;
	public final long mapped;
	private final long allocation;

	public RawBuffer(VulkanContext ctx, long size, int vkUsage, boolean hostMapped) {
		this(ctx, size, vkUsage, hostMapped, "buffer");
	}

	public RawBuffer(VulkanContext ctx, long size, int vkUsage, boolean hostMapped, String label) {
		this.ctx = ctx;
		this.size = size;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(vkUsage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			var alloc = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
			if (hostMapped) alloc.flags(VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
			LongBuffer pBuffer = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
			VulkanContext.check(vmaCreateBuffer(ctx.vma, info, alloc, pBuffer, pAlloc, allocInfo), "vmaCreateBuffer");
			handle = pBuffer.get(0);
			allocation = pAlloc.get(0);
			dev.emrion.shaders.mod.debug.VramReport.name(ctx.vma, allocation, "raw " + label);
			mapped = hostMapped ? allocInfo.pMappedData() : 0L;
		}
	}

	public void destroyLater() {
		ctx.destroyLater(() -> vmaDestroyBuffer(ctx.vma, handle, allocation));
	}
}
