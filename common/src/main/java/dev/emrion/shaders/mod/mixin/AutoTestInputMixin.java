package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.platform.SDLEventHandler;
import dev.emrion.shaders.mod.autotest.AutoTest;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the gamescope test display is visible on the desktop and stray mouse/keys turned the camera mid-run. Drops every
 * real SDL input event during an autotest; AutoTest's scripted input calls KeyboardHandler/KeyMapping directly, not this.
 */
@Mixin(SDLEventHandler.class)
abstract class AutoTestInputMixin {
	private static boolean emrion$logged;

	@Inject(method = {"handleKeyEvent", "handleTextInputEvent", "handleTextEditingEvent", "handleMouseMotionEvent",
			"handleMouseButtonEvent", "handleMouseWheelEvent", "handleDropCompleteEvent"}, at = @At("HEAD"), cancellable = true)
	private void emrion$dropRealInput(SDL_Event event, CallbackInfo ci) {
		if (!AutoTest.blockRealInput()) return;
		if (!emrion$logged) {
			emrion$logged = true;
			dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Autotest: ignoring real mouse/keyboard input (-Demrion.autotest.allowInput=true to allow)");
		}
		ci.cancel();
	}
}
