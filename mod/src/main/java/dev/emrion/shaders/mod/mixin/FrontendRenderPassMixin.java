package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: atlasSize needs the bound Sampler0, and passes with more than 8 targets switch attachment groups on pipeline set.
 */
@Mixin(FrontendRenderPass.class)
public class FrontendRenderPassMixin {
	@Shadow @Final private RenderPassBackend backend;
	@Shadow @Final @Mutable private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;

	@Inject(method = "setUniform(Ljava/lang/String;Ljava/lang/Object;)V", at = @At("HEAD"))
	private void emrion$sampler0(String name, @Nullable Object value, CallbackInfo ci) {
		if (name.equals("Sampler0") || name.equals("u_BlockTex")) GeometryPipelines.sampler0(value); // u_BlockTex: Sodium terrain
	}

	@Inject(method = "setPipeline", at = @At("HEAD"))
	private void emrion$attachmentGroup(CompiledRenderPipeline pipeline, CallbackInfo ci) {
		if (!GeometryHooks.attachmentGroups) return;
		var groupAttachments = GeometryHooks.switchGroup(((VulkanRenderPassAccessor) backend).emrion$commandBuffer(), pipeline);
		if (groupAttachments != null) colorAttachments = List.copyOf(groupAttachments);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void emrion$terrainStats(CallbackInfo ci) {
		if (dev.emrion.shaders.mod.debug.TerrainStats.ENABLED) dev.emrion.shaders.mod.debug.TerrainStats.passEnd();
	}
}
