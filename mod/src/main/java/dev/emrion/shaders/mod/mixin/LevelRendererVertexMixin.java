package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: (1) section draws compute their base vertex from the terrain pipeline's format size, which must be EMRION_BLOCK's
 * while the meshes are extended; (2) entity vertices are written while the world feature frame is prepared, the only point
 * where ENTITY draws of the world (not the GUI) can switch to EMRION_ENTITY.
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
		try {
			return original.call(dispatcher, storage);
		} finally {
			EntityIds.extending = false;
		}
	}
}
