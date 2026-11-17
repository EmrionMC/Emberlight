package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;

/** Why: Iris (MixinHumanoidArmorLayer) draws worn armour, elytra and horse armour with currentRenderedItemId = the item's id. */
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerRendererMixin {
	@WrapMethod(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V")
	private void emrion$armourId(EquipmentClientInfo.LayerType type, ResourceKey<EquipmentAsset> asset, Model<?> model, Object state, ItemStack stack,
			PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier texture, int outline, int color, Operation<Void> original) {
		if (!EmrionVertexFormats.active() || stack.isEmpty()) {
			original.call(type, asset, model, state, stack, poseStack, collector, light, texture, outline, color);
			return;
		}
		int item = EntityIds.item;
		Identifier id = stack.get(DataComponents.ITEM_MODEL);
		EntityIds.item = EmrionVertexFormats.ids().item(id != null ? id : BuiltInRegistries.ITEM.getKey(stack.getItem()));
		try {
			original.call(type, asset, model, state, stack, poseStack, collector, light, texture, outline, color);
		} finally {
			EntityIds.item = item;
		}
	}
}
