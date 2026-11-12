package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Why: the one point where each block entity's features are submitted; blockEntityId = its block state's block.properties id,
 * and its entity render types become block-entity copies (GeometryHooks.blockEntityType, Iris BLOCK_ENTITIES phase).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {
	@WrapMethod(method = "submit")
	private void emrion$blockEntityId(BlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
			Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(state, poseStack, collector, camera);
			return;
		}
		int previous = EntityIds.blockEntity;
		boolean wasSubmitting = GeometryHooks.submittingBlockEntity;
		EntityIds.blockEntity = EmrionVertexFormats.ids().block(((BlockEntityRenderStateAccessor) state).emrion$blockState());
		GeometryHooks.submittingBlockEntity = true;
		try {
			original.call(state, poseStack, collector, camera);
		} finally {
			EntityIds.blockEntity = previous;
			GeometryHooks.submittingBlockEntity = wasSubmitting;
		}
	}
}
