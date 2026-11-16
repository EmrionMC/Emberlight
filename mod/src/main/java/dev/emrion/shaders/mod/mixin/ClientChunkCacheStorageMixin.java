package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.rt.RtLights;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: a section that becomes all air (its last block broken) or whose chunk unloads is never compiled again, neither by vanilla
 * nor by Sodium, so the RT light list would keep its lights. Every such section passes through here, on either path.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public class ClientChunkCacheStorageMixin {
	@Inject(method = "markSectionEmpty", at = @At("HEAD"))
	private void emrion$sectionEmpty(long sectionNode, CallbackInfo ci) {
		RtLights.emptied(sectionNode);
	}
}
