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
import org.lwjgl.vulkan.VkAccelerationStructureTrianglesOpacityMicromapEXT;
import org.lwjgl.vulkan.VkMicromapUsageEXT;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

/**
 * Acceleration structures over MC's terrain: one BLAS per (section mesh, layer), read in place from MC's vertex heaps, built
 * within a per-frame triangle budget; one TLAS rebuilt every frame with camera-relative transforms (the pack's playerPos).
 * <p>Threading: {@link #onMeshReleased} may run on worker threads (under MC's copyLock); everything else on the render thread.
 * BLAS read their slice only at build time, and releases are applied before every build batch, so a reused slice is safe.
 */
public final class RtScene {
	private static final int STRIDE = 56; // EMRION_BLOCK
	private static final int LAYERS = ChunkSectionLayer.values().length;
	public static final long BUDGET = Long.getLong("emrion.rt.blasBudget", 2_000_000L); // triangles per frame
	/** BLAS per frame: each costs ~25 us of CPU (sizes, allocation, create), so the world join's 2000+ sections take a few frames, not one 60 ms one. */
	public static final int MAX_BUILDS = Integer.getInteger("emrion.rt.blasPerFrame", 400);
	private static final long SCRATCH_SIZE = 64L << 20;
	private static final int RING = UboRing.REGIONS;
	/** Fixed capacity: the TLAS and section table never move, so descriptor sets written once per runtime stay valid. */
	private static final int CAPACITY = Integer.getInteger("emrion.rt.maxInstances", 65536);
	/** The TLAS is rebuilt every frame, so fast build is the default. */
	private static final int TLAS_FLAGS = Boolean.getBoolean("emrion.rt.tlasFastTrace") ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
			: VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR;
	private static final long AS_BUILD = KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
	private static final long AS_READ = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR;
	private static final long AS_WRITE = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;

	private static @Nullable RtScene instance;
	/** Entities in the TLAS (mask 0x04) while an applied effect asks for them; see {@link #setEntities}. */
	@Nullable RtEntities entities;
	private static final ConcurrentLinkedQueue<SectionMesh> released = new ConcurrentLinkedQueue<>();

	/** One BLAS: a (section mesh, layer) in MC's heaps, or (section null) a Sodium section layer with decoded positions. */
	public static final class Entry {
		final SectionRenderDispatcher.@Nullable RenderSection section;
		final @Nullable CompiledSectionMesh mesh;
		final ChunkSectionLayer layer;
		final int x, y, z, format;
		public int quads;
		/** Sodium path: the frame it was queued in (SodiumRt's BLAS wait statistic). */
		public int queued;
		/** Vertex attributes for hit shading (section table xy); on the Sodium path rewritten every frame (arenas defragment). */
		public long vertexAddr;
		/** BLAS build input: R32G32B32 positions at posAddr with posStride. */
		public long posAddr;
		public int posStride = STRIDE;
		long blas, blasAddr, blasSize, scratchAddr;
		@Nullable RawBuffer storage;
		/** Cutout layers with opacity micromaps (RtOmm): the micromap and this layer's per-triangle index into it. */
		RtOmm.@Nullable Map omm;
		@Nullable RawBuffer ommIndices;
		public boolean removed;

		Entry(SectionRenderDispatcher.RenderSection section, CompiledSectionMesh mesh, ChunkSectionLayer layer) {
			this.section = section;
			this.mesh = mesh;
			this.layer = layer;
			var o = section.getRenderOrigin();
			x = o.getX();
			y = o.getY();
			z = o.getZ();
			format = 0;
		}

		/** A Sodium section layer (EmrionChunkVertex) with its origin in world blocks. */
		public Entry(ChunkSectionLayer layer, int x, int y, int z, int quads) {
			section = null;
			mesh = null;
			this.layer = layer;
			this.x = x;
			this.y = y;
			this.z = z;
			this.quads = quads;
			format = 1;
		}

		public boolean built() {
			return blas != 0;
		}
	}

