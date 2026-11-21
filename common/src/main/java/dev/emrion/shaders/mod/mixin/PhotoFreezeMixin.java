package dev.emrion.shaders.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.emrion.shaders.mod.photo.PhotoMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: photo mode freezes the client like the singleplayer pause menu does (no entity, particle or block-entity ticks, a fixed
 * partial tick, no texture animation), also in multiplayer. MC recomputes {@code pause} at the end of every runTick, so it is
 * set again right after that, before the next tick reads it.
 */
@Mixin(Minecraft.class)
public class PhotoFreezeMixin {
	@Shadow private volatile boolean pause;
	@Shadow @Final private DeltaTracker.Timer deltaTracker;

	@Inject(method = "runTick", at = @At("TAIL"))
	private void emrion$photoFreeze(boolean advanceGameTime, CallbackInfo ci) {
		if (!PhotoMode.frozen()) return;
		pause = true;
		deltaTracker.updatePauseState(true);
	}

	@WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
	private void emrion$photoTextures(TextureManager textures, Operation<Void> original) {
		if (!PhotoMode.frozen()) original.call(textures);
	}
}
