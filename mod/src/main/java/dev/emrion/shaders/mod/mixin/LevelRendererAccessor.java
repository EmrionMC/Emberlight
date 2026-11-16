package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Why: the shadow pass prepares its terrain draws on the same path (multidraw-indirect or not) as MC's main pass this frame. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	@Accessor("usingMultiDrawIndirectForTerrain")
	boolean emrion$usingMultiDrawIndirect();

	/** Why: the shadow pass keeps only sections the occlusion flood fill reaches (Sodium's wide search, Iris's shadow set). */
	@Accessor("sectionOcclusionGraph")
	net.minecraft.client.renderer.SectionOcclusionGraph emrion$sectionOcclusionGraph();

	/** Why: without a pack the level renders at the upscaler's render size, and the outline post chain runs at that size too. */
	@Accessor("entityOutlineTarget")
	com.mojang.blaze3d.pipeline.RenderTarget emrion$entityOutlineTarget();
}
