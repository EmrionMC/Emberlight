package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Why: the one draw of every render type; a block-entity copy (SubmitNodeCollectionMixin) draws with gbuffers_block, renderStage 12. */
@Mixin(PreparedRenderType.class)
public abstract class PreparedRenderTypeMixin {
	@Shadow
	public abstract String name();

	@WrapMethod(method = "draw")
	private void emrion$blockEntityDraw(StagedVertexBuffer.ExecuteInfo info, RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
		if (!EmrionShadersClient.active()) original.call(info, pass, pipeline);
		else GeometryHooks.drawRenderType(name(), () -> original.call(info, pass, pipeline));
	}
}
