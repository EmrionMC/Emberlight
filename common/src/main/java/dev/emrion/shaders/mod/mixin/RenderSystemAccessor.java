package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: MC's current pipeline cache (replaced on every resource reload). */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
	@Accessor("currentPipelineCache")
	static @Nullable PipelineCache emrion$currentPipelineCache() {
		throw new AssertionError();
	}
}
