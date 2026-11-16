package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumVertices;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: fluid quads get the fluid's block data (Iris MixinDefaultFluidRenderer), and oldLighting=false removes side shading. */
@Mixin(DefaultFluidRenderer.class)
public class DefaultFluidRendererMixin {
	@Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

	@Inject(method = "writeQuad", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadView;sodium$getSprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private void emrion$fluidData(CallbackInfo ci) {
		SodiumVertices.tag(vertices);
	}

	/** The side faces' 0.8/0.6 brightness (the third updateQuad call), as Iris's setBrightness. */
	@ModifyArg(method = "render", index = 6, at = @At(value = "INVOKE", ordinal = 2, target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer;updateQuad(Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadViewMutable;Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/model/light/LightPipeline;Lnet/minecraft/core/Direction;Lnet/caffeinemc/mods/sodium/client/model/quad/properties/ModelQuadFacing;FLnet/caffeinemc/mods/sodium/client/model/color/ColorProvider;Lnet/minecraft/world/level/material/FluidState;)V"))
	private float emrion$sideBrightness(float br) {
		return EmrionVertexFormats.directionalShadingOff() ? 1.0f : br;
	}
}
