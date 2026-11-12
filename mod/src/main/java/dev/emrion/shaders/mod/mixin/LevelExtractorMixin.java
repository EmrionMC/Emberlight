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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: while a pack renders, the visible chunk sections get Sodium's cylindrical render-distance shape (HorizonCull), so
 * the pack's border fog sees the same terrain edge as under Iris. Only the main view's list is filtered (applyFrustum).
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;

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
