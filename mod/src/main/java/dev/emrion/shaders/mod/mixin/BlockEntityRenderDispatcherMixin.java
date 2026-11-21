package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.BlockEntityGeometryCache;
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
		float ox = BlockEntityGeometryCache.originX, oy = BlockEntityGeometryCache.originY, oz = BlockEntityGeometryCache.originZ;
		var m = poseStack.last().pose(); // the translation to the block, nothing else (else: no origin, not cached)
		boolean translation = m.m00() == 1 && m.m11() == 1 && m.m22() == 1 && m.m01() == 0 && m.m02() == 0 && m.m10() == 0 && m.m12() == 0
				&& m.m20() == 0 && m.m21() == 0;
		BlockEntityGeometryCache.originX = translation ? m.m30() : Float.NaN;
		BlockEntityGeometryCache.originY = m.m31();
		BlockEntityGeometryCache.originZ = m.m32();
		long owner = dev.emrion.shaders.mod.upscale.ObjectMotion.setOwner(dev.emrion.shaders.mod.upscale.ObjectMotion.BLOCK_ENTITY, state.blockPos.asLong());
		try {
			original.call(state, poseStack, collector, camera);
		} finally {
			dev.emrion.shaders.mod.upscale.ObjectMotion.restoreOwner(owner);
			BlockEntityGeometryCache.originX = ox;
			BlockEntityGeometryCache.originY = oy;
			BlockEntityGeometryCache.originZ = oz;
			EntityIds.blockEntity = previous;
			GeometryHooks.submittingBlockEntity = wasSubmitting;
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "onResourceManagerReload", at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
	private void emrion$clearGeometryCache(net.minecraft.server.packs.resources.ResourceManager manager,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		BlockEntityGeometryCache.clear(); // new atlas UVs and models
	}
}
