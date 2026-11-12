package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.DebugTerrain;
import dev.emrion.shaders.mod.debug.PipelineLog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: every MC draw picks its pipeline here; this is the single swap point for our geometry pipelines (ARCHITECTURE §6). */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {
	@Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true)
	private static void emrion$swap(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		if (!EmrionShadersClient.active() || !DebugTerrain.ENABLED) return;
		CompiledRenderPipeline ours = DebugTerrain.replacement(pipeline);
		if (ours != null) cir.setReturnValue(ours);
	}

	@Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
	private static void emrion$log(RenderPipeline pipeline, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
		if (PipelineLog.ENABLED && EmrionShadersClient.active()) PipelineLog.seen(pipeline, cir.getReturnValue());
	}
}