	private final VulkanContext ctx = VulkanContext.get();
	private final long scratchAlign;
	private final List<Entry> uploaded = new ArrayList<>();
	private final Map<SectionMesh, Entry[]> entries = new IdentityHashMap<>();
	private final ArrayDeque<Entry> pending = new ArrayDeque<>();
	/** Sodium path: this frame's built section layers, written into the TLAS after MC's (empty there) entries. */
	public final List<Entry> external = new ArrayList<>();
	private final dev.emrion.shaders.mod.compat.sodium.@Nullable SodiumRt sodium;
	private final RawBuffer scratch;
	private final RtLights lights = new RtLights();
	/** Opacity micromaps where the device has them, else null. */
	private final @Nullable RtOmm omm;
	/** RtConfig.omm as the current BLAS were built; a change rebuilds all sections. */
	private boolean ommOn = RtConfig.omm;
	private @Nullable RawBuffer indices;
	private int indexQuads;
	// TLAS: fixed capacity; instance + section table rings (host-written per frame)
	private long tlas, tlasAddr;
	private @Nullable RawBuffer tlasStorage, tlasScratch;
	private final RawBuffer[] instances = new RawBuffer[RING];
	/** EmrionRtSections: RING regions of CAPACITY entries; instanceCustomIndex = region * CAPACITY + slot. */
	private @Nullable RawBuffer sections;
	private boolean overflowWarned, multiBatchLogged;
	private int batchesSinceTlas, frameBuilds;
	private long frameTris; // this frame's BLAS builds over all its upload batches (BUDGET, MAX_BUILDS are per frame)
	private int frame;
	private boolean tlasValid;
	// stats
	private long blasBytes, triangles, built, gpuBuildNs, maxFrameBuildNs, tlasBytes;
	private int instanceCount, sectionInstances;
	private boolean deferEntities;
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
		scratch = new RawBuffer(ctx, SCRATCH_SIZE + scratchAlign, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt blas scratch");
		ensureCapacity();
		omm = RtOmm.supported ? new RtOmm(this) : null;
		sodium = dev.emrion.shaders.mod.compat.SodiumTerrain.LOADED ? new dev.emrion.shaders.mod.compat.sodium.SodiumRt(this) : null;
		RtLights.collecting = true;
		EmrionShadersClient.LOG.info("RT: scene created, TLAS capacity {} instances, BLAS budget {} triangles/frame, scratch alignment {}", CAPACITY, BUDGET, scratchAlign);
	}

	public static @Nullable RtScene get() {
		return instance;
	}

