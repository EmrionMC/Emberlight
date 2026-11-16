package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: surfaces only report HDR colour spaces with the instance extension VK_EXT_swapchain_colorspace (Hdr). Enabled when
 * the loader has it; it only adds enum values, MC's SDR format choice is unchanged.
 */
@Mixin(VulkanInstance.class)
abstract class HdrInstanceMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"), index = 3)
	private Set<String> emrion$swapchainColorspace(int verbosity, boolean labels, Set<String> available, Set<String> enabled) {
		if (available.contains("VK_EXT_swapchain_colorspace")) enabled.add("VK_EXT_swapchain_colorspace");
		return enabled;
	}
}
