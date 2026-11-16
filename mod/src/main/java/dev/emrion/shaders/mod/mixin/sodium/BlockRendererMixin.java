package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumVertices;
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
}
