package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.emrion.shaders.mod.autotest.AutoTest;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Why: during an autotest the game still counts as mouse-grabbed (no pause menu, same flow), but the OS cursor is not
 * locked into the test display, and real held keys are not re-read from SDL on grab.
 */
@Mixin(MouseHandler.class)
abstract class AutoTestGrabMixin {
	@Redirect(method = "grabMouse", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/InputConstants;grabMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"))
	private void emrion$noCursorLock(Window window, double x, double y) {
		if (!AutoTest.blockRealInput()) InputConstants.grabMouse(window, x, y);
	}

	@Redirect(method = "grabMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;setAll()V"))
	private void emrion$noRealKeyState() {
		if (!AutoTest.blockRealInput()) KeyMapping.setAll();
	}
}
