package dev.emrion.shaders.mod.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.emrion.shaders.mod.compat.sodium.BandedBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.client.renderer.oit.OitStage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Why: Sodium draws opaque terrain region by region (regions sorted by distance, sections in octree order inside), so far
 * sections of a near region are shaded before near sections of the next region hides them; in a cave this costs 24 % more
 * fragment shader invocations than vanilla's globally near-to-far order. Here each region's opaque commands are filled in
 * distance bands and the regions are drawn once per band, nearest band first. Same commands, only the order changes.
 * -Demrion.perf.sodiumBands=false: Sodium's order.
 */
@Mixin(DefaultChunkRenderer.class)
public abstract class DefaultChunkRendererMixin {
	@Unique private static final boolean BANDS = !"false".equals(System.getProperty("emrion.perf.sodiumBands"));
	/** Section distance (Chebyshev, in sections) bands: 0, 1, 2-3, 4-7, 8-15, 16+ (finer far bands measured no faster). */
	@Unique private static final int BAND_COUNT = 6;
	@Unique private static int emrion$band = -1;
	/** Scratch: squared section distance << 8 | local section index, sorted near to far. */
	@Unique private static final int[] emrion$sections = new int[256];

	@Shadow
	private static void addSharedIndexedDrawCommands(MultiDrawBatch batch, long pMeshData, int mask) {}

	@Shadow
	public abstract void render(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera,
			FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData,
			GpuBuffer sectionTimeInfo, @Nullable OitStage stage);

	@Unique
	private static int emrion$band(int dx, int dy, int dz) {
		int d = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		return d == 0 ? 0 : Math.min(BAND_COUNT - 1, 32 - Integer.numberOfLeadingZeros(d));
	}

	/** Opaque passes: the same commands as Sodium's fillCommandBuffer (shared index buffer, face culling), in band order. */
	@Inject(method = "fillCommandBuffer", at = @At("HEAD"), cancellable = true)
	private static void emrion$fillBands(MultiDrawBatch batch, RenderRegion region, SectionRenderDataStorage storage, ChunkRenderList list,
			CameraTransform camera, TerrainRenderPass pass, boolean useBlockFaceCulling, boolean useIndexedTessellation, CallbackInfo ci) {
		if (!BANDS || pass.isTranslucent() || !(batch instanceof BandedBatch banded)) return;
		ci.cancel();
		batch.isFilled = true;
		int[] bands = new int[BAND_COUNT + 1];
		int n = 0;
		int camX = camera.intX >> 4, camY = camera.intY >> 4, camZ = camera.intZ >> 4;
		ByteIterator it = list.sectionsWithGeometryIterator(false);
		if (it != null) while (it.hasNext()) {
			int id = it.nextByteAsInt();
			int dx = region.getChunkX() + LocalSectionIndex.unpackX(id) - camX, dy = region.getChunkY() + LocalSectionIndex.unpackY(id) - camY,
					dz = region.getChunkZ() + LocalSectionIndex.unpackZ(id) - camZ;
			emrion$sections[n++] = (dx * dx + dy * dy + dz * dz) << 8 | id;
		}
		java.util.Arrays.sort(emrion$sections, 0, n);
		for (int b = 0; b < BAND_COUNT; b++) {
			bands[b] = batch.size;
			for (int i = 0; i < n; i++) {
				int id = emrion$sections[i] & 0xFF;
				int x = region.getChunkX() + LocalSectionIndex.unpackX(id), y = region.getChunkY() + LocalSectionIndex.unpackY(id),
						z = region.getChunkZ() + LocalSectionIndex.unpackZ(id);
				if (emrion$band(x - camX, y - camY, z - camZ) != b) continue;
				long data = storage.getDataPointer(id);
				int slices = useBlockFaceCulling ? DefaultChunkRenderer.getVisibleFaces(camera.intX, camera.intY, camera.intZ, x, y, z)
						: net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing.ALL;
				slices &= SectionRenderDataUnsafe.getSliceMask(data);
				if (slices != 0) addSharedIndexedDrawCommands(batch, data, slices);
			}
		}
		bands[BAND_COUNT] = batch.size;
		banded.emrion$setBands(bands);
	}

	/** Opaque passes: one render per band, each drawing that band's sub-range of every region. */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void emrion$renderBands(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera,
			FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData,
			GpuBuffer sectionTimeInfo, @Nullable OitStage stage, CallbackInfo ci) {
		if (!BANDS || renderPass.isTranslucent() || emrion$band >= 0) return;
		// bands with a command in some region past band 0; with none (only near sections, or batches not filled in bands: the
		// indirect or OpenGL backend) Sodium's own single render draws everything
		int used = 1;
		for (var it = renderLists.iterator(false); it.hasNext(); ) {
			RenderRegion region = it.next().getRegion();
			if (region.getStorage(renderPass) == null || region.getResources() == null) continue; // as render: no batch
			MultiDrawBatch batch = region.getCachedBatch(renderPass);
			int[] bands = batch instanceof BandedBatch b ? b.emrion$bands() : null;
			if (bands == null || bands[BAND_COUNT] != batch.size) continue;
			for (int b = 1; b < BAND_COUNT; b++) if (bands[b] < bands[b + 1]) used |= 1 << b;
		}
		if (used == 1) return;
		ci.cancel();
		try {
			for (emrion$band = 0; emrion$band < BAND_COUNT; emrion$band++)
				if ((used >>> emrion$band & 1) != 0) render(matrices, renderLists, renderPass, camera, parameters, indexedRenderingEnabled, pass, terrainSampler, uniformData, sectionTimeInfo, stage);
		} finally {
			emrion$band = -1;
		}
	}

	/** A region with nothing in this band is skipped before its buffers and push constants are bound. */
	@Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;isEmpty()Z"))
	private boolean emrion$bandEmpty(MultiDrawBatch batch) {
		if (batch.isEmpty() || emrion$band < 0) return batch.isEmpty();
		int[] bands = batch instanceof BandedBatch b ? b.emrion$bands() : null;
		if (bands == null || bands[BAND_COUNT] != batch.size) return emrion$band > 0; // not filled in bands: drawn with the first
		return bands[emrion$band] >= bands[emrion$band + 1];
	}

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/gpu/device/batch/MultiDrawBatch;draw(Lnet/caffeinemc/mods/sodium/client/gpu/device/context/DrawContext;)V"))
	private void emrion$drawBand(MultiDrawBatch batch, DrawContext context) {
		int[] bands = emrion$band >= 0 && batch instanceof BandedBatch b ? b.emrion$bands() : null;
		if (bands == null || bands[BAND_COUNT] != batch.size) { // not filled in bands: everything with the first band
			if (emrion$band <= 0) batch.draw(context);
			return;
		}
		if (bands[emrion$band] < bands[emrion$band + 1]) ((BandedBatch) batch).emrion$draw(context, bands[emrion$band], bands[emrion$band + 1]);
	}
}
