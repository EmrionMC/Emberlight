package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: the adaptive terrain prepass records its occlusion queries into the pass's command buffer (SodiumWorldRendererMixin). */
@Mixin(FrontendRenderPass.class)
public interface FrontendRenderPassAccessor {
	@Accessor("backend")
	RenderPassBackend emrion$backend();
}
