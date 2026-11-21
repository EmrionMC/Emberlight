package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: an item's layers are submitted here; its currentRenderedItemId (set by ItemModelResolverMixin) applies to them. */
@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin implements EntityIds.ItemHolder {
	@Unique private int emrion$itemId;
	@Unique private boolean emrion$blockItem;

	@Override
	public void emrion$setItem(int id, boolean blockItem) {
		emrion$itemId = id;
		emrion$blockItem = blockItem;
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void emrion$clear(CallbackInfo ci) {
		emrion$itemId = 0;
		emrion$blockItem = false;
	}

	@WrapMethod(method = "submit")
	private void emrion$submitWithItemId(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline,
			Operation<Void> original) {
		int item = EntityIds.item, blockEntity = EntityIds.blockEntity;
		EntityIds.item = emrion$itemId;
		if (emrion$blockItem) EntityIds.blockEntity = 1;
		try {
			original.call(poseStack, collector, light, overlay, outline);
		} finally {
			EntityIds.item = item;
			EntityIds.blockEntity = blockEntity;
		}
	}
}
