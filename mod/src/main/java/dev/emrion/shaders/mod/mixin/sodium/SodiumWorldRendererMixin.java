package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.SodiumShadow;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: the shadow pass's per-region caches (compat.sodium.SodiumShadow) must not outlive Sodium's regions, their block entities and world. */
@Mixin(SodiumWorldRenderer.class)
public class SodiumWorldRendererMixin {
	@Inject(method = {"unloadLevel", "initRenderer"}, at = @At("HEAD"))
	private void emrion$resetShadow(CallbackInfo ci) {
		SodiumShadow.reset();
	}
}
