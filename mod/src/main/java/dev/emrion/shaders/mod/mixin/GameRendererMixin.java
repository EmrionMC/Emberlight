package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.autotest.FrameTimeLogger;
import dev.emrion.shaders.mod.debug.DebugPost;
import dev.emrion.shaders.mod.debug.DebugTerrain;
import dev.emrion.shaders.mod.pack.PackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: {@code render} HEAD is the one per-frame point (frame timing, per-frame resets). {@code renderLevel} HEAD is the
 * start of world rendering (no MC render pass open): uniforms and the plan's pre-geometry commands. Just before
 * {@code render3dHud}, MC's depth still holds the world (render3dHud clears it for the hand): depthtex0 is imported there.
 * {@code renderLevel} TAIL is the end of world rendering (world + hand, before vanilla post effects and the GUI), where
 * deferred/composite/final run (ARCHITECTURE §6).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void emrion$frameStart(CallbackInfo ci) {
		FrameTimeLogger.INSTANCE.frame();
		if (EmrionShadersClient.active() && DebugTerrain.ENABLED) DebugTerrain.beginFrame();
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void emrion$levelStart(CallbackInfo ci) {
		if (EmrionShadersClient.active()) PackManager.onFrameStart(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void emrion$beforeHud(CallbackInfo ci) {
		if (EmrionShadersClient.active()) PackManager.onBeforeHud();
	}

	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void emrion$afterLevel(CallbackInfo ci) {
		if (!EmrionShadersClient.active()) return;
		if (DebugPost.ENABLED) DebugPost.onRenderLevelEnd();
		PackManager.onAfterLevel();
	}
}
