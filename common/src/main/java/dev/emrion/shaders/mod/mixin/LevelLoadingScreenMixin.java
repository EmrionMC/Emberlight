package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.gui.LoadingHud;
import dev.emrion.shaders.mod.pack.PackManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: after a portal or world join the screen stays until the pack renders the new dimension, so no vanilla frames show
 * (PackManager.switching, at most 30 s), with the pack's loading panel on it. Only the screen waits: the player-loaded
 * signal (ClientPacketListener's own isLevelReady check) goes to the server on vanilla timing.
 */
@Mixin(LevelLoadingScreen.class)
abstract class LevelLoadingScreenMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;isLevelReady()Z"))
	private boolean emrion$waitForPack(LevelLoadTracker tracker, Operation<Boolean> original) {
		return original.call(tracker) && !PackManager.switching();
	}

	/** The HUD is not drawn under a screen. */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void emrion$loadingPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
		LoadingHud.draw(graphics);
	}
}
