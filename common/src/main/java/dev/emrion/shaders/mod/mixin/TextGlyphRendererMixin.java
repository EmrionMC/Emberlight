package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Why: Iris draws block-entity text (sign glyphs) with gbuffers_block_translucent (TEXT_BE); glyph render types are picked here. */
@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
public class TextGlyphRendererMixin {
	@ModifyArg(method = "acceptRenderable", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private RenderType emrion$blockEntityText(RenderType type) {
		return EmrionShadersClient.active() && EntityIds.fromBlockEntity ? GeometryHooks.blockEntityCopy(type) : type;
	}
}
