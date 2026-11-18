package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.runtime.HorizonCull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: while a pack renders, the visible chunk sections get Sodium's cylindrical render-distance shape (HorizonCull), so
 * the pack's border fog sees the same terrain edge as under Iris. Only the main view's list is filtered (applyFrustum).
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private net.minecraft.client.multiplayer.ClientLevel level;
	@Shadow private net.minecraft.client.SectionUpdateTracker sectionUpdateTracker;
	@Shadow @Final private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;

	/** Why: MC compiles only sections its visibility graph reached; the shadow pass and RT need the rest (ForcedSections). */
	@Inject(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
	private void emrion$forcedSections(CallbackInfo ci) {
		var area = levelRenderer.viewArea();
		if (sectionUpdateTracker != null && level != null && area != null) dev.emrion.shaders.mod.runtime.ForcedSections.schedule(sectionUpdateTracker, level, area, levelRenderState);
		else dev.emrion.shaders.mod.runtime.ForcedSections.clear();
	}

	/** Why: RT traces only the entities MC extracts; without a shadow pass nearby ones out of view must be extracted too (RtEntities.casterFrustum). */
	@ModifyArg(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"),
			index = 1)
	private net.minecraft.client.renderer.culling.Frustum emrion$rtCasters(net.minecraft.client.renderer.culling.Frustum frustum) {
		return dev.emrion.shaders.mod.rt.RtEntities.casterFrustum(frustum, PackManager.running());
	}

	@Inject(method = "applyFrustum", at = @At("TAIL"))
	private void emrion$sodiumDistance(CallbackInfo ci) {
		if (!PackManager.running()) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		float distance = mc.options.getEffectiveRenderDistance() * 16.0f;
		levelRenderer.visibleSections().removeIf(s -> {
			BlockPos o = s.getRenderOrigin();
			return !HorizonCull.inRenderDistance(o.getX(), o.getY(), o.getZ(), cam.x, cam.y, cam.z, distance);
		});
	}
}
