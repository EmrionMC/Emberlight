package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.autotest.FrameTimeLogger;
import dev.emrion.shaders.mod.debug.DebugPost;
import dev.emrion.shaders.mod.debug.HitchLog;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: per-frame points. With a pack: classic transparency is forced (OIT breaks Iris's phases), and the hand is drawn
 * inside the level by {@link GeometryHooks} (Iris order), so MC's own hand pass is skipped.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void emrion$frameStart(CallbackInfo ci) {
		FrameTimeLogger.INSTANCE.frame();
		dev.emrion.shaders.mod.autotest.AutoTest.onFrame();
		dev.emrion.shaders.mod.upscale.VanillaUpscale.frameStart();
		if (!EmrionShadersClient.active()) {
			if (FrameTimeLogger.INSTANCE.recording() && dev.emrion.shaders.mod.autotest.AutoTest.PROFILE) dev.emrion.shaders.mod.autotest.GlGpuTimer.frameStart();
			return;
		}
		HitchLog.frameStart();
		Profiler.frameStart();
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void emrion$frameEnd(CallbackInfo ci) {
		if (!EmrionShadersClient.active()) {
			if (FrameTimeLogger.INSTANCE.recording() && dev.emrion.shaders.mod.autotest.AutoTest.PROFILE) dev.emrion.shaders.mod.autotest.GlGpuTimer.frameEnd();
			return;
		}
		HitchLog.frameEnd();
		GeometryHooks.endFrame();
		Profiler.frameEnd();
	}

	/** Frame generation: MC's main target after the level and vanilla post effects, before the GUI = the HUD-less image. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void emrion$hudless(CallbackInfo ci) {
		boolean fg = dev.emrion.shaders.mod.framegen.FrameGen.wantsHudless();
		if (!fg && !dev.emrion.shaders.mod.hdr.Hdr.active()) return;
		var main = ((GameRenderer) (Object) this).mainRenderTarget();
		if (main.getColorTexture() == null) return;
		long image = ((com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture) main.getColorTexture()).vkImage();
		if (fg) dev.emrion.shaders.mod.framegen.FrameGen.beforeGui(image, main.width, main.height);
		dev.emrion.shaders.mod.hdr.Hdr.beforeGui(image, main.width, main.height); // HDR: the GUI mask (GUI stays at paper white)
	}

	/** Upscaling without a pack: the level is done, hand and screen effects come next at display size. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void emrion$vanillaUpscale(CallbackInfo ci) {
		if (EmrionShadersClient.active()) dev.emrion.shaders.mod.upscale.VanillaUpscale.endLevel();
	}

	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void emrion$afterLevel(CallbackInfo ci) {
		if (EmrionShadersClient.active()) dev.emrion.shaders.mod.upscale.VanillaUpscale.afterHand();
		if (EmrionShadersClient.active() && DebugPost.ENABLED) DebugPost.onRenderLevelEnd();
		if (EmrionShadersClient.active() && dev.emrion.shaders.mod.rt.RtCapability.DEBUG) dev.emrion.shaders.mod.rt.RtDebugView.onRenderLevelEnd();
	}

	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void emrion$classicTransparency(CallbackInfoReturnable<Boolean> cir) {
		if (EmrionShadersClient.active() && GeometryHooks.packRendering()) cir.setReturnValue(false);
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
	private void emrion$skipVanillaHand(GameRenderer self, CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, Operation<Void> original) {
		if (EmrionShadersClient.active() && GeometryHooks.packRendering()) return;
		original.call(self, camera, player, depth);
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame emrion$handDispatcher(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage,
			Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		FeatureRenderDispatcher ours = EmrionShadersClient.active() ? GeometryHooks.handDispatcher() : null;
		return original.call(ours != null ? ours : dispatcher, storage);
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass emrion$handPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor,
			GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass ours = EmrionShadersClient.active() ? GeometryHooks.handPass() : null;
		return ours != null ? ours : original.call(encoder, label, color, clearColor, depth, clearDepth);
	}
}
