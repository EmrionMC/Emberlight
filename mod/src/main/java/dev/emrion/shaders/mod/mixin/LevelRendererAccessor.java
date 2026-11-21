package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	/** Why: without a pack the level renders at the upscaler's render size, and the outline post chain runs at that size too. */
	@Accessor("entityOutlineTarget")
	com.mojang.blaze3d.pipeline.RenderTarget emrion$entityOutlineTarget();
}
