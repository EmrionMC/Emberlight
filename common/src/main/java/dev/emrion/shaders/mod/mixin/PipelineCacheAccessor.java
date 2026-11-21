package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: PackManager compiles missing vanilla pipelines without blocking, so it has to see what MC already has. */
@Mixin(PipelineCache.class)
public interface PipelineCacheAccessor {
	@Accessor("cache")
	Map<RenderPipeline, CompiledRenderPipeline> emrion$cache();

	@Accessor("shaderSource")
	ShaderSource emrion$shaderSource();
}
