package dev.emrion.shaders.mod.mixin;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import dev.emrion.shaders.mod.runtime.AudioPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: the OpenAL device (and its audio thread) is (re)opened here; see AudioPriority. */
@Mixin(Library.class)
public class AudioLibraryMixin {
	@Inject(method = "init", at = @At("RETURN"))
	private void emrion$audioPriority(String preferredDevice, DeviceList devices, boolean useHrtf, CallbackInfo ci) {
		AudioPriority.raise();
	}
}
