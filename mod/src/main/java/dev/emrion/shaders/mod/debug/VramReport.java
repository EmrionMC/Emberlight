package dev.emrion.shaders.mod.debug;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;

/**
 * VRAM audit (-Demrion.debug.vram=true): every VMA allocation (MC's textures/buffers and ours) carries its label as the
 * VMA name, and the autotest {@code mem} step writes VMA's detailed JSON map; {@code mod/tools/vram_report.py} sums it by owner.
 */
public final class VramReport {
	public static final boolean ENABLED = Boolean.getBoolean("emrion.debug.vram");
	/** VK_EXT_memory_budget, where the driver has it: {@link #freeDeviceLocalMiB} needs it enabled on the device. */
	public static final FeatureSet FEATURE_SET = new FeatureSet("Emberlight memory budget", Set.of("VK_EXT_memory_budget"), Set.of());
	public static volatile boolean budgetSupported;
	private static int dumps;

	private VramReport() {}

	public static void name(long vma, long allocation, String name) {
		if (ENABLED) Vma.vmaSetAllocationName(vma, allocation, name);
	}

	/** The driver's own per-heap usage of this process (VK_EXT_memory_budget, a physical-device query), in MiB; "" if unsupported. */
	public static String driverUsage(VulkanContext ctx) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
			var props = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget);
			VK11.vkGetPhysicalDeviceMemoryProperties2(ctx.vk.getPhysicalDevice(), props);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < props.memoryProperties().memoryHeapCount(); i++)
				sb.append(String.format(java.util.Locale.ROOT, "heap%d driver usage %.1f MiB of budget %.1f MiB; ", i, budget.heapUsage(i) / 1048576.0, budget.heapBudget(i) / 1048576.0));
			return sb.toString();
		}
	}

	/** Free MiB of the largest device-local heap (driver budget minus this process's usage), or -1 without VK_EXT_memory_budget. */
	public static long freeDeviceLocalMiB(VulkanContext ctx) {
		if (!budgetSupported) return -1;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
			var props = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget);
			VK11.vkGetPhysicalDeviceMemoryProperties2(ctx.vk.getPhysicalDevice(), props);
			var mp = props.memoryProperties();
			int best = -1;
			for (int i = 0; i < mp.memoryHeapCount(); i++)
				if ((mp.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 && (best < 0 || mp.memoryHeaps(i).size() > mp.memoryHeaps(best).size())) best = i;
			return best < 0 ? -1 : Math.max(0, budget.heapBudget(best) - budget.heapUsage(best)) / 1048576;
		}
	}

	/** Writes VMA's detailed stats JSON next to the game dir; returns the file. */
	public static Path dump(VulkanContext ctx, Path dir) throws IOException {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer p = stack.mallocPointer(1);
			Vma.vmaBuildStatsString(ctx.vma, p, true);
			String json = MemoryUtil.memUTF8(p.get(0));
			Vma.nvmaFreeStatsString(ctx.vma, p.get(0));
			Path out = dir.resolve("emrion-vma-" + (dumps++) + ".json");
			Files.writeString(out, json);
			return out;
		}
	}
}
