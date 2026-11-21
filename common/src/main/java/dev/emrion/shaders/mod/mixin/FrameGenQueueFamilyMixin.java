package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.emrion.shaders.mod.framegen.Presenter;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Why: a spare graphics queue lets the FG presenter present without blocking MC's submits; without one it shares MC's queue.
 */
@Mixin(VulkanBackend.class)
abstract class FrameGenQueueFamilyMixin {
	@Redirect(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;queueFamilyCreateInfoMap()Lit/unimi/dsi/fastutil/ints/Int2IntMap;"))
	private static Int2IntMap emrion$presentQueue(VulkanPhysicalDevice pd) {
		Int2IntMap map = pd.queueFamilyCreateInfoMap();
		var graphics = pd.graphicsQueueFamilyAndIndex();
		if (graphics == null) return map;
		int family = graphics.leftInt();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var count = stack.mallocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd.vkPhysicalDevice(), count, null);
			var props = VkQueueFamilyProperties.malloc(count.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd.vkPhysicalDevice(), count, props);
			int used = map.get(family);
			if (family >= props.capacity() || props.get(family).queueCount() <= used) return map;
			Int2IntMap more = new Int2IntArrayMap(map);
			more.put(family, used + 1);
			Presenter.queueFamily = family;
			Presenter.queueIndex = used;
			return more;
		}
	}
}
