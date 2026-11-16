package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import dev.emrion.shaders.mod.runtime.HorizonBox;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.SkyRenderer;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the sky phase: Iris's renderStage per sky part, sun/moon/stars toggles, sunPathRotation tilt and the horizon cone.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass emrion$skyPass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor,
			GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass ours = EmrionShadersClient.active()
				? GeometryHooks.openPhase("sky", GbufferPipelines.Context.WORLD, UniformSystem.Phase.WORLD, GeometryHooks.CUSTOM_SKY) : null;
		return ours != null ? ours : original.call(encoder, label, color, clearColor, depth, clearDepth);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void emrion$skyEnd(CallbackInfo ci) {
		if (EmrionShadersClient.active()) GeometryHooks.closePhase();
	}

	@Inject(method = "renderSkyDisc", at = @At("HEAD"))
	private void emrion$disc(RenderPass pass, Vector3fc skyColor, CallbackInfo ci) {
		GeometryHooks.stage(GeometryHooks.SKY);
		if (GeometryHooks.inPhase() && GeometryHooks.draws("sky")) HorizonBox.draw(pass); // Iris draws it before the sky
	}

	@Inject(method = "renderDarkDisc", at = @At("HEAD"))
	private void emrion$darkDisc(CallbackInfo ci) {
		GeometryHooks.stage(GeometryHooks.VOID);
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"))
	private void emrion$sunset(CallbackInfo ci) {
		GeometryHooks.stage(GeometryHooks.SUNSET);
	}

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void emrion$sun(CallbackInfo ci) {
		if (GeometryHooks.inPhase() && !GeometryHooks.draws("sun")) ci.cancel();
		else GeometryHooks.stage(GeometryHooks.SUN);
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void emrion$moon(CallbackInfo ci) {
		if (GeometryHooks.inPhase() && !GeometryHooks.draws("moon")) ci.cancel();
		else GeometryHooks.stage(GeometryHooks.MOON);
	}

	@Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
	private void emrion$stars(CallbackInfo ci) {
		if (GeometryHooks.inPhase() && !GeometryHooks.draws("stars")) ci.cancel();
		else GeometryHooks.stage(GeometryHooks.STARS);
	}

	@WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V", ordinal = 0))
	private void emrion$sunPath(PoseStack pose, Axis axis, float degrees, Operation<Void> original) {
		original.call(pose, axis, degrees);
		if (GeometryHooks.inPhase()) pose.rotateDegrees(Axis.ZP, GeometryHooks.sunPathRotation());
	}
}
