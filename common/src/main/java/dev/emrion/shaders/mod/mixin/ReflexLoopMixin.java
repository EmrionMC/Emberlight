package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.framegen.Reflex;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: Reflex (VK_NV_low_latency2) sleeps before the frame samples input (pollEvents starts each pass of MC's game loop)
 * and needs markers at the end of simulation, around render submission and around present.
 */
@Mixin(Minecraft.class)
abstract class ReflexLoopMixin {
	@Inject(method = "run", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"))
	private void emrion$reflexSleep(CallbackInfo ci) {
		Reflex.beginFrame(((Minecraft) (Object) this).gameRenderer.gameRenderState().framerateLimit);
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
	private void emrion$reflexRender(CallbackInfo ci) {
		Reflex.beforeRender();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
	private void emrion$reflexPresentStart(CallbackInfo ci) {
		Reflex.beforePresent();
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void emrion$reflexPresentEnd(CallbackInfo ci) {
		Reflex.afterPresent();
	}

	/** The driver's sleep enforces the frame cap before input instead (Reflex.beginFrame). */
	@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
	private void emrion$reflexCap(int limit, Operation<Void> original) {
		if (!Reflex.capsFrameRate()) original.call(limit);
	}
}
