package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.debug.HitchLog;
import java.util.Collection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Why: the hitch log times Sodium's chunk mesh uploads (render thread). */
@Mixin(RenderRegionManager.class)
public class RenderRegionManagerMixin {
	@Unique
	private static long emrion$uploadStart;

	@Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("HEAD"), require = 0)
	private void emrion$uploadBegin(Collection<BuilderTaskOutput> results, UniformBufferManager ubm, CallbackInfo ci) {
		emrion$uploadStart = System.nanoTime();
	}

	@Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("TAIL"), require = 0)
	private void emrion$uploadEnd(Collection<BuilderTaskOutput> results, UniformBufferManager ubm, CallbackInfo ci) {
		HitchLog.add(HitchLog.CHUNKS, emrion$uploadStart, results.size());
	}
}
