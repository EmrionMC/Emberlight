package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: with {@code separateAo=true} Iris (via Sodium's XHFPTerrainVertex) writes the unshaded tint into the chunk vertex
 * colour and AO × shade into its alpha; MC bakes {@code gray(ao)} × shade into rgb. Here the per-vertex brightness becomes
 * white with that alpha instead (the tint is multiplied in afterwards), for chunk meshes only.
 */
@Mixin(BlockModelLighter.class)
public class BlockModelLighterMixin {
	@WrapOperation(method = {"prepareQuadAmbientOcclusion", "prepareQuadFlat"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;gray(F)I"))
	private int emrion$aoInAlpha(float brightness, Operation<Integer> original) {
		return separate() ? ARGB.color(Math.round(Math.clamp(brightness, 0.0f, 1.0f) * 255.0f), 0xFFFFFF) : original.call(brightness);
	}

	@WrapOperation(method = "prepareQuadAmbientOcclusion", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/QuadInstance;scaleColor(F)V"))
	private void emrion$shadeInAlpha(QuadInstance quad, float shade, Operation<Void> original) {
		if (!separate()) {
			original.call(quad, shade);
			return;
		}
		for (int i = 0; i < 4; i++) quad.setColor(i, ARGB.multiplyAlpha(quad.getColor(i), shade));
	}

	private static boolean separate() {
		return EmrionVertexFormats.separateAo() && BlockContext.get().inSection;
	}
}
