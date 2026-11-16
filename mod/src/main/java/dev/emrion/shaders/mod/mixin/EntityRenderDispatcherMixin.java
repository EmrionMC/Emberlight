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
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Why: the one point where each entity's features are submitted; its entity.properties id is stamped into those submits.
 * Vanilla blob shadows are dropped while the pack draws a shadow map (Iris shouldDisableVanillaEntityShadows).
 */
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
		boolean previousLocal = EntityIds.localPlayer;
		int ownerId = ((dev.emrion.shaders.mod.upscale.ObjectMotion.Owned) state).emrion$entityId();
		EntityIds.localPlayer = cameraEntity != null && ownerId == cameraEntity.getId(); // body, held items, armour, cape, parrots
		int converting = ids.entityName(BlockIdTable.CONVERTING_VILLAGER);
		EntityIds.entity = state instanceof ZombieVillagerRenderState zombie && zombie.isConverting && converting != -1 ? converting
				: state instanceof AvatarRenderState avatar && ids.currentPlayer() != -1 && cameraEntity != null
				&& avatar.id == cameraEntity.getId() ? ids.currentPlayer() : state.entityType == null ? -1 : ids.entity(state.entityType);
		long owner = dev.emrion.shaders.mod.upscale.ObjectMotion.setOwner(dev.emrion.shaders.mod.upscale.ObjectMotion.ENTITY,
				ownerId);
		try {
			original.call(state, camera, x, y, z, poseStack, collector);
		} finally {
			EntityIds.entity = previous;
			EntityIds.localPlayer = previousLocal;
			dev.emrion.shaders.mod.upscale.ObjectMotion.restoreOwner(owner);
		}
	}

	@WrapWithCondition(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"))
	private boolean emrion$vanillaShadow(SubmitNodeCollector collector, PoseStack poseStack, float radius, List<?> pieces) {
		return !EmrionShadersClient.active() || GeometryHooks.vanillaEntityShadows();
	}
}
