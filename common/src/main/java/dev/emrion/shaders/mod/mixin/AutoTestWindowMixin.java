package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.emrion.shaders.mod.autotest.AutoTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: during an autotest, clicking another desktop window must not unfocus (and pause) the game, and a resize (the
 *  "size" step) must not re-lock the cursor that AutoTestGrabMixin leaves free. */
@Mixin(Window.class)
abstract class AutoTestWindowMixin {
	@Inject(method = "onFocus", at = @At("HEAD"), cancellable = true)
	private void emrion$keepFocus(boolean focused, CallbackInfo ci) {
		if (AutoTest.blockRealInput()) ci.cancel();
	}

	@Redirect(method = "onResize", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;grabMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"))
	private void emrion$noCursorLock(Window window, double x, double y) {
		if (!AutoTest.blockRealInput()) InputConstants.grabMouse(window, x, y);
	}
}
