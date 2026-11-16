package dev.emrion.shaders.mod.mixin.sodium;

import dev.emrion.shaders.mod.compat.sodium.MeshVersions;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: RT on the Sodium path (compat.sodium.SodiumRt) must see every mesh upload. Sodium frees the old segment before it
 * allocates the new one, so an upload of the same size can get the same BufferSegment object back; and the version lets it
 * skip a region whose meshes did not change or move.
 */
@Mixin(SectionRenderDataStorage.class)
public class SectionRenderDataStorageMixin implements MeshVersions {
	@Unique private final int[] emrion$uploads = new int[RenderRegion.REGION_SIZE];
	@Unique private int emrion$version;

	@Override
	public int[] emrion$uploads() {
		return emrion$uploads;
	}

	@Override
	public int emrion$version() {
		return emrion$version;
	}

	@Inject(method = "setVertexData", at = @At("HEAD"))
	private void emrion$upload(int section, BufferSegment allocation, int[] vertexCounts, CallbackInfo ci) {
		emrion$uploads[section]++;
		emrion$version++;
	}

	@Inject(method = {"removeData(IZZ)V", "onVertexSegmentChanged", "onBufferResized"}, at = @At("HEAD"))
	private void emrion$changed(CallbackInfo ci) {
		emrion$version++;
	}
}
