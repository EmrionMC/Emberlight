package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.BlockIdTable;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** Why: Iris (MixinCapeLayer) draws the player's cape with currentRenderedItemId = item.properties id of minecraft:player_cape. */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {
	@WrapMethod(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V")
	private void emrion$capeId(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot,
			Operation<Void> original) {
		if (!EmrionVertexFormats.active()) {
			original.call(poseStack, collector, light, state, yRot, xRot);
			return;
		}
		EntityIds.item = EmrionVertexFormats.ids().item(BlockIdTable.PLAYER_CAPE);
		try {
			original.call(poseStack, collector, light, state, yRot, xRot);
		} finally {
			EntityIds.item = 0; // Iris resets the item id to 0 after the cape layer
		}
	}
}
