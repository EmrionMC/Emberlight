package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.debug.Profiler;
import dev.emrion.shaders.mod.runtime.ForcedSections;
import dev.emrion.shaders.mod.runtime.Formats;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.GpuTimer;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawCommands;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

/**
 * Acceleration structures over MC's terrain (RAYTRACING.md §2): one BLAS per (section mesh, layer), read in place from MC's
 * vertex heaps with our quad index buffer, built at {@code uploadTerrainBuffersToGpu} TAIL within a per-frame triangle budget;
 * one TLAS rebuilt every frame at {@code levelStart} with camera-relative instance transforms (TLAS space = the pack's playerPos).
 * <p>Threading: {@link #onMeshReleased} may run on section worker threads (always under MC's copyLock); everything else runs on
 * the render thread. BLAS read only at build time, so a freed or reused slice is only a problem for builds not yet recorded:
 * releases are applied before every build batch, and a slice can only be reused by an upload, which runs on the render thread
 * and is followed by the next batch in submission order.
 */
public final class RtScene {
	private static final int STRIDE = 56; // EMRION_BLOCK
	private static final int LAYERS = ChunkSectionLayer.values().length;
	private static final long BUDGET = Long.getLong("emrion.rt.blasBudget", 2_000_000L); // triangles per frame
	private static final long SCRATCH_SIZE = 64L << 20;
	private static final int RING = UboRing.REGIONS;
	/**
	 * Fixed TLAS capacity: the TLAS and the section table never move, so the pack's descriptor sets (written once per runtime)
	 * stay valid. 64 k instances cover RD 16 with every section forced (26 k sections x up to 3 layers, mostly empty).
	 */
	private static final int CAPACITY = Integer.getInteger("emrion.rt.maxInstances", 65536);
	private static final long AS_BUILD = KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
	private static final long AS_READ = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
	private static final long AS_WRITE = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;

	private static @Nullable RtScene instance;
	private static final ConcurrentLinkedQueue<SectionMesh> released = new ConcurrentLinkedQueue<>();

	private static final class Entry {
		final SectionRenderDispatcher.RenderSection section;
		final CompiledSectionMesh mesh;
		final ChunkSectionLayer layer;
		final int x, y, z;
		int quads;
		long vertexAddr, blas, blasAddr, blasSize, scratchAddr;
		@Nullable RawBuffer storage;
		boolean removed;

		Entry(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh mesh, ChunkSectionLayer layer) {
			this.section = section;
			this.mesh = mesh;
			this.layer = layer;
			var o = section.getRenderOrigin();
			x = o.getX();
			y = o.getY();
			z = o.getZ();
		}
	}

	private final VulkanContext ctx = VulkanContext.get();
	private final long scratchAlign;
	private final List<Entry> uploaded = new ArrayList<>();
	private final Map<SectionMesh, Entry[]> entries = new IdentityHashMap<>();
	private final ArrayDeque<Entry> pending = new ArrayDeque<>();
	private final RawBuffer scratch;
	private @Nullable RawBuffer indices;
	private int indexQuads;
	// TLAS: fixed capacity; instance + section table rings (host-written per frame)
	private long tlas, tlasAddr;
	private @Nullable RawBuffer tlasStorage, tlasScratch;
	private final RawBuffer[] instances = new RawBuffer[RING];
	/** EmrionRtSections: RING regions of CAPACITY entries; instanceCustomIndex = region * CAPACITY + slot. */
	private @Nullable RawBuffer sections;
	private boolean overflowWarned;
	private int frame;
	private boolean tlasValid;
	// stats
	private long blasBytes, triangles, built, gpuBuildNs, maxFrameBuildNs, tlasBytes;
	private int instanceCount;
	private final GpuTimer timer = new GpuTimer(2);
	private final boolean[] timed = new boolean[RING];
	private int timerSlot;
	private long lastLog;

