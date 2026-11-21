package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vertex.BlockIdTable;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Why: Iris draws block entities with gbuffers_block, so block-entity submits get their own render types and batch apart.
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
	@ModifyVariable(method = {"submitModel", "submitCustomGeometry"}, at = @At("HEAD"), argsOnly = true)
	private RenderType emrion$blockEntityType(RenderType type) {
		return EmrionShadersClient.active() ? GeometryHooks.blockEntityType(type) : type;
	}

	/** Iris (name-tag feature renderer): name tags draw as entity minecraft:name_tag (−1 when the pack does not map it). */
	@WrapMethod(method = "submitNameTag")
	private void emrion$nameTagId(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough, int light,
			CameraRenderState camera, Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(poseStack, attachment, offset, name, seeThrough, light, camera);
			return;
		}
		int previous = EntityIds.entity;
		EntityIds.entity = EmrionVertexFormats.ids().entityName(BlockIdTable.NAME_TAG);
		try {
			original.call(poseStack, attachment, offset, name, seeThrough, light, camera);
		} finally {
			EntityIds.entity = previous;
		}
	}
}
