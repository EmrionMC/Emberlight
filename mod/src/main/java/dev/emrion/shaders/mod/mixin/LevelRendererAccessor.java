package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: the shadow pass prepares its terrain draws on the same path (multidraw-indirect or not) as MC's main pass this frame. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Accessor("usingMultiDrawIndirectForTerrain")
	boolean emrion$usingMultiDrawIndirect();
}
