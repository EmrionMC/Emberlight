package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.pack.PackManager;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	/** Why: RT traces only the entities MC extracts; without a shadow pass nearby ones out of view must be extracted too (RtEntities.casterFrustum). */
	@ModifyArg(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
			index = 1)
	private net.minecraft.client.renderer.culling.Frustum emrion$rtCasters(net.minecraft.client.renderer.culling.Frustum frustum) {
		return dev.emrion.shaders.mod.rt.RtEntities.casterFrustum(frustum, PackManager.running());
	}
}
