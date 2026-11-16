package dev.emrion.shaders.mod.mixin.iris;

import dev.emrion.shaders.mod.uniforms.IrisTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: the Iris reference run pins frameTimeCounter (and with emrion.autotest.frameTime, frameTime) like ours, so animations and smoothing match. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public class IrisTimerMixin {
	@Inject(method = "getFrameTimeCounter", at = @At("HEAD"), cancellable = true, require = 0)
	private void emrion$fixed(CallbackInfoReturnable<Float> cir) {
		if (IrisTimer.FIXED >= 0) cir.setReturnValue(IrisTimer.FIXED);
	}

	@Inject(method = "getLastFrameTime", at = @At("HEAD"), cancellable = true, require = 0)
	private void emrion$fixedFrameTime(CallbackInfoReturnable<Float> cir) {
		if (IrisTimer.FIXED_FRAME_TIME >= 0) cir.setReturnValue(IrisTimer.FIXED_FRAME_TIME);
	}
}
