package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: switching a gbuffers pass's attachment group records into the pass's command buffer (FrontendRenderPassMixin). */
@Mixin(VulkanRenderPass.class)
public interface VulkanRenderPassAccessor {
	@Accessor("commandBuffer")
	VkCommandBuffer emrion$commandBuffer();
}
