package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.emrion.shaders.mod.debug.Profiler;
import java.nio.IntBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Why: swapchain acquire and present can block on the GPU / compositor; the profiler counts that as waiting (GpuWaitMixin). */
@Mixin(VulkanGpuSurface.class)
abstract class SurfaceWaitMixin {
	@WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int emrion$timeAcquire(VkDevice device, long swapchain, long timeout, long semaphore, long fence, IntBuffer index, Operation<Integer> original) {
		long t = Profiler.cpuStart();
		int r = original.call(device, swapchain, timeout, semaphore, fence, index);
		Profiler.cpuEnd(Profiler.WAIT, t);
		return r;
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int emrion$timePresent(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		long t = Profiler.cpuStart();
		int r = original.call(queue, info);
		Profiler.cpuEnd(Profiler.WAIT, t);
		return r;
	}
}
