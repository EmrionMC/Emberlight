package dev.emrion.shaders.mod.mixin;

import com.mojang.renderpearl.frontend.FrontendRenderPass;
import dev.emrion.shaders.mod.pipeline.GeometryPipelines;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: Iris's atlasSize is the size of the bound albedo texture when it is an atlas; Sampler0 is bound by name only here. */
@Mixin(FrontendRenderPass.class)
public class FrontendRenderPassMixin {
	@Inject(method = "setUniform(Ljava/lang/String;Ljava/lang/Object;)V", at = @At("HEAD"))
	private void emrion$sampler0(String name, @Nullable Object value, CallbackInfo ci) {
		if (name.equals("Sampler0")) GeometryPipelines.sampler0(value);
	}
}
