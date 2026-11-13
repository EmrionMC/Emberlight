package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.emrion.shaders.mod.rt.RtCapability;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: records whether MC enabled the optional "Emrion RT" set, and only then lets VMA add VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT
 * (vkGetBufferDeviceAddress on a buffer is invalid without it). MC creates VMA with no flags.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendRtMixin {
	@Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;", at = @At("HEAD"))
	private static void emrion$rtEnabled(FeatureSet featureSet, VulkanPhysicalDevice physicalDevice, CallbackInfoReturnable<?> cir) {
		RtCapability.enabled = RtCapability.REQUESTED && featureSet.contains(RtCapability.FEATURE_SET);
	}

	@ModifyArg(method = "createVma", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"), index = 0)
	private static VmaAllocatorCreateInfo emrion$bdaFlag(VmaAllocatorCreateInfo info) {
		return RtCapability.enabled ? info.flags(info.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT) : info;
	}
}
