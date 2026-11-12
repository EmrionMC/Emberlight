package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.BlockIdTable;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Why: the one point where each entity's features are submitted; its entity.properties id is stamped into those submits. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@WrapMethod(method = "submit")
	private void emrion$entityId(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
			SubmitNodeCollector collector, Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(state, camera, x, y, z, poseStack, collector);
			return;
		}
		BlockIdTable ids = EmrionVertexFormats.ids();
		Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
		int previous = EntityIds.entity;
		EntityIds.entity = state instanceof AvatarRenderState avatar && ids.currentPlayer() != -1 && cameraEntity != null
				&& avatar.id == cameraEntity.getId() ? ids.currentPlayer() : state.entityType == null ? -1 : ids.entity(state.entityType);
		try {
			original.call(state, camera, x, y, z, poseStack, collector);
		} finally {
			EntityIds.entity = previous;
		}
	}
}
