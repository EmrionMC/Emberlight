package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.emrion.shaders.mod.vertex.BlockEntityGeometryCache;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Why: static block-entity models (chests, shulkers) are rebuilt every frame in both passes; BlockEntityGeometryCache reuses them. */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererCacheMixin {
	@Unique private ModelFeatureRenderer.Submit<?> emrion$submit;
	@Unique private Object emrion$builder;
	@Unique private boolean emrion$hit;

	@WrapMethod(method = "prepareModel")
	private void emrion$cache(ModelFeatureRenderer.Submit<?> submit, Operation<Void> original) {
		emrion$submit = submit;
		emrion$hit = false;
		try {
			original.call(submit);
			if (!emrion$hit) BlockEntityGeometryCache.end(submit, emrion$builder);
		} finally {
			emrion$submit = null;
			emrion$builder = null;
		}
	}

	@ModifyExpressionValue(method = "prepareModel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer emrion$builder(VertexConsumer builder) {
		emrion$builder = builder;
		return builder;
	}

	@WrapOperation(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V"))
	private void emrion$setupAnim(Model<?> model, Object state, Operation<Void> original) {
		var s = emrion$submit;
		if ((Object) s instanceof EntityIds.Holder h
				&& BlockEntityGeometryCache.begin(s, emrion$builder, h.emrion$motion(), h.emrion$origin(0), h.emrion$origin(1), h.emrion$origin(2))) {
			emrion$hit = true;
			return;
		}
		original.call(model, state);
	}

	@WrapOperation(method = "prepareModel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
	private void emrion$render(Model<?> model, PoseStack pose, VertexConsumer buffer, int light, int overlay, int color, Operation<Void> original) {
		if (!emrion$hit) original.call(model, pose, buffer, light, overlay, color);
	}
}
