package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: the per-layer vertex uber buffer aligns allocations to the vanilla vertex size (28 B) once, at construction, and draws
 * use offset / stride as base vertex. Aligning to lcm(28, 56) keeps base vertices exact in both formats without recreating it.
 */
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherMixin {
	@ModifyExpressionValue(method = "lambda$new$0", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/vertex/VertexFormat;getVertexSize()I"))
	private int emrion$alignToBothFormats(int vanillaVertexSize) {
		int ours = EmrionVertexFormats.TERRAIN.getVertexSize();
		int a = vanillaVertexSize, b = ours;
		while (b != 0) { int r = a % b; a = b; b = r; }
		return vanillaVertexSize / a * ours;
	}
}
