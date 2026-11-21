package dev.emrion.shaders.mod.compat.sodium;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.sodium.SectionRenderDataStorageAccessor;
import dev.emrion.shaders.mod.mixin.sodium.SodiumWorldRendererAccessor;
import dev.emrion.shaders.mod.rt.RtConfig;
import dev.emrion.shaders.mod.rt.RtScene;
import dev.emrion.shaders.mod.runtime.Formats;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * RT on the Sodium path: one BLAS per (section, pass) from Sodium's arenas, synced at levelStart after Sodium's uploads.
 * A changed upload counter (MeshVersions) or segment means rebuild; a region pass whose storage version and buffer did not
 * change since the last frame keeps its entries as they were. Render thread.
 */
public final class SodiumRt {
	private static final int STRIDE = 40; // EmrionChunkVertex
	private static final ChunkSectionLayer[] LAYERS = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT};
	private static final long POSITIONS = Math.clamp(RtScene.BUDGET * 24, 16L << 20, 64L << 20); // budget x 2 vertices/triangle x 12 B

	private static final class Region {
		final BufferSegment[][] segments = new BufferSegment[LAYERS.length][RenderRegion.REGION_SIZE];
		final int[][] uploads = new int[LAYERS.length][RenderRegion.REGION_SIZE];
		final SectionRenderDataStorage[] storages = new SectionRenderDataStorage[LAYERS.length];
		final int[] versions = new int[LAYERS.length];
		long base, size;
		final RtScene.Entry[][] entries = new RtScene.Entry[LAYERS.length][RenderRegion.REGION_SIZE];
		int frame;
	}

	private final VulkanContext ctx = VulkanContext.get();
	private final RtScene scene;
	private final Map<RenderRegion, Region> regions = new IdentityHashMap<>();
	private final ArrayDeque<RtScene.Entry> pending = new ArrayDeque<>();
	private final ArrayDeque<RtScene.Entry> batch = new ArrayDeque<>();
	private final RawBuffer positions;
	private final long layout, pipeline;
	private int frame, maxWait;
	/** For the RT log: section layer vertex uploads (SectionRenderDataStorageMixin) and the render-thread time in update, both since start. */
	public static long meshUploads, updateNs;
	private boolean wrongFormatWarned;

	public SodiumRt(RtScene scene) {
		this.scene = scene;
		positions = new RawBuffer(ctx, POSITIONS, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
				| VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, false, "rt sodium positions");
		layout = Descriptors.pipelineLayout(ctx, 20);
		long cs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/rt_sodium_positions.comp.spv"));
		pipeline = ComputePipelines.create(ctx, cs, layout, ctx.pipelineCache().handle);
		ShaderModules.destroy(ctx, cs);
		EmrionShadersClient.LOG.info("RT: Sodium path, BLAS from Sodium's region arenas ({} MiB position buffer)", POSITIONS >> 20);
	}

	public void destroy() {
		for (Region r : regions.values()) dropAll(r);
		regions.clear();
		pending.clear();
		scene.external.clear();
		positions.destroyLater();
		long l = layout, p = pipeline;
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, p, null);
			vkDestroyPipelineLayout(ctx.vk, l, null);
		});
	}

	/** Section layers waiting for their BLAS. */
	public int pendingCount() {
		return pending.size();
	}

	/** The most frames a section layer waited for its BLAS since the last call (it is out of the TLAS meanwhile). */
	public int takeMaxWait() {
		int w = maxWait;
		maxWait = 0;
		return w;
	}

	/** Section layers with a BLAS in this frame's TLAS set. */
	public int built() {
		return scene.external.size();
	}

	/** Sodium's section manager exists (it is null while a resource reload or level switch rebuilds the renderer). */
	public static boolean sectionsReady() {
		var renderer = SodiumWorldRenderer.instanceNullable();
		return renderer != null && ((SodiumWorldRendererAccessor) renderer).emrion$sections() != null;
	}

	/** levelStart, before the TLAS build in the same command buffer: syncs the BLAS set, builds within budget, fills scene.external. */
	public void update(VkCommandBuffer cb, Vec3 cam) {
		long start = System.nanoTime();
		frame++;
		scene.external.clear();
		double maxDist = RtConfig.distanceBlocks();
		var renderer = SodiumWorldRenderer.instanceNullable();
		var sections = renderer == null ? null : ((SodiumWorldRendererAccessor) renderer).emrion$sections();
		// our arenas hold EmrionChunkVertex only while a pack is active (ChunkMeshFormatsMixin); anything else is not read
		boolean ours = EmrionVertexFormats.active() && ChunkMeshFormats.getCurrent() == EmrionChunkVertex.INSTANCE;
		if (!ours && sections != null && !wrongFormatWarned) {
			EmrionShadersClient.LOG.warn("RT: Sodium meshes in {}, not EmrionChunkVertex: no terrain BLAS", ChunkMeshFormats.getCurrent());
			wrongFormatWarned = true;
		}
		if (sections != null && ours)
			for (RenderRegion region : sections.regions.getLoadedRegions()) sync(region, cam, maxDist);
		for (RtScene.Entry e : scene.external) if (scene.keepInRange(e, pending, cam, maxDist)) e.queued = frame;
		regions.values().removeIf(r -> {
			if (r.frame == frame) return false;
			dropAll(r);
			return true;
		});
		build(cb, cam, maxDist);
		scene.external.removeIf(e -> !e.built()); // built just now count: the section is in this frame's TLAS, no gap after a rebuild
		updateNs += System.nanoTime() - start;
		scene.log();
	}

	private void sync(RenderRegion region, Vec3 cam, double maxDist) {
		var resources = region.getResources();
		long base = 0, size = 0;
		if (resources != null && resources.getGeometryBuffer() instanceof VulkanGpuBuffer buf && (buf.usage() & Formats.USAGE_RT_INPUT) != 0) {
			base = scene.address(buf.vkBuffer());
			size = buf.size();
		}
		Region r = regions.get(region);
		if (r == null) {
			if (base == 0) return;
			regions.put(region, r = new Region());
		}
		r.frame = frame;
		boolean moved = base != r.base || size != r.size;
		r.base = base;
		r.size = size;
		for (int p = 0; p < LAYERS.length; p++) {
			SectionRenderDataStorage storage = base == 0 ? null : region.getStorage(DefaultTerrainRenderPasses.ALL[p]);
			RtScene.Entry[] entries = r.entries[p];
			int version = storage == null ? 0 : ((MeshVersions) storage).emrion$version();
			if (!moved && storage == r.storages[p] && version == r.versions[p]) { // nothing uploaded, removed or moved
				for (RtScene.Entry e : entries) if (e != null && e.vertexAddr != 0) scene.external.add(e);
				continue;
			}
			r.storages[p] = storage;
			r.versions[p] = version;
			BufferSegment[] now = storage == null ? null : ((SectionRenderDataStorageAccessor) storage).emrion$vertexAllocations();
			int[] uploads = storage == null ? null : ((MeshVersions) storage).emrion$uploads();
			BufferSegment[] had = r.segments[p];
			int[] hadUploads = r.uploads[p];
			for (int id = 0; id < RenderRegion.REGION_SIZE; id++) {
				BufferSegment seg = now == null ? null : now[id];
				int upload = uploads == null ? 0 : uploads[id];
				if (seg != had[id] || upload != hadUploads[id]) { // new mesh (perhaps in the same segment object), or removed
					boolean replaced = entries[id] != null;
					if (replaced) scene.drop(entries[id]);
					entries[id] = null;
					had[id] = seg;
					hadUploads[id] = upload;
					if (seg != null) {
						long data = storage.getDataPointer(id), vertices = 0;
						for (int f = 0; f < ModelQuadFacing.COUNT; f++) vertices += SectionRenderDataUnsafe.getVertexCount(data, f);
						int quads = (int) (vertices / 4);
						if (quads > 0) {
							entries[id] = new RtScene.Entry(LAYERS[p], (region.getChunkX() + LocalSectionIndex.unpackX(id)) << 4,
									(region.getChunkY() + LocalSectionIndex.unpackY(id)) << 4, (region.getChunkZ() + LocalSectionIndex.unpackZ(id)) << 4, quads);
							// a changed section (a redstone clock rebuilds it every few ticks) goes first, so it is never out of the TLAS for a frame;
							// new ones are queued by update once they are within the RT distance
							if (replaced && RtScene.near(entries[id], cam, maxDist, RtScene.QUEUE_MARGIN)) {
								entries[id].queued = frame;
								entries[id].waiting = true;
								pending.addFirst(entries[id]);
							}
						}
					}
				}
				RtScene.Entry e = entries[id];
				if (e == null) continue;
				long first = SectionRenderDataUnsafe.getBaseVertex(storage.getDataPointer(id));
				// defragmentation moves the segment: the address is current for this frame only; never read past the buffer
				e.vertexAddr = (first + e.quads * 4L) * STRIDE <= size ? base + first * STRIDE : 0;
				if (e.vertexAddr != 0) scene.external.add(e); // unbuilt ones are dropped after this frame's builds (update)
			}
		}
	}

	/** Decodes the positions of the next pending entries (up to the BLAS budget and the position buffer), then builds them. */
	private void build(VkCommandBuffer cb, Vec3 cam, double maxDist) {
		if (pending.isEmpty()) return;
		long tris = 0, offset = 0, posBase = scene.address(positions.handle);
		// this frame's uploads into the arenas (RAW), last frame's builds from the position buffer (WAR) and into the shared
		// scratch ring (WAW: the builds below reuse it from offset 0)
		Barriers.memory(cb, Barriers.TRANSFER | AS_BUILD, Barriers.TRANSFER_WRITE | AS_READ | AS_WRITE, Barriers.COMPUTE, Barriers.STORAGE_READ | Barriers.STORAGE_WRITE);
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var push = stack.malloc(20);
			while (!pending.isEmpty() && tris < RtScene.BUDGET && batch.size() < RtScene.MAX_BUILDS) {
				RtScene.Entry e = pending.peek();
				long bytes = e.quads * 4L * 12;
				// dropped, outside its buffer (cannot happen for a well-formed arena), or out of RT range since it was queued
				if (e.removed || e.vertexAddr == 0 || !RtScene.near(e, cam, maxDist, RtScene.QUEUE_MARGIN)) {
					pending.poll().waiting = false;
					continue;
				}
				if (bytes > POSITIONS) {
					EmrionShadersClient.LOG.warn("RT: Sodium section with {} quads exceeds the position buffer, skipped", e.quads);
					pending.poll().waiting = false;
					continue;
				}
				if (offset + bytes > POSITIONS) break;
				pending.poll().waiting = false;
				maxWait = Math.max(maxWait, frame - e.queued);
				e.posAddr = posBase + offset;
				push.putLong(0, e.vertexAddr).putLong(8, e.posAddr).putInt(16, e.quads * 4);
				vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, push);
				vkCmdDispatch(cb, (e.quads * 4 + 63) / 64, 1, 1);
				offset += (bytes + 255) & ~255L;
				tris += e.quads * 2L;
				batch.add(e);
			}
		}
		Barriers.memory(cb, Barriers.COMPUTE, Barriers.STORAGE_WRITE, AS_BUILD, AS_READ | AS_WRITE | VK13.VK_ACCESS_2_SHADER_READ_BIT);
		scene.buildPending(cb, batch);
	}

	private void dropAll(Region r) {
		for (RtScene.Entry[] arr : r.entries) for (RtScene.Entry e : arr) if (e != null) scene.drop(e);
	}

	private static final long AS_BUILD = KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
	private static final long AS_READ = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
	private static final long AS_WRITE = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
}
