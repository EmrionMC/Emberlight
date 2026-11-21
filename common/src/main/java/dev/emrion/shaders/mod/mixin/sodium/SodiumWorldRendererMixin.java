package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumShadow;
import dev.emrion.shaders.mod.pipeline.GbufferPipelines;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: the shadow pass's per-region caches (compat.sodium.SodiumShadow) must not outlive Sodium's regions, their block entities and world. */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin {
	@Inject(method = {"unloadLevel", "initRenderer"}, at = @At("HEAD"))
	private void emrion$resetShadow(CallbackInfo ci) {
		SodiumShadow.reset();
	}

	@org.spongepowered.asm.mixin.Shadow
	public abstract void renderLayer(net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices,
			net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass pass, double x, double y, double z,
			net.caffeinemc.mods.sodium.client.util.FogParameters fogParameters, com.mojang.renderpearl.api.textures.GpuSampler terrainSampler,
			com.mojang.renderpearl.api.commands.RenderPass renderPass, net.minecraft.client.renderer.oit.@org.jspecify.annotations.Nullable OitStage stage);

	@org.spongepowered.asm.mixin.Shadow
	private net.caffeinemc.mods.sodium.client.util.FogParameters lastFogParameters;

	/**
	 * Why: with RT the pack's opaque terrain shader traces rays per fragment, and in foliage most fragments are drawn over later (3.5
	 * fragments per pixel in a forest). With the converter's prepass patch both opaque layers first draw depth only (the shader stops
	 * after its alpha test), then shade without depth writes: only the fragment that ends up visible passes. Same image.
	 */
	@Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true)
	private void emrion$prepass(com.mojang.renderpearl.api.commands.RenderPass pass, net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup group,
			net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices matrices, double x, double y, double z,
			com.mojang.renderpearl.api.textures.GpuSampler terrainSampler, net.minecraft.client.renderer.oit.@org.jspecify.annotations.Nullable OitStage stage,
			CallbackInfo ci) {
		var e = dev.emrion.shaders.mod.pack.PackManager.levelExecutor();
		if (group != net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup.OPAQUE || e == null || e.context() != GbufferPipelines.Context.WORLD
				|| !e.pipelines().gbuffers.prepass) return;
		org.lwjgl.vulkan.VkCommandBuffer cb = pass instanceof dev.emrion.shaders.mod.mixin.FrontendRenderPassAccessor f
				&& f.emrion$backend() instanceof dev.emrion.shaders.mod.mixin.VulkanRenderPassAccessor v ? v.emrion$commandBuffer() : null;
		if (!dev.emrion.shaders.mod.pipeline.TerrainPrepass.begin(cb)) return; // little overdraw in view: Sodium's plain draws
		ci.cancel(); // Sodium's drawChunkLayer(OPAQUE) is exactly these two renderLayer calls
		try {
			GbufferPipelines.terrainMode = GbufferPipelines.Context.TERRAIN_PREPASS;
			renderLayer(matrices, DefaultTerrainRenderPasses.SOLID, x, y, z, lastFogParameters, terrainSampler, pass, stage);
			renderLayer(matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z, lastFogParameters, terrainSampler, pass, stage);
			dev.emrion.shaders.mod.pipeline.TerrainPrepass.main();
			GbufferPipelines.terrainMode = GbufferPipelines.Context.TERRAIN_MAIN;
			renderLayer(matrices, DefaultTerrainRenderPasses.SOLID, x, y, z, lastFogParameters, terrainSampler, pass, stage);
			renderLayer(matrices, DefaultTerrainRenderPasses.CUTOUT, x, y, z, lastFogParameters, terrainSampler, pass, stage);
		} finally {
			GbufferPipelines.terrainMode = null;
			dev.emrion.shaders.mod.pipeline.TerrainPrepass.end();
		}
	}

	/** Why: Sodium culls entities by its section graph (the view); RT shadow casters near the camera must be extracted anyway (RtEntities.casterFrustum). */
	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void emrion$rtCasters(net.minecraft.client.renderer.entity.EntityRenderer<?, ?> renderer, net.minecraft.world.entity.Entity entity, float partial,
			CallbackInfoReturnable<Boolean> cir) {
		if (dev.emrion.shaders.mod.rt.RtEntities.casterNear(entity.getBoundingBox())) cir.setReturnValue(true);
	}
}
