package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.emrion.shaders.mod.framegen.Reflex;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: Reflex needs the swapchain created with low latency mode allowed, and the frame's present ID on MC's present. */
@Mixin(VulkanGpuSurface.class)
abstract class ReflexSurfaceMixin {
	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int emrion$latencySwapchain(VkDevice device, VkSwapchainCreateInfoKHR info, VkAllocationCallbacks alloc, LongBuffer out, Operation<Integer> original) {
		Reflex.chainSwapchain(info);
		int r = original.call(device, info, alloc, out);
		if (r == 0) Reflex.swapchainCreated(device, out.get(0));
		return r;
	}

	@Inject(method = {"configure", "close"}, at = @At("HEAD"))
	private void emrion$latencySwapchainGone(CallbackInfo ci) {
		Reflex.swapchainGone();
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int emrion$latencyPresent(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		Reflex.chainPresent(info);
		int r = original.call(queue, info);
		Reflex.presentedCurrent();
		return r;
	}
}
