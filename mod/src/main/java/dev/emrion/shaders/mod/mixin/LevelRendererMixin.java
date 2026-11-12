package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import dev.emrion.shaders.mod.uniforms.UniformSystem;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: the level's frame structure lives here (ARCHITECTURE §6). {@code render} HEAD is Iris's beginLevelRendering
 * (uniforms, plan up to the first world phase); after the frame graph executed, the rest of the plan runs (composites,
 * final). In the main pass, MC's "Main" render pass becomes our opaque gbuffers pass, and the classic translucent part is
 * moved into a second pass after the solid hand and the deferred passes. The other wraps only set Iris's renderStage per
 * draw group, and skip vanilla clouds when the pack turns them off.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void emrion$levelStart(CallbackInfo ci) {
		if (EmrionShadersClient.active()) PackManager.onLevelStart(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V", shift = At.Shift.AFTER))
	private void emrion$levelEnd(CallbackInfo ci) {
		if (EmrionShadersClient.active()) PackManager.onLevelEnd();
	}

	@WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass emrion$opaquePass(CommandEncoder encoder, Supplier<String> label, GpuTextureView color, Optional<Vector4fc> clearColor,
			GpuTextureView depth, OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass ours = EmrionShadersClient.active()
				? GeometryHooks.openPhase("opaque", GbufferPipelines.Context.WORLD, UniformSystem.Phase.WORLD, GeometryHooks.TERRAIN_SOLID) : null;
		return ours != null ? ours : original.call(encoder, label, color, clearColor, depth, clearDepth);
	}

	@WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;executeClassicTransparency(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$splitMainPass(LevelRenderer self, ChunkSectionsToRender chunks, FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass,
			Operation<Void> original) {
		if (!GeometryHooks.splitMainPass(pass, p -> original.call(self, chunks, frame, p))) original.call(self, chunks, frame, pass);
	}

	// ---- renderStage per draw group (Iris WorldRenderingPhase) ----

	@WrapOperation(method = {"executeSolid", "executeClassicTransparency"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void emrion$terrainStage(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas,
			boolean wireframe, Operation<Void> original) {
		int stage = group == ChunkSectionLayerGroup.TRANSLUCENT ? GeometryHooks.TERRAIN_TRANSLUCENT : GeometryHooks.TERRAIN_SOLID;
		GeometryHooks.withStage(stage, () -> original.call(chunks, group, pass, sampler, atlas, wireframe));
	}

	@WrapOperation(method = "executeSolid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$solidFeatures(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, Operation<Void> original) {
		GeometryHooks.withStage(GeometryHooks.ENTITIES, () -> original.call(frame, pass));
	}

	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucent(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$translucentFeatures(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, Operation<Void> original) {
		GeometryHooks.withStage(GeometryHooks.ENTITIES, () -> original.call(frame, pass));
	}

	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$afterTerrainFeatures(FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, Operation<Void> original) {
		GeometryHooks.withStage(GeometryHooks.PARTICLES, () -> original.call(frame, pass));
	}

	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/CloudRenderer;render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$clouds(CloudRenderer clouds, CloudStatus status, RenderPass pass, Operation<Void> original) {
		if (GeometryHooks.inPhase() && !GeometryHooks.draws("clouds")) return;
		GeometryHooks.withStage(20, () -> original.call(clouds, status, pass));
	}

	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void emrion$weather(WeatherEffectRenderer weather, WeatherRenderState state, RenderPass pass, Operation<Void> original) {
		GeometryHooks.withStage(GeometryHooks.RAIN_SNOW, () -> original.call(weather, state, pass));
	}

	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldBorderRenderer;render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/phys/Vec3;D)V"))
	private void emrion$worldBorder(WorldBorderRenderer border, WorldBorderRenderState state, RenderPass pass, Vec3 camera, double distance,
			Operation<Void> original) {
		GeometryHooks.withStage(GeometryHooks.WORLD_BORDER, () -> original.call(border, state, pass, camera, distance));
	}
}
