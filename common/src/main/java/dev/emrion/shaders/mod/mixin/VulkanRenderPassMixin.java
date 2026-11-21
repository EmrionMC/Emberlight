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
import org.jspecify.annotations.Nullable;

/** Why: MC only knows set 0 (push descriptors); when one of our geometry pipelines is bound we bind our set 1. */
@Mixin(VulkanRenderPass.class)
public class VulkanRenderPassMixin {
	@Shadow @Final private VkCommandBuffer commandBuffer;
	@Shadow protected @Nullable VulkanRenderPipeline pipeline;

	/** Why: Sodium's push constants would overwrite our EmrionDraw at offset 0. */
	@Inject(method = "pushConstants", at = @At("HEAD"), cancellable = true)
	private void emrion$sodiumPush(java.nio.ByteBuffer value, CallbackInfo ci) {
		if (GeometryPipelines.pushConstants(commandBuffer, pipeline, value)) ci.cancel();
	}

	@Inject(method = "setPipeline", at = @At("RETURN")) // RETURN: NeoForge adds an early return (stencil pipelines)
	private void emrion$bindSet1(BackendRenderPipeline pipeline, CallbackInfo ci) {
		GeometryPipelines.onSetPipeline(commandBuffer, (VulkanRenderPipeline) pipeline);
	}
}
