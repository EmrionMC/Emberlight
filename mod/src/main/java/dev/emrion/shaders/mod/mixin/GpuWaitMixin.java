package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.emrion.shaders.mod.debug.HitchLog;
import dev.emrion.shaders.mod.debug.Profiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: the profiler needs the render-thread time spent blocked on the GPU, to tell GPU-bound from CPU-bound frames. MC's
 * submit waits for the submit two frames back here (the frames-in-flight limit).
 */
@Mixin(VulkanCommandEncoder.class)
abstract class GpuWaitMixin {
	@Shadow @Final private long submitSemaphore;

	@WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
	private boolean emrion$timeWait(VulkanCommandEncoder self, long index, long timeout, Operation<Boolean> original) {
		dev.emrion.shaders.mod.framegen.Reflex.submitted(submitSemaphore, index + 1); // the submit just made signals index + 1
		long t = System.nanoTime();
		boolean r = original.call(self, index, timeout);
		HitchLog.add(HitchLog.GPU_WAIT, t);
		if (Profiler.enabled()) Profiler.cpuEnd(Profiler.WAIT, t);
		return r;
	}
}
