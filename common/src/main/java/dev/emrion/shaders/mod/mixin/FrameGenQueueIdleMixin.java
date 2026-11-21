package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.emrion.shaders.mod.framegen.Presenter;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Why: see {@link FrameGenQueueMixin}; vkQueueWaitIdle needs the same external synchronisation. */
@Mixin(VulkanQueue.class)
abstract class FrameGenQueueIdleMixin {
	@WrapOperation(method = "waitIdle", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"))
	private int emrion$lockedWaitIdle(VkQueue queue, Operation<Integer> original) {
		if (!Presenter.sharedQueue()) return original.call(queue);
		Presenter.LOCK.lock();
		try {
			return original.call(queue);
		} finally {
			Presenter.LOCK.unlock();
		}
	}
}
