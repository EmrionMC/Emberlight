package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.runtime.GeometryHooks;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: submits are created while the entity/block entity/item that owns them is known, but their vertices are written later,
 * regrouped by render type. Each submit keeps the ids that were current when it was created (Iris MixinModelSubmit).
 */
@Mixin({ModelFeatureRenderer.Submit.class, ItemFeatureRenderer.Submit.class, CustomFeatureRenderer.Submit.class,
		BlockModelFeatureRenderer.Submit.class, MovingBlockFeatureRenderer.Submit.class, FlameFeatureRenderer.Submit.class,
		LeashFeatureRenderer.Submit.class, ShadowFeatureRenderer.Submit.class, TextFeatureRenderer.Submit.class})
public class FeatureSubmitIdsMixin implements EntityIds.Holder {
	@Unique private long emrion$ids;

	@Inject(method = "<init>*", at = @At("RETURN"))
	private void emrion$capture(CallbackInfo ci) {
		emrion$ids = EntityIds.packed() | (GeometryHooks.submittingBlockEntity ? EntityIds.BLOCK_ENTITY_SUBMIT : 0);
	}

	@Override
	public long emrion$ids() {
		return emrion$ids;
	}
}
