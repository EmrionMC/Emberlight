package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumShadow;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Why: the shadow pass's per-region caches (compat.sodium.SodiumShadow) must not outlive Sodium's regions, their block entities and world. */
@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererMixin {
	@Inject(method = {"unloadLevel", "initRenderer"}, at = @At("HEAD"))
	private void emrion$resetShadow(CallbackInfo ci) {
		SodiumShadow.reset();
	}

	/** Why: Sodium culls entities by its section graph (the view); RT shadow casters near the camera must be extracted anyway (RtEntities.casterFrustum). */
	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void emrion$rtCasters(net.minecraft.client.renderer.entity.EntityRenderer<?, ?> renderer, net.minecraft.world.entity.Entity entity, float partial,
			CallbackInfoReturnable<Boolean> cir) {
		if (dev.emrion.shaders.mod.rt.RtEntities.casterNear(entity.getBoundingBox())) cir.setReturnValue(true);
	}
}
