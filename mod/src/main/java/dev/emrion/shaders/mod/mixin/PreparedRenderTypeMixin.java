package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.pipeline.GlintPass;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Why: the one draw of every render type; a block-entity copy (SubmitNodeCollectionMixin) draws with gbuffers_block, renderStage 12.
 * Enchanted armour and items get their glint as a second draw (GlintPass).
 */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
	@Shadow
	public abstract String name();

	@Shadow @Final @Mutable private GpuBufferSlice dynamicTransforms;
	@Shadow @Final @Mutable private List<PreparedRenderType.Texture> textures;

	@WrapMethod(method = "draw")
	private void emrion$blockEntityDraw(StagedVertexBuffer.ExecuteInfo info, RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
		if (!EmrionShadersClient.active()) {
			original.call(info, pass, pipeline);
			return;
		}
		GeometryHooks.drawRenderType(name(), () -> original.call(info, pass, pipeline));
		GpuBufferSlice glint = GlintPass.take(this);
		// no pack: vanilla's fused draw already has its glint, and OIT's RGBA32 pass cannot take GlintPass's RGBA8 pipeline
		if (glint == null || !GeometryHooks.packRendering() || GeometryHooks.inShadowPass()) return;
		var glintTexture = textures.stream().filter(t -> t.name().equals("GlintSampler")).findFirst().orElse(null);
		if (glintTexture == null) return;
		GpuBufferSlice baseTransforms = dynamicTransforms;
		List<PreparedRenderType.Texture> baseTextures = textures;
		dynamicTransforms = glint;
		textures = List.of(new PreparedRenderType.Texture("Sampler0", glintTexture.textureView(), glintTexture.sampler()));
		try {
			GeometryHooks.drawRenderType(name(), () -> original.call(info, pass, GlintPass.PIPELINE));
		} finally {
			dynamicTransforms = baseTransforms;
			textures = baseTextures;
		}
	}
}
