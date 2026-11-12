package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.autotest.FrameTimeLogger;
import dev.emrion.shaders.mod.debug.DebugPost;
import dev.emrion.shaders.mod.debug.DebugTerrain;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: {@code render} HEAD is the one per-frame point (frame timing, per-frame resets); {@code renderLevel} TAIL is the end
 * of world rendering (world + hand, before vanilla post effects and the GUI) is where composite/final run (ARCHITECTURE §6). */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void emrion$frameStart(CallbackInfo ci) {
		FrameTimeLogger.INSTANCE.frame();
		if (EmrionShadersClient.active() && DebugTerrain.ENABLED) DebugTerrain.beginFrame();
	}

	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void emrion$afterLevel(CallbackInfo ci) {
		if (EmrionShadersClient.active() && DebugPost.ENABLED) DebugPost.onRenderLevelEnd();
	}
}
