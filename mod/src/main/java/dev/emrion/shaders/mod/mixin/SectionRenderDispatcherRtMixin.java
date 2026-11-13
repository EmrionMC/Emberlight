package dev.emrion.shaders.mod.mixin;

import dev.emrion.shaders.mod.rt.RtScene;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: BLAS builds follow MC's terrain uploads (RAYTRACING.md §2.3). Every hook returns at once while no RtScene exists. */
@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherRtMixin {
	@Inject(method = "uploadTerrainBuffersToGpu", at = @At("TAIL"))
	private void emrion$rtUploadsDone(CallbackInfo ci) {
		RtScene.onUploadsDone((SectionRenderDispatcher) (Object) this);
	}

	@Inject(method = "dispose", at = @At("HEAD"))
	private void emrion$rtDispose(CallbackInfo ci) {
		RtScene.onDispose();
	}
}
