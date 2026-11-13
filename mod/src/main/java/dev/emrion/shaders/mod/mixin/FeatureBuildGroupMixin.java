package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: each feature renderer writes its submits' vertices in this loop; the submit's captured ids go into those vertices. */
@Mixin({ModelFeatureRenderer.class, ItemFeatureRenderer.class, CustomFeatureRenderer.class, BlockModelFeatureRenderer.class,
		MovingBlockFeatureRenderer.class, FlameFeatureRenderer.class, LeashFeatureRenderer.class, ShadowFeatureRenderer.class,
		TextFeatureRenderer.class})
public class FeatureBuildGroupMixin {
	@ModifyExpressionValue(method = "buildGroup", at = @At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;"))
	private Object emrion$restoreIds(Object submit) {
		if (submit instanceof EntityIds.Holder h) EntityIds.restore(h.emrion$ids());
		return submit;
	}

	@Inject(method = "buildGroup", at = @At("RETURN"))
	private void emrion$clearIds(CallbackInfo ci) {
		EntityIds.restore(0L);
	}
}
