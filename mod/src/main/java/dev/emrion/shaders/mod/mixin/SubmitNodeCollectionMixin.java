package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Why: MC batches block-entity and entity models together, but Iris draws block entities with gbuffers_block (BLOCK_ENTITIES
 * phase); block-entity submits get their own render type copies so they batch apart (see PreparedRenderTypeMixin).
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
	@ModifyVariable(method = {"submitModel", "submitCustomGeometry"}, at = @At("HEAD"), argsOnly = true)
	private RenderType emrion$blockEntityType(RenderType type) {
		return EmrionShadersClient.active() ? GeometryHooks.blockEntityType(type) : type;
	}
}
