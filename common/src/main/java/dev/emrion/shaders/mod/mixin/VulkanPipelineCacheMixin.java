package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.emrion.shaders.mod.vk.VulkanContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Why: MC creates its pipelines without a VkPipelineCache; ours is persisted, so warm starts skip the driver compile. */
@Mixin(VulkanRenderPipeline.class)
public class VulkanPipelineCacheMixin {
	@ModifyArg(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
			index = 1)
	private static long emrion$pipelineCache(long cache) {
		return cache != 0 ? cache : VulkanContext.pipelineCacheHandle();
	}
}