	private RtScene() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var as = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
			vkGetPhysicalDeviceProperties2(ctx.vk.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(as));
			scratchAlign = Math.max(1, as.minAccelerationStructureScratchOffsetAlignment());
		}
		scratch = new RawBuffer(ctx, SCRATCH_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt blas scratch");
		ensureCapacity();
		EmrionShadersClient.LOG.info("RT: scene created, TLAS capacity {} instances, BLAS budget {} triangles/frame, scratch alignment {}", CAPACITY, BUDGET, scratchAlign);
	}

	public static @Nullable RtScene get() {
		return instance;
	}

	/**
	 * An RT runtime is being installed (PackManager): creates the scene, then rebuilds all chunks so every section's mesh is
	 * uploaded again and gets its BLAS (MC keeps no CPU copy to walk). Render thread.
	 */
	public static RtScene activate() {
		if (instance != null) return instance;
		instance = new RtScene();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.levelExtractor.allChanged());
		return instance;
	}

	// ---- hooks (SectionRenderDispatcherRtMixin) ----------------------------------------------------------------------------

	/** RenderSection.vertexBufferUploadCallback HEAD (render thread, inside the upload): the layer's vertices were just copied. */
	public static void onVertexUploaded(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh mesh, ChunkSectionLayer layer) {
		if (!RtCapability.enabled || !EmrionVertexFormats.active()) return;
		if (instance == null) {
			if (!RtCapability.DEBUG) return; // only an RT runtime (activate) or the RT-1 debug view create the scene
			instance = new RtScene();
		}
		instance.uploaded.add(new Entry(section, mesh, layer));
	}

	/** RenderSection.releaseSectionMesh HEAD (any thread, under copyLock): all layers of the mesh lose their slices. */
	public static void onMeshReleased(SectionMesh mesh) {
		if (instance != null) released.add(mesh);
	}

	/** SectionRenderDispatcher.uploadTerrainBuffersToGpu TAIL (render thread, after MC's copies were submitted). */
	public static void onUploadsDone(SectionRenderDispatcher dispatcher) {
		if (instance != null) instance.update(dispatcher);
	}

	/** SectionRenderDispatcher.dispose HEAD: the heaps go away without per-mesh releases. */
	public static void onDispose() {
		if (instance == null) return;
		for (Entry[] arr : instance.entries.values()) for (Entry e : arr) if (e != null) instance.drop(e);
		instance.entries.clear();
		instance.pending.clear();
		instance.uploaded.clear();
		released.clear();
	}

	/** Client shutdown: everything through MC's destroy queue, which the device teardown drains. */
	public static void shutdown() {
		RtScene s = instance;
		if (s == null) return;
		onDispose();
		s.scratch.destroyLater();
		if (s.indices != null) s.indices.destroyLater();
		for (int r = 0; r < RING; r++) if (s.instances[r] != null) s.instances[r].destroyLater();
		if (s.sections != null) s.sections.destroyLater();
		if (s.tlas != 0) {
			long t = s.tlas;
			s.ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(s.ctx.vk, t, null));
			s.tlasStorage.destroyLater();
			s.tlasScratch.destroyLater();
		}
		s.timer.close();
		instance = null;
		EmrionShadersClient.LOG.info("RT: scene destroyed");
	}

	// ---- BLAS ---------------------------------------------------------------------------------------------------------------

	private void update(SectionRenderDispatcher dispatcher) {
		for (Entry e : uploaded) {
			var slice = dispatcher.getRenderSectionSlice(e.mesh, e.layer);
			var draw = e.mesh.getSectionDraw(e.layer);
			if (slice == null || draw == null || !(slice.vertexBuffer() instanceof VulkanGpuBuffer heap)) continue;
			e.quads = draw.indexCount() / 6;
			// heap without our usage bits, or a slice too small for EMRION_BLOCK quads (a vanilla-format mesh): never read past it
			if (e.quads == 0 || (heap.usage() & Formats.USAGE_RT_INPUT) == 0 || slice.vertexBufferOffset() + e.quads * 4L * STRIDE > heap.size()) continue;
			e.vertexAddr = address(heap.vkBuffer()) + slice.vertexBufferOffset();
			Entry[] arr = entries.computeIfAbsent(e.mesh, k -> new Entry[LAYERS]);
			if (arr[e.layer.ordinal()] != null) drop(arr[e.layer.ordinal()]);
			arr[e.layer.ordinal()] = e;
			pending.add(e);
		}
		uploaded.clear();
		for (SectionMesh m; (m = released.poll()) != null; ) {
			Entry[] arr = entries.remove(m);
			if (arr != null) for (Entry e : arr) if (e != null) drop(e);
		}

		timerSlot = (timerSlot + 1) % RING;
		timer.beginFrame();
		if (timed[timerSlot] && timer.nanosSinceFirst(1) > 0) {
			gpuBuildNs += timer.nanosSinceFirst(1);
			maxFrameBuildNs = Math.max(maxFrameBuildNs, timer.nanosSinceFirst(1));
		}
		timed[timerSlot] = false;
		if (!pending.isEmpty()) {
			VkCommandBuffer cb = RawCommands.begin(ctx);
			timer.mark(cb);
			// MC's staging copies of this upload come first in the frame: make them visible to the builds
			Barriers.memory(cb, Barriers.TRANSFER, Barriers.TRANSFER_WRITE, AS_BUILD, AS_READ | org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT);
			buildPending(cb);
			timer.mark(cb);
			timed[timerSlot] = true;
			Profiler.mark(cb, "RT: BLAS builds");
			RawCommands.submit(ctx, cb);
		}
		log();
	}

	private void buildPending(VkCommandBuffer cb) {
		List<Entry> batch = new ArrayList<>();
		long tris = 0, scratchOffset = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack);
			var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
			var create = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default();
			var addrInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default();
			LongBuffer p = stack.mallocLong(1);
			var maxPrims = stack.mallocInt(1);
			while (!pending.isEmpty() && tris < BUDGET) {
				Entry e = pending.poll();
				if (e.removed) continue;
				ensureIndices(e.quads);
				fillGeometry(geom.get(0), e);
				blasInfo(info, geom, 0, 0);
				vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, maxPrims.put(0, e.quads * 2), sizes);
				long scratchSize = align(sizes.buildScratchSize(), scratchAlign);
				if (scratchSize > SCRATCH_SIZE) { // cannot happen for a 16³ section; keep it out rather than overflow
					EmrionShadersClient.LOG.warn("RT: section BLAS needs {} B scratch, skipped", scratchSize);
					continue;
				}
				if (scratchOffset + scratchSize > SCRATCH_SIZE) { // scratch ring wraps: finish the batch, then reuse from 0
					record(cb, batch);
					Barriers.memory(cb, AS_BUILD, AS_WRITE | AS_READ, AS_BUILD, AS_WRITE | AS_READ);
					batch.clear();
					scratchOffset = 0;
				}
				e.blasSize = sizes.accelerationStructureSize();
				e.storage = new RawBuffer(ctx, e.blasSize, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt blas");
				create.buffer(e.storage.handle).offset(0).size(e.blasSize).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
				check(vkCreateAccelerationStructureKHR(ctx.vk, create, null, p), "vkCreateAccelerationStructureKHR");
				e.blas = p.get(0);
				e.blasAddr = vkGetAccelerationStructureDeviceAddressKHR(ctx.vk, addrInfo.accelerationStructure(e.blas));
				e.scratchAddr = address(scratch.handle) + scratchOffset;
				scratchOffset += scratchSize;
				batch.add(e);
				tris += e.quads * 2L;
				blasBytes += e.blasSize;
				triangles += e.quads * 2L;
				built++;
			}
		}
		record(cb, batch);
	}

	/** One vkCmdBuildAccelerationStructuresKHR over the batch (heap-allocated structs: a batch can hold thousands). */
	private void record(VkCommandBuffer cb, List<Entry> batch) {
		int n = batch.size();
		if (n == 0) return;
		var geoms = VkAccelerationStructureGeometryKHR.calloc(n);
		var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(n);
		var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(n);
		PointerBuffer pRanges = MemoryUtil.memAllocPointer(n);
		try {
			for (int i = 0; i < n; i++) {
				Entry e = batch.get(i);
				fillGeometry(geoms.get(i), e);
				blasInfo(infos.get(i), VkAccelerationStructureGeometryKHR.create(geoms.address(i), 1), e.blas, e.scratchAddr);
				ranges.get(i).primitiveCount(e.quads * 2);
				pRanges.put(i, ranges.address(i));
			}
			vkCmdBuildAccelerationStructuresKHR(cb, infos, pRanges);
		} finally {
			geoms.free();
			infos.free();
			ranges.free();
			MemoryUtil.memFree(pRanges);
		}
	}

	private void fillGeometry(VkAccelerationStructureGeometryKHR g, Entry e) {
		g.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(e.layer == ChunkSectionLayer.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
		var t = g.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(STRIDE)
				.maxVertex(e.quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32);
		t.vertexData().deviceAddress(e.vertexAddr);
		t.indexData().deviceAddress(address(indices.handle));
	}

	private static void blasInfo(VkAccelerationStructureBuildGeometryInfoKHR info, VkAccelerationStructureGeometryKHR.Buffer geom, long dst, long scratchAddr) {
		info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
				.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).dstAccelerationStructure(dst).pGeometries(geom).geometryCount(1);
		info.scratchData().deviceAddress(scratchAddr);
	}

	/** RtQuadIndices: 0,1,2, 2,3,0 + 4k (MC's quad winding) for at least {@code quads} quads; grown by doubling. */
	private void ensureIndices(int quads) {
		if (quads <= indexQuads) return;
		if (indices != null) indices.destroyLater(); // builds already recorded keep it alive through the destroy queue
		int n = Math.max(65536, Integer.highestOneBit(quads - 1) << 1);
		indices = new RawBuffer(ctx, n * 24L, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "rt quad indices");
		for (int q = 0; q < n; q++) {
			long a = indices.mapped + q * 24L;
			int v = q * 4;
			MemoryUtil.memPutInt(a, v);
			MemoryUtil.memPutInt(a + 4, v + 1);
			MemoryUtil.memPutInt(a + 8, v + 2);
			MemoryUtil.memPutInt(a + 12, v + 2);
			MemoryUtil.memPutInt(a + 16, v + 3);
			MemoryUtil.memPutInt(a + 20, v);
		}
		indexQuads = n;
	}

	private void drop(Entry e) {
		e.removed = true;
		if (e.blas == 0) return;
		long blas = e.blas;
		ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(ctx.vk, blas, null));
		e.storage.destroyLater();
		blasBytes -= e.blasSize;
		triangles -= e.quads * 2L;
		e.blas = 0;
	}

	// ---- TLAS ---------------------------------------------------------------------------------------------------------------

	/**
	 * FrameExecutor.levelStart, in its raw command buffer (no MC render pass open): rebuilds the TLAS over every built BLAS
	 * whose mesh is its section's current one. Transforms are {@code origin - camera} in double, then float.
	 */
	public void buildTlas(VkCommandBuffer cb) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		frame = (frame + 1) % RING;
		if ((++wantCounter & 7) == 0) wantSections(cam);
		long inst = instances[frame].mapped, sec = sections.mapped + (long) frame * CAPACITY * 16;
		double maxDist = RtConfig.distanceBlocks();
		int i = 0;
		for (Entry[] arr : entries.values()) {
			for (Entry e : arr) {
				if (e == null || e.blas == 0 || e.section.getSectionMesh() != e.mesh) continue; // a newer mesh is not complete yet
				if (maxDist > 0 && Math.max(Math.abs(e.x + 8 - cam.x), Math.abs(e.z + 8 - cam.z)) > maxDist + 8) continue;
				if (i == CAPACITY) {
					if (!overflowWarned) EmrionShadersClient.LOG.warn("RT: more than {} instances, the rest is left out (-Demrion.rt.maxInstances)", CAPACITY);
					overflowWarned = true;
					break;
				}
				long a = inst + (long) i * VkAccelerationStructureInstanceKHR.SIZEOF;
				// 3x4 row-major: identity rotation, camera-relative translation
				MemoryUtil.memPutFloat(a, 1f); MemoryUtil.memPutFloat(a + 4, 0f); MemoryUtil.memPutFloat(a + 8, 0f);
				MemoryUtil.memPutFloat(a + 12, (float) (e.x - cam.x));
				MemoryUtil.memPutFloat(a + 16, 0f); MemoryUtil.memPutFloat(a + 20, 1f); MemoryUtil.memPutFloat(a + 24, 0f);
				MemoryUtil.memPutFloat(a + 28, (float) (e.y - cam.y));
				MemoryUtil.memPutFloat(a + 32, 0f); MemoryUtil.memPutFloat(a + 36, 0f); MemoryUtil.memPutFloat(a + 40, 1f);
				MemoryUtil.memPutFloat(a + 44, (float) (e.z - cam.z));
				int mask = e.layer == ChunkSectionLayer.TRANSLUCENT ? 0x02 : 0x01;
				MemoryUtil.memPutInt(a + 48, (frame * CAPACITY + i) | mask << 24); // instanceCustomIndex = section table entry
				MemoryUtil.memPutInt(a + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
				MemoryUtil.memPutLong(a + 56, e.blasAddr);
				long s = sec + i * 16L; // EmrionRtSections: uvec2 vertexAddr, uint layer, uint pad
				MemoryUtil.memPutLong(s, e.vertexAddr);
				MemoryUtil.memPutInt(s + 8, e.layer.ordinal());
				MemoryUtil.memPutInt(s + 12, 0);
				i++;
			}
		}
		instanceCount = i;
		// previous frame's ray queries and BLAS builds (WAR on the TLAS, RAW on BLAS) before this build
		Barriers.memory(cb, AS_BUILD | Barriers.COMPUTE | Barriers.FRAGMENT, AS_WRITE | AS_READ, AS_BUILD, AS_WRITE | AS_READ);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			geom.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).geometry().instances().sType$Default().arrayOfPointers(false)
					.data().deviceAddress(address(instances[frame].handle));
			var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
					.dstAccelerationStructure(tlas).pGeometries(geom).geometryCount(1);
			info.scratchData().deviceAddress(align(address(tlasScratch.handle), scratchAlign));
			var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(i);
			vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range));
		}
		Barriers.memory(cb, AS_BUILD, AS_WRITE, Barriers.COMPUTE | Barriers.FRAGMENT, AS_READ | Barriers.STORAGE_READ);
		tlasValid = true;
	}

	private int wantCounter;

	/**
	 * RT sees everything within the RT distance, not only what the camera's visibility graph reached: asks for those sections to
	 * be compiled (ForcedSections; every 8th frame, a compile takes longer than that anyway).
	 */
	private static void wantSections(Vec3 cam) {
		var area = Minecraft.getInstance().levelRenderer.viewArea();
		if (area == null) return;
		var center = area.getCameraSectionPos();
		int r = area.getViewDistance();
		double maxDist = RtConfig.distanceBlocks();
		if (maxDist > 0) r = Math.min(r, (int) Math.ceil(maxDist / 16));
		for (int sx = center.x() - r; sx <= center.x() + r; sx++)
			for (int sz = center.z() - r; sz <= center.z() + r; sz++)
				for (int sy = area.minSectionY(); sy < area.maxSectionY(); sy++) ForcedSections.want(SectionPos.asLong(sx, sy, sz));
	}

	private void ensureCapacity() {
		int cap = CAPACITY;
		for (int r = 0; r < RING; r++)
			instances[r] = new RawBuffer(ctx, (long) cap * VkAccelerationStructureInstanceKHR.SIZEOF,
					VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true, "rt instances");
		sections = new RawBuffer(ctx, RING * (long) cap * 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt section table");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			geom.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).geometry().instances().sType$Default();
			var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
			var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, stack.ints(cap), sizes);
			tlasBytes = sizes.accelerationStructureSize();
			tlasStorage = new RawBuffer(ctx, tlasBytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt tlas");
			tlasScratch = new RawBuffer(ctx, sizes.buildScratchSize() + scratchAlign, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt tlas scratch");
			LongBuffer p = stack.mallocLong(1);
			check(vkCreateAccelerationStructureKHR(ctx.vk, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(tlasStorage.handle)
					.size(tlasBytes).type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR), null, p), "vkCreateAccelerationStructureKHR");
			tlas = p.get(0);
			tlasAddr = vkGetAccelerationStructureDeviceAddressKHR(ctx.vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(tlas));
		}
		EmrionShadersClient.LOG.info("RT: TLAS capacity {} instances ({} KiB)", cap, tlasBytes >> 10);
	}

	/** The TLAS of this frame; 0 before the first {@link #buildTlas}. */
	public long tlas() {
		return tlasValid ? tlas : 0;
	}

	/** The TLAS handle for descriptor sets (fixed for the scene's lifetime; built every frame before any pass reads it). */
	public long tlasHandle() {
		return tlas;
	}

	/** The section table (EmrionRtSections), indexed by instanceCustomIndex; fixed for the scene's lifetime. */
	public RawBuffer sectionTable() {
		return sections;
	}

	// ---- helpers ------------------------------------------------------------------------------------------------------------

	private long address(long buffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			return vkGetBufferDeviceAddress(ctx.vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
		}
	}

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}

	private void log() {
		long now = System.nanoTime();
		if (now - lastLog < 5_000_000_000L) return;
		lastLog = now;
		long ring = RING * (long) CAPACITY * (VkAccelerationStructureInstanceKHR.SIZEOF + 16);
		EmrionShadersClient.LOG.info("RT: {} BLAS ({} pending), {} triangles, BLAS {} MiB (avg {} B/tri), TLAS {} instances {} KiB, scratch {} MiB, "
						+ "instance+table rings {} MiB; GPU BLAS build time so far {} ms for {} builds, worst frame {} us",
				entriesBuilt(), pending.size(), triangles, blasBytes >> 20, triangles == 0 ? 0 : blasBytes / triangles, instanceCount, tlasBytes >> 10,
				SCRATCH_SIZE >> 20, ring >> 20, gpuBuildNs / 1_000_000, built, maxFrameBuildNs / 1000);
	}

	/** One line for the RT options page. */
	public String summary() {
		return String.format("BLAS: %d MiB, %d section layers, %.1f M triangles; TLAS %d instances", blasBytes >> 20, entriesBuilt(), triangles / 1e6, instanceCount);
	}

	private int entriesBuilt() {
		int n = 0;
		for (Entry[] arr : entries.values()) for (Entry e : arr) if (e != null && e.blas != 0) n++;
		return n;
	}
}
