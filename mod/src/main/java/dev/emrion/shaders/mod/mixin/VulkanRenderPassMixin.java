package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: MC only knows set 0 (push descriptors); when one of our geometry pipelines is bound we bind our set 1 (ARCHITECTURE §5.3). */
@Mixin(VulkanRenderPass.class)
public class VulkanRenderPassMixin {
	@Shadow @Final private VkCommandBuffer commandBuffer;

	@Inject(method = "setPipeline", at = @At("TAIL"))
	private void emrion$bindSet1(BackendRenderPipeline pipeline, CallbackInfo ci) {
		GeometryPipelines.onSetPipeline(commandBuffer, (VulkanRenderPipeline) pipeline);
	}
}
