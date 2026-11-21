package dev.emrion.shaders.mod.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: while the integrated server saves, MC renders only the saving screen and never uploads terrain. Section compiles
 * that find the staging buffer full then spin on every Worker-Main thread, and the chunk save waiting for that pool hangs.
 */
@Mixin(Minecraft.class)
abstract class DisconnectUploadMixin {
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void emrion$uploadWhileSaving(CallbackInfo ci) {
		var d = ((Minecraft) (Object) this).levelRenderer.sectionRenderDispatcher();
		if (d == null) return;
		d.lock();
		try {
			d.uploadTerrainBuffersToGpu();
		} finally {
			d.unlock();
		}
	}
}
