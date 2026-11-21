package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Why: the first-person hand's ENTITY draws (gbuffers_hand) are written here; same switch as the world feature frame. */
@Mixin(GameRenderer.class)
public class GameRendererVertexMixin {
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame emrion$extendEntities(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
			Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		EntityIds.extending = EmrionVertexFormats.entitiesActive();
		dev.emrion.shaders.mod.upscale.ObjectMotion.begin(false);
		try {
			return original.call(dispatcher, storage);
		} finally {
			EntityIds.extending = false;
			dev.emrion.shaders.mod.upscale.ObjectMotion.end();
		}
	}
}
