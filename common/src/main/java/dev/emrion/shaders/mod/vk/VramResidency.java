package dev.emrion.shaders.mod.vk;

import static org.lwjgl.util.vma.Vma.vmaGetAllocationInfo;
import static org.lwjgl.util.vma.Vma.vmaGetMemoryTypeProperties;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationInfo;

/**
 * VMA silently puts an image in system memory when VRAM is full (a resource reload keeps old and new atlases alive), and a
 * pack target there makes every pass PCIe-bound. Pack images report here; PackManager recreates them once VRAM has room.
 */
public final class VramResidency {
	private static volatile String offDevice;

	private VramResidency() {}

	/** Records a pack image ({@code label} "emrion …") whose allocation is not in device-local memory. */
	public static void check(long vma, long allocation, String label) {
		if (allocation == 0 || !label.startsWith("emrion ")) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var info = VmaAllocationInfo.calloc(stack);
			vmaGetAllocationInfo(vma, allocation, info);
			var flags = stack.mallocInt(1);
			vmaGetMemoryTypeProperties(vma, info.memoryType(), flags);
			if ((flags.get(0) & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == 0) offDevice = label;
		}
	}

	/** The label of a pack image that landed in system memory since the last call, or null; clears it. */
	public static String takeOffDevice() {
		String l = offDevice;
		offDevice = null;
		return l;
	}
}
