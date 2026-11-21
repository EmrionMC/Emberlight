package dev.emrion.shaders.mod.mixin.sodium;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Why: Sodium caches its pipelines per pass for the whole session. They stay in the compact format, so they still fit when the
 * pack is turned off; while a pack renders ours replace them (built with SODIUM_TERRAIN), as Iris's MixinShaderChunkRenderer.
 */
@Mixin(ShaderChunkRenderer.class)
public class ShaderChunkRendererMixin {
	@ModifyArg(method = {"createShader", "createOITShader"}, index = 1, at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withVertexBinding(ILcom/mojang/renderpearl/api/vertex/VertexFormat;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;"))
	private VertexFormat emrion$compact(VertexFormat format) {
		return CompactChunkVertex.VERTEX_FORMAT;
	}
}
