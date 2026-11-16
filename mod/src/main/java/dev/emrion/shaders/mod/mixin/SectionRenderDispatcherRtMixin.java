package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.rt.RtScene;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: BLAS builds follow MC's terrain uploads. Every hook returns at once while no RtScene exists. */
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherRtMixin {
	@org.spongepowered.asm.mixin.Unique
	private static long emrion$uploadStart;

	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("HEAD"))
	private void emrion$uploadBegin(CallbackInfo ci) {
		emrion$uploadStart = System.nanoTime();
	}

	/** Before the RT hook below (declaration order), so the hitch log's chunk upload time excludes the BLAS builds. */
	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("TAIL"))
	private void emrion$uploadEnd(CallbackInfo ci) {
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.CHUNKS, emrion$uploadStart);
	}

	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("TAIL"))
	private void emrion$rtUploadsDone(CallbackInfo ci) {
		RtScene.onUploadsDone((SectionRenderDispatcher) (Object) this);
	}

	@Inject(method = "dispose", at = @At("HEAD"))
	private void emrion$rtDispose(CallbackInfo ci) {
		RtScene.onDispose();
	}
}
