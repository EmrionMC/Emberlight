package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vertex.DebugVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: dev-only vertex-data view (-Demrion.debug.vertex): terrain pipelines draw EMRION_BLOCK with a debug shader. */
@Mixin(RenderSystem.class)
public class VertexDebugMixin {
	@Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true)
	private static void emrion$debugVertex(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		if (!DebugVertex.ENABLED || !EmrionShadersClient.active()) return;
		CompiledRenderPipeline ours = DebugVertex.replacement(pipeline);
		if (ours != null) cir.setReturnValue(ours);
	}
}
