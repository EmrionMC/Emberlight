package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumVertices;
import dev.emrion.shaders.mod.vertex.BlockContext;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: each block quad's vertices get the current block's data before Sodium pushes or keeps them (Iris MixinBlockRenderer). */
@Mixin(BlockRenderer.class)
public class BlockRendererMixin {
	@Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

	@Inject(method = "bufferQuad", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;sprite(Lnet/caffeinemc/mods/sodium/client/render/texture/SodiumSpriteFinder;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
	private void emrion$blockData(CallbackInfo ci) {
		SodiumVertices.tag(vertices);
	}

	/**
	 * Why: Better Block Entities (fancy banners) meshes a banner's cloth and patterns as translucent terrain. Packs and our RT
	 * treat translucent terrain as glass: no RT shadow (the sun shone through in the cloth's colours), RT reflections on the
	 * cloth, a tinted map shadow. Cutout (BBE's own "fast" banners) keeps them opaque and alpha-tested.
	 */
	@ModifyArg(method = "processQuad", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"))
	private ChunkSectionLayer emrion$opaqueBanner(ChunkSectionLayer layer) {
		return layer == ChunkSectionLayer.TRANSLUCENT && EmrionVertexFormats.active() && BlockContext.get().banner ? ChunkSectionLayer.CUTOUT : layer;
	}
}
