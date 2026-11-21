package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import dev.emrion.shaders.mod.pack.PackManager;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: after a resource reload all chunk workers can wait on the render thread, so MC's pipeline compile on that pool would
 * never finish; with a pack it runs on our own thread.
 */
@Mixin(PipelineCache.class)
public class PipelineCacheMixin {
	@ModifyArg(method = "get", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
			index = 2)
	private Executor emrion$ownExecutor(Executor original) {
		return PackManager.selected() != null && !PackManager.PIPELINE_COMPILER.isShutdown() ? PackManager.PIPELINE_COMPILER : original;
	}
}
