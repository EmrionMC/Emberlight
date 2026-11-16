package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.EmrionChunkVertex;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Why: while a pack is active Sodium meshes in our extended format (Iris MixinChunkMeshFormats). Read when Sodium creates its
 * RenderSectionManager, which EmrionVertexFormats' chunk rebuild (allChanged → Sodium reload) does on every switch.
 */
@Mixin(ChunkMeshFormats.class)
public class ChunkMeshFormatsMixin {
	@Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
	private static void emrion$extended(CallbackInfoReturnable<ChunkVertexType> cir) {
		if (EmrionVertexFormats.active()) cir.setReturnValue(EmrionChunkVertex.INSTANCE);
	}
}
