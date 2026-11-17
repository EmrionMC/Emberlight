package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.photo.PhotoMode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: a server keeps sending its time while photo mode runs; the sun (and the shadows) must not move. The next packet resyncs. */
@Mixin(ClientPacketListener.class)
public class PhotoTimeMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"), cancellable = true)
	private void emrion$photoTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		if (PhotoMode.frozen()) ci.cancel();
	}
}
