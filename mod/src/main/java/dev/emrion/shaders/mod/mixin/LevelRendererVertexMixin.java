package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.rt.RtEntities;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Why: only here can world ENTITY draws switch to EMRION_ENTITY. */
@Mixin(LevelRenderer.class)
public class LevelRendererVertexMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame emrion$extendEntities(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
			Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		EntityIds.extending = EmrionVertexFormats.entitiesActive();
		RtEntities.begin(false, net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position());
		dev.emrion.shaders.mod.upscale.ObjectMotion.begin(true);
		dev.emrion.shaders.mod.vertex.BlockEntityGeometryCache.newFrame();
		try {
			FeatureRenderDispatcher.PreparedFrame frame = original.call(dispatcher, storage);
			dev.emrion.shaders.mod.upscale.ObjectMotion.end(); // the RT-only player frame is never drawn: no motion slots
			dev.emrion.shaders.mod.runtime.GeometryHooks.captureRtPlayer((net.minecraft.client.renderer.LevelRenderer) (Object) this);
			return frame;
		} finally {
			EntityIds.extending = false;
			RtEntities.end();
			dev.emrion.shaders.mod.upscale.ObjectMotion.end();
		}
	}
}
