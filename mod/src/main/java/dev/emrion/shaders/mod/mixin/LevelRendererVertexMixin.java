package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.rt.RtEntities;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: section base vertices use the terrain format size (must be EMRION_BLOCK's), and only here can world ENTITY draws
 * switch to EMRION_ENTITY.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererVertexMixin {
	@ModifyExpressionValue(method = "extractSectionDrawGroups", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;getVertexFormatBinding(I)Lcom/mojang/renderpearl/api/vertex/VertexFormat;"))
	private VertexFormat emrion$terrainFormat(VertexFormat vanilla) {
		return EmrionVertexFormats.active() ? EmrionVertexFormats.TERRAIN : vanilla;
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame emrion$extendEntities(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
			Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		EntityIds.extending = EmrionVertexFormats.entitiesActive();
		RtEntities.begin(false, net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position());
		dev.emrion.shaders.mod.upscale.ObjectMotion.begin(true);
		try {
			return original.call(dispatcher, storage);
		} finally {
			EntityIds.extending = false;
			RtEntities.end();
			dev.emrion.shaders.mod.upscale.ObjectMotion.end();
		}
	}
}
