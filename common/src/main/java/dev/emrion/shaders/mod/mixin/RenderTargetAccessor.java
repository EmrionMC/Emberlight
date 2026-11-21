package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: upscaling without a pack renders the level into MC's main target with render-size textures swapped in (upscale/VanillaUpscale). */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
	@Accessor("colorTexture")
	void emrion$setColorTexture(GpuTexture t);

	@Accessor("colorTextureView")
	void emrion$setColorTextureView(GpuTextureView v);

	@Accessor("depthTexture")
	void emrion$setDepthTexture(GpuTexture t);

	@Accessor("depthTextureView")
	void emrion$setDepthTextureView(GpuTextureView v);
}
