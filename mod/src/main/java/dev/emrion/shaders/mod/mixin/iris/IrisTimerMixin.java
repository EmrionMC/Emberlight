package dev.emrion.shaders.mod.mixin.iris;

import dev.emrion.shaders.mod.uniforms.IrisTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: the Iris reference run pins frameTimeCounter (and with emrion.autotest.frameTime, frameTime) like ours, so animations and
 * smoothing match; with a pinned frameTime its clock steps by exactly that per frame and honours the autotest rewind (turnshot), so
 * frame sequences in motion line up frame by frame with ours.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public class IrisTimerMixin {
	@Shadow private float frameTimeCounter;
	@Shadow private float lastFrameTime;

	@Inject(method = "beginFrame", at = @At("TAIL"), require = 0)
	private void emrion$step(long frameStartTime, CallbackInfo ci) {
		if (IrisTimer.FIXED_FRAME_TIME >= 0) {
			frameTimeCounter += IrisTimer.FIXED_FRAME_TIME - lastFrameTime;
			lastFrameTime = IrisTimer.FIXED_FRAME_TIME;
		}
		if (IrisTimer.rewindTo >= 0) {
			frameTimeCounter = IrisTimer.rewindTo;
			IrisTimer.rewindTo = -1;
			try { // Iris is not on our compile classpath
				Object counter = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms").getField("COUNTER").get(null);
				counter.getClass().getMethod("reset").invoke(counter);
			} catch (ReflectiveOperationException ignored) { }
		}
	}

	@Inject(method = "getFrameTimeCounter", at = @At("HEAD"), cancellable = true, require = 0)
	private void emrion$fixed(CallbackInfoReturnable<Float> cir) {
		if (IrisTimer.FIXED >= 0) cir.setReturnValue(IrisTimer.FIXED);
	}
}
