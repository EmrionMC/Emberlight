package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanQueryPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Why: {@code vkQueryPool()} is protected; GpuTimer writes timestamps into our own command buffers, between raw passes. */
@Mixin(VulkanQueryPool.class)
public interface VulkanQueryPoolAccessor {
	@Invoker("vkQueryPool")
	long emrion$vkQueryPool();
}