	/** Creates the scene and rebuilds all chunks so every mesh is uploaded again and gets its BLAS (MC keeps no CPU copy). */
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
			if (!RtCapability.DEBUG) return; // only an RT runtime or the debug view create the scene
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
		RtLights.clear();
	}

	/** Client shutdown: everything through MC's destroy queue, which the device teardown drains. */
	public static void shutdown() {
		RtScene s = instance;
		if (s == null) return;
		onDispose();
		s.scratch.destroyLater();
		if (s.indices != null) s.indices.destroyLater();
		if (s.entities != null) s.entities.destroy();
		s.entities = null;
		for (int r = 0; r < RING; r++) if (s.instances[r] != null) s.instances[r].destroyLater();
		if (s.sections != null) s.sections.destroyLater();
		if (s.tlas != 0) {
			long t = s.tlas;
			s.ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(s.ctx.vk, t, null));
			s.tlasStorage.destroyLater();
			s.tlasScratch.destroyLater();
		}
		s.timer.close();
		if (s.sodium != null) s.sodium.destroy();
		if (s.omm != null) s.omm.destroy();
		RtLights.collecting = false;
		s.lights.destroyLater();
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
			e.vertexAddr = e.posAddr = address(heap.vkBuffer()) + slice.vertexBufferOffset();
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

		if (!pending.isEmpty() && frameTris < BUDGET && frameBuilds < MAX_BUILDS) {
			// only a frame's first batch is timed: the timer ring has one query pair per frame, a second batch would reuse queries in flight
			boolean time = batchesSinceTlas == 0;
			if (time) {
				timerSlot = (timerSlot + 1) % RING;
				timer.beginFrame();
				if (timed[timerSlot] && timer.nanosSinceFirst(1) > 0) {
					gpuBuildNs += timer.nanosSinceFirst(1);
					maxFrameBuildNs = Math.max(maxFrameBuildNs, timer.nanosSinceFirst(1));
				}
			}
			VkCommandBuffer cb = RawCommands.begin(ctx);
			if (time) timer.mark(cb);
			// make MC's staging copies visible to the builds. AS_BUILD too: scratch restarts at 0 every batch and MC can upload
			// several times per frame, so two batches would race on scratch and corrupt BLAS (GPU hang).
			Barriers.memory(cb, Barriers.TRANSFER | AS_BUILD, Barriers.TRANSFER_WRITE | AS_WRITE | AS_READ, AS_BUILD,
					AS_READ | AS_WRITE | org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT);
			if (++batchesSinceTlas == 2 && !multiBatchLogged) {
				multiBatchLogged = true;
				EmrionShadersClient.LOG.info("RT: several BLAS batches in one frame (MC staging buffer full)");
			}
			long b0 = built;
			frameTris += buildPending(cb, pending, BUDGET - frameTris, MAX_BUILDS - frameBuilds);
			frameBuilds += (int) (built - b0);
			// the builds read MC's vertex heaps: a later upload into a freed slice must wait for them (WAR)
			Barriers.memory(cb, AS_BUILD, AS_READ, Barriers.TRANSFER, Barriers.TRANSFER_WRITE);
			if (time) {
				timer.mark(cb);
				timed[timerSlot] = true;
			}
			Profiler.mark(cb, "RT: BLAS builds");
			RawCommands.submit(ctx, cb);
		}
		log();
	}

	/** Records BLAS builds for queued entries until {@code budget} triangles or {@code maxBuilds} builds; the rest stays queued. Returns the triangles built. */
	public long buildPending(VkCommandBuffer cb, ArrayDeque<Entry> pending, long budget, int maxBuilds) {
		long start = System.nanoTime(), builtBefore = built;
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
			var ommGeom = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(stack);
			var ommUsage = VkMicromapUsageEXT.calloc(1, stack);
			RtOmm.Map micromap = omm != null && ommOn && !pending.isEmpty() ? omm.prepare(cb) : null;
			while (!pending.isEmpty() && tris < budget && built - builtBefore < maxBuilds) {
				Entry e = pending.poll();
				if (e.removed) continue;
				ensureIndices(e.quads);
				if (micromap != null && e.layer == ChunkSectionLayer.CUTOUT) omm.attach(e, micromap);
				fillGeometry(geom.get(0), e, ommGeom, ommUsage);
				blasInfo(info, geom, 0, 0);
				vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, maxPrims.put(0, e.quads * 2), sizes);
				long scratchSize = align(sizes.buildScratchSize(), scratchAlign);
				if (scratchSize > SCRATCH_SIZE) { // cannot happen for a 16³ section; keep it out rather than overflow
					EmrionShadersClient.LOG.warn("RT: section BLAS needs {} B scratch, skipped", scratchSize);
					if (omm != null) omm.release(e);
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
				e.scratchAddr = align(address(scratch.handle), scratchAlign) + scratchOffset; // the buffer base itself may be less aligned
				scratchOffset += scratchSize;
				batch.add(e);
				tris += e.quads * 2L;
				blasBytes += e.blasSize;
				triangles += e.quads * 2L;
				built++;
			}
		}
		record(cb, batch);
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.BLAS, start, built - builtBefore);
		return tris;
	}

	/** One vkCmdBuildAccelerationStructuresKHR over the batch (heap-allocated structs: a batch can hold thousands). */
	private void record(VkCommandBuffer cb, List<Entry> batch) {
		int n = batch.size();
		if (n == 0) return;
		dev.emrion.shaders.mod.debug.HitchLog.count(dev.emrion.shaders.mod.debug.HitchLog.BLAS_BATCH, 1);
		var geoms = VkAccelerationStructureGeometryKHR.calloc(n);
		var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(n);
		var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(n);
		var ommGeoms = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(n);
		var ommUsages = VkMicromapUsageEXT.calloc(n);
		PointerBuffer pRanges = MemoryUtil.memAllocPointer(n);
		try {
			if (omm != null) omm.dispatchIndices(cb, batch);
			for (int i = 0; i < n; i++) {
				Entry e = batch.get(i);
				fillGeometry(geoms.get(i), e, ommGeoms.get(i), VkMicromapUsageEXT.create(ommUsages.address(i), 1));
				blasInfo(infos.get(i), VkAccelerationStructureGeometryKHR.create(geoms.address(i), 1), e.blas, e.scratchAddr);
				ranges.get(i).primitiveCount(e.quads * 2);
				pRanges.put(i, ranges.address(i));
			}
			vkCmdBuildAccelerationStructuresKHR(cb, infos, pRanges);
			if (omm != null) for (Entry e : batch) omm.built(e);
		} finally {
			geoms.free();
			infos.free();
			ranges.free();
			ommGeoms.free();
			ommUsages.free();
			MemoryUtil.memFree(pRanges);
		}
	}

	private void fillGeometry(VkAccelerationStructureGeometryKHR g, Entry e, VkAccelerationStructureTrianglesOpacityMicromapEXT ommGeom, VkMicromapUsageEXT.Buffer ommUsage) {
		g.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(e.layer == ChunkSectionLayer.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR
				: e.layer == ChunkSectionLayer.TRANSLUCENT ? VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR : 0); // RTX tints once per glass face
		var t = g.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(e.posStride)
				.maxVertex(e.quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32);
		t.vertexData().deviceAddress(e.posAddr);
		t.indexData().deviceAddress(address(indices.handle));
		t.pNext(0);
		if (e.ommIndices != null) RtOmm.chain(t, e, ommGeom, ommUsage, address(e.ommIndices.handle));
	}

	private static void blasInfo(VkAccelerationStructureBuildGeometryInfoKHR info, VkAccelerationStructureGeometryKHR.Buffer geom, long dst, long scratchAddr) {
		info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
				.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).dstAccelerationStructure(dst).pGeometries(geom).geometryCount(1);
		info.scratchData().deviceAddress(scratchAddr);
	}

	/** RtQuadIndices: 0,1,2, 2,3,0 + 4k (MC's quad winding) for at least {@code quads} quads; grown by doubling. */
	void ensureIndices(int quads) {
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

	public void drop(Entry e) {
		e.removed = true;
		if (omm != null) omm.release(e);
		if (e.blas == 0) return;
		long blas = e.blas;
		ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(ctx.vk, blas, null));
		e.storage.destroyLater();
		blasBytes -= e.blasSize;
		triangles -= e.quads * 2L;
		e.blas = 0;
	}

	// ---- TLAS ---------------------------------------------------------------------------------------------------------------

	/** levelStart, outside any MC render pass: rebuilds the TLAS over every BLAS whose mesh is its section's current one. */
	public void buildTlas(VkCommandBuffer cb) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		if (omm != null && RtConfig.omm != ommOn) {
			ommOn = RtConfig.omm;
			rebuildSections();
		}
		frame = (frame + 1) % RING;
		batchesSinceTlas = frameBuilds = 0;
		frameTris = 0;
		if (sodium != null) {
			sodium.update(cb, cam);
			Profiler.mark(cb, "RT: BLAS builds");
		} else if ((++wantCounter & 7) == 0) wantSections(cam);
		long inst = instances[frame].mapped, sec = sections.mapped + (long) frame * CAPACITY * 16;
		// entities captured in this frame's shadow pass are added after it (refreshEntities); here they would be a frame old
		deferEntities = entities != null && entities.expectShadowCapture() && !deferEntities;
		if (entities != null && !deferEntities) {
			entities.prepare(cb, frame);
			Profiler.mark(cb, "RT: entity BLAS");
		}
		long start = System.nanoTime();
		double maxDist = RtConfig.distanceBlocks();
		int i = 0;
		for (Entry[] arr : entries.values())
			for (Entry e : arr)
				if (e != null && e.blas != 0 && e.section.getSectionMesh() == e.mesh) i = instance(inst, sec, i, e, cam, maxDist); // else a newer mesh is not complete yet
		for (Entry e : external) i = instance(inst, sec, i, e, cam, maxDist);
		sectionInstances = i;
		if (entities != null && !deferEntities) i = entities.writeInstances(inst, sec, i, CAPACITY, frame * CAPACITY, cam);
		recordTlasBuild(cb, i);
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.TLAS, start);
		start = System.nanoTime();
		lights.upload(cb, cam, frame);
		dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.RT_LIGHTS, start);
		tlasValid = true;
	}

	/** After the shadow pass captured this frame's entities: their BLAS and the TLAS again (sections unchanged). */
	public void refreshEntities(VkCommandBuffer cb) {
		if (!deferEntities) return;
		deferEntities = false;
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		entities.prepare(cb, frame);
		entities.keepShadowCapture();
		Profiler.mark(cb, "RT: entity BLAS");
		long inst = instances[frame].mapped, sec = sections.mapped + (long) frame * CAPACITY * 16;
		recordTlasBuild(cb, entities.writeInstances(inst, sec, sectionInstances, CAPACITY, frame * CAPACITY, cam));
		Profiler.mark(cb, "RT: TLAS build (entities)");
	}

	private void recordTlasBuild(VkCommandBuffer cb, int i) {
		instanceCount = i;
		// previous ray queries and BLAS builds (WAR on the TLAS, RAW on BLAS) before this build
		Barriers.memory(cb, AS_BUILD | Barriers.COMPUTE | Barriers.FRAGMENT, AS_WRITE | AS_READ, AS_BUILD, AS_WRITE | AS_READ);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			geom.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).geometry().instances().sType$Default().arrayOfPointers(false)
					.data().deviceAddress(address(instances[frame].handle));
			var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
					.flags(TLAS_FLAGS).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
					.dstAccelerationStructure(tlas).pGeometries(geom).geometryCount(1);
			info.scratchData().deviceAddress(align(address(tlasScratch.handle), scratchAlign));
			var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(i);
			vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range));
		}
		Barriers.memory(cb, AS_BUILD, AS_WRITE, Barriers.COMPUTE | Barriers.FRAGMENT, AS_READ | Barriers.STORAGE_READ);
	}

	/** Writes e's TLAS instance and section-table entry at slot i unless it is beyond the RT distance or the TLAS is full; returns the next slot. */
	private int instance(long inst, long sec, int i, Entry e, Vec3 cam, double maxDist) {
		if (maxDist > 0 && Math.max(Math.abs(e.x + 8 - cam.x), Math.abs(e.z + 8 - cam.z)) > maxDist + 8) return i;
		if (i == CAPACITY) {
			if (!overflowWarned) EmrionShadersClient.LOG.warn("RT: more than {} instances, the rest is left out (-Demrion.rt.maxInstances)", CAPACITY);
			overflowWarned = true;
			return i;
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
		long s = sec + i * 16L; // EmrionRtSections: uvec2 vertexAddr, uint layer, uint vertex format
		MemoryUtil.memPutLong(s, e.vertexAddr);
		MemoryUtil.memPutInt(s + 8, e.layer.ordinal());
		MemoryUtil.memPutInt(s + 12, e.format);
		return i + 1;
	}

	private int wantCounter;

	/** RT sees everything within the RT distance, not only what the visibility graph reached: forces those sections to compile. */
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
					.flags(TLAS_FLAGS).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
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

	long indexAddress() {
		return address(indices.handle);
	}

	long scratchAlign() {
		return scratchAlign;
	}

	/** All sections are meshed and built again (BLAS inputs changed: the opacity micromap switch, the any-hit ids). */
	private static void rebuildSections() {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.levelExtractor.allChanged());
	}

	/** Block ids the applied shaders decide per id (Manifest.Rt.anyHitIds): never opaque in a micromap. */
	public RtScene setAnyHitIds(List<Integer> ids) {
		if (omm != null && omm.setAnyHitIds(ids) && ommOn) rebuildSections();
		return this;
	}

	/** Entities go into the TLAS only if an applied effect asked for them ({@code <effect>:entities}). */
	public RtScene setEntities(boolean on) {
		if (on && entities == null) entities = new RtEntities(this);
		else if (!on && entities != null) {
			entities.destroy();
			entities = null;
		}
		return this;
	}

	/** The entity texture atlas view (builtin emrion_rt_entity_atlas), 0 without entities. */
	public long entityAtlasView() {
		return entities == null ? 0 : entities.atlas.view;
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

	/** The block-light grid (EmrRtLights), refilled every frame at levelStart; fixed for the scene's lifetime. */
	public RawBuffer lightBuffer() {
		return lights.buffer;
	}

	// ---- helpers ------------------------------------------------------------------------------------------------------------

	public long address(long buffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			return vkGetBufferDeviceAddress(ctx.vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
		}
	}

	static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}

	public void log() {
		long now = System.nanoTime();
		if (now - lastLog < 5_000_000_000L) return;
		lastLog = now;
		long ring = RING * (long) CAPACITY * (VkAccelerationStructureInstanceKHR.SIZEOF + 16);
		EmrionShadersClient.LOG.info("RT: {} BLAS ({} pending, longest wait {} frames), {} triangles, BLAS {} MiB (avg {} B/tri), TLAS {} instances {} KiB, scratch {} MiB, "
						+ "instance+table rings {} MiB; GPU BLAS build time so far {} ms for {} builds, worst frame {} us",
				entriesBuilt(), pending.size() + (sodium == null ? 0 : sodium.pendingCount()), sodium == null ? 0 : sodium.takeMaxWait(), triangles, blasBytes >> 20, triangles == 0 ? 0 : blasBytes / triangles, instanceCount, tlasBytes >> 10,
				SCRATCH_SIZE >> 20, ring >> 20, gpuBuildNs / 1_000_000, built, maxFrameBuildNs / 1000);
		if (entities != null) EmrionShadersClient.LOG.info("RT: {}", entities.summary());
		if (omm != null && ommOn) EmrionShadersClient.LOG.info("RT: {}", omm.summary());
	}

	/** One line for the RT options page. */
	public String summary() {
		String s = String.format("BLAS: %d MiB, %d section layers, %.1f M triangles; TLAS %d instances", blasBytes >> 20, entriesBuilt(), triangles / 1e6, instanceCount);
		if (omm != null && ommOn) s += "; " + omm.summary();
		return entities == null ? s : s + "; " + entities.summary();
	}

	private int entriesBuilt() {
		int n = 0;
		for (Entry[] arr : entries.values()) for (Entry e : arr) if (e != null && e.blas != 0) n++;
		return n + (sodium == null ? 0 : sodium.built());
	}
}
