package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.framegen.Presenter;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: without a queue of its own the frame-generation presenter submits and presents on MC's graphics
 * queue from another thread; Vulkan requires external synchronisation of the queue, so MC's submit takes the same lock then.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
abstract class FrameGenQueueMixin {
	@WrapOperation(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int emrion$lockedSubmit(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		if (!Presenter.sharedQueue()) return original.call(queue, submits, fence);
		Presenter.LOCK.lock();
		try {
			return original.call(queue, submits, fence);
		} finally {
			Presenter.LOCK.unlock();
		}
	}
}
