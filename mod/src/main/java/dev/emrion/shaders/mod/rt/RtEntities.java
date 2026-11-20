package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.mixin.StagedDrawAccessor;
import dev.emrion.shaders.mod.runtime.GeometryHooks;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.ComputePipelines;
import dev.emrion.shaders.mod.vk.Descriptors;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.RawImage;
import dev.emrion.shaders.mod.vk.ShaderModules;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import dev.emrion.shaders.mod.vertex.EntityIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
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
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Entities, block entities and the player in the TLAS (mask 0x04; the local player and what it carries 0x08 in first person,
 * where its body is not drawn: shadow rays see it, primary-visibility traces skip it). Shadow-casting entity draws are copied on the CPU while a
 * feature frame is prepared; at the next build they go into a ring slot (UVs remapped into {@link #atlas}) and get one BLAS
 * per draw, refitted or rebuilt within a budget. Without a shadow pass the capture is one frame old: the transform corrects
 * for camera motion, not entity motion.
 */
public final class RtEntities {
	static final int KIND_ATLAS = 8, KIND_BLOCKS = 9, MASK = 0x04, MASK_LOCAL_PLAYER = 0x08;
	private static final int STRIDE = 56, ATLAS = 2048, MAX_TEXTURE = 1024, RING = UboRing.REGIONS;
	private static final long BUDGET = Long.getLong("emrion.rt.entityBudget", 1_000_000L); // triangles per frame
	/** Refits of a BLAS before it is rebuilt from scratch (a refit keeps the old tree, which degrades as the pose changes). */
	private static final int REFITS = Integer.getInteger("emrion.rt.entityRefits", 8);
	/** Trace speed over build speed: the builds are tiny, the rays run per pixel. */
	private static final int BUILD_FLAGS = VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
			| (Boolean.getBoolean("emrion.rt.entityFastBuild") ? VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR : VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
	private static final long AS_BUILD = KHRSynchronization2.VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
	private static final long AS_RW = KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR
			| KHRSynchronization2.VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;
	private static final int[] NO_FIT = new int[0];

	// ---- capture (render thread; static: the hooks run whether or not a scene exists) -------------------------------------

	private record Captured(int offset, int vertices, String type, GpuTextureView texture, boolean player) {}

	private static boolean capturing, shadowCaptured;
	/** What the last shadow frame submitted (the pack's shadowEntities / shadowBlockEntities). */
	private static boolean shadowHasEntities = true, shadowHasBlockEntities = true;
	/** The main frame adds what the shadow frame left out (only those draws); the next shadow capture keeps them. */
	private static boolean extraOnly, extraPending;
	private static final IdentityHashMap<StagedVertexBuffer.Draw, PreparedRenderType> drawTypes = new IdentityHashMap<>();
	private static ByteBuffer cap = MemoryUtil.memAlloc(1 << 20);
	private static final List<Captured> captured = new ArrayList<>();
	private static double capX, capY, capZ;

	/** Around the main feature frame's prepareFrame. */
	public static void begin(boolean shadow, Vec3 cam) {
		begin(shadow, cam, true, true);
	}

	/**
	 * Around a feature frame's prepareFrame. The shadow pass's frame (all casters in shadow range) wins over the main one; what
	 * the pack keeps out of its shadow map ({@code entities}, {@code blockEntities} false) the main frame still captures, a
	 * frame old: the GI primary must hit every drawn surface (a chest missing from it took the GI of the floor behind it).
	 */
	public static void begin(boolean shadow, Vec3 cam, boolean entities, boolean blockEntities) {
		RtScene s = RtScene.get();
		if (s == null || s.entities == null) return;
		extraOnly = !shadow && shadowCaptured;
		if (extraOnly && shadowHasEntities && shadowHasBlockEntities) return;
		capturing = true;
		if (shadow) {
			shadowCaptured = true;
			shadowHasEntities = entities;
			shadowHasBlockEntities = blockEntities;
			if (extraPending) moveCaptured(cam); // last main frame's extras, now relative to this camera
			else captured.clear();
			extraPending = false;
		} else if (extraOnly) {
			if (!captured.isEmpty()) return; // this frame's shadow capture is not consumed yet: same camera, consumed with it
			extraPending = true;
		} else captured.clear();
		if (captured.isEmpty()) cap.clear();
		capX = cam.x;
		capY = cam.y;
		capZ = cam.z;
	}

	/** Shifts the captured vertices (camera-relative) from the capture camera to {@code cam}. */
	private static void moveCaptured(Vec3 cam) {
		float dx = (float) (capX - cam.x), dy = (float) (capY - cam.y), dz = (float) (capZ - cam.z);
		long base = MemoryUtil.memAddress0(cap);
		for (Captured c : captured)
			for (long v = base + c.offset(), e = v + (long) c.vertices() * STRIDE; v < e; v += STRIDE) {
				MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(v) + dx);
				MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(v + 4) + dy);
				MemoryUtil.memPutFloat(v + 8, MemoryUtil.memGetFloat(v + 8) + dz);
			}
	}

	/** True while a main frame's prepare is captured as the RT entity frame (no shadow-pass capture this frame). */
	public static boolean capturingMainFrame() {
		return capturing && !extraOnly;
	}

	public static void end() {
		capturing = false;
		drawTypes.clear();
	}

	public static void onDraw(StagedVertexBuffer.Draw draw, PreparedRenderType type) {
		if (capturing) drawTypes.put(draw, type);
	}

	/** StagedVertexBuffer.uploadDrawsToBuffers HEAD: every draw's vertices are still in its CPU slices. */
	public static void onUpload(List<StagedVertexBuffer.Draw> draws) {
		if (!capturing) return;
		for (StagedVertexBuffer.Draw d : draws) {
			PreparedRenderType type = drawTypes.get(d);
			StagedDrawAccessor a = (StagedDrawAccessor) d;
			int n = a.emrion$vertexCount();
			if (type == null || n < 4 || a.emrion$format() != EmrionVertexFormats.ENTITY || a.emrion$topology() != PrimitiveTopology.QUADS
					|| type.textures().isEmpty()) continue;
			boolean blockEntity = type.name().startsWith(GeometryHooks.BLOCK_ENTITY_PREFIX);
			String name = blockEntity ? type.name().substring(GeometryHooks.BLOCK_ENTITY_PREFIX.length()) : type.name();
			if (!casts(name) || extraOnly && (blockEntity ? shadowHasBlockEntities : shadowHasEntities)) continue;
			GpuTextureView tex = null;
			for (var t : type.textures()) if (t.name().equals("Sampler0")) tex = t.textureView();
			if (tex == null) continue;
			int bytes = (n & ~3) * STRIDE;
			if (cap.remaining() < bytes) {
				int need = cap.position() + bytes;
				cap = MemoryUtil.memRealloc(cap, Math.max(need, cap.capacity() * 2));
			}
			int offset = cap.position();
			int left = bytes;
			for (ByteBufferBuilder.Result r : a.emrion$slices()) {
				ByteBuffer b = r.byteBuffer();
				int len = Math.min(left, b.remaining());
				MemoryUtil.memCopy(MemoryUtil.memAddress(b), MemoryUtil.memAddress(cap), len);
				cap.position(cap.position() + len);
				left -= len;
			}
			if (left != 0) { // slices shorter than the vertex count says: drop the draw rather than trace garbage
				cap.position(offset);
				continue;
			}
			split(offset, n & ~3, name, tex);
		}
	}

	/** Adds the draw at {@code offset}, its local-player quads (EntityIds.LOCAL_PLAYER_W) moved behind the others as a draw of their own. */
	private static void split(int offset, int vertices, String type, GpuTextureView tex) {
		long base = MemoryUtil.memAddress0(cap) + offset, quad = 4L * STRIDE;
		int quads = vertices / 4, player = 0;
		for (int q = 0; q < quads; q++) if (isPlayer(base + q * quad)) player++;
		if (player == 0 || player == quads) {
			captured.add(new Captured(offset, vertices, type, tex, player != 0));
			return;
		}
		ByteBuffer tmp = MemoryUtil.memAlloc((int) (player * quad));
		try {
			int others = 0, p = 0;
			for (int q = 0; q < quads; q++) {
				long src = base + q * quad;
				if (isPlayer(src)) MemoryUtil.memCopy(src, MemoryUtil.memAddress(tmp) + p++ * quad, quad);
				else MemoryUtil.memCopy(src, base + others++ * quad, quad); // forward, never overtakes src
			}
			MemoryUtil.memCopy(MemoryUtil.memAddress(tmp), base + others * quad, player * quad);
			captured.add(new Captured(offset, others * 4, type, tex, false));
			captured.add(new Captured(offset + (int) (others * quad), player * 4, type, tex, true));
		} finally {
			MemoryUtil.memFree(tmp);
		}
	}

	private static boolean isPlayer(long vertex) {
		return (MemoryUtil.memGetShort(vertex + 54) & EntityIds.LOCAL_PLAYER_W) != 0; // iris_Entity w
	}

	/** Render types whose geometry casts shadows; not emissive layers or blob shadows. */
	static boolean casts(String name) {
		if (name.contains("emissive") || name.equals("entity_shadow")) return false;
		return name.startsWith("entity_") || name.startsWith("armor_") || name.startsWith("item_") || name.equals("banner_pattern");
	}

	/** How far around the camera entities are extracted off screen for the TLAS when no shadow pass captures them. */
	private static final double CASTER_RANGE = 32;
	private static volatile boolean castersOn;
	private static volatile double casterX, casterY, casterZ;

	/**
	 * The frustum MC extracts entities with (LevelExtractor.extractVisibleEntities). Without a shadow pass (native packs) the main
	 * frame is the only capture, so an entity that left the view also left the TLAS and its shadow on screen popped away with
	 * the camera's pitch. Then entities within {@link #CASTER_RANGE} count as visible in every direction (drawn off screen and
	 * clipped: vertex cost only); Sodium's own section test asks {@link #casterNear} too (SodiumWorldRendererMixin).
	 */
	public static net.minecraft.client.renderer.culling.Frustum casterFrustum(net.minecraft.client.renderer.culling.Frustum view, boolean packRunning) {
		RtScene s = RtScene.get();
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		casterX = cam.x;
		casterY = cam.y;
		casterZ = cam.z;
		castersOn = packRunning && s != null && s.entities != null && !s.entities.expectShadowCapture();
		if (!castersOn) return view;
		return new net.minecraft.client.renderer.culling.Frustum(view) {
			@Override
			public boolean isVisible(net.minecraft.world.phys.AABB bb) {
				return super.isVisible(bb) || casterNear(bb);
			}
		};
	}

	/** An entity box near enough to the camera to be extracted for the TLAS whether it is in view or not. */
	public static boolean casterNear(net.minecraft.world.phys.AABB bb) {
		if (!castersOn) return false;
		double dx = Math.max(0, Math.max(bb.minX - casterX, casterX - bb.maxX)), dy = Math.max(0, Math.max(bb.minY - casterY, casterY - bb.maxY)),
				dz = Math.max(0, Math.max(bb.minZ - casterZ, casterZ - bb.maxZ));
		return dx * dx + dy * dy + dz * dz <= CASTER_RANGE * CASTER_RANGE;
	}

	// ---- per scene ----------------------------------------------------------------------------------------------------------

	private static final class Blas {
		long as, addr, size, vertexAddr;
		@Nullable RawBuffer storage;
		int vertices, refits, kind, lastFrame;
		boolean opaque, player;
	}

	private final VulkanContext ctx = VulkanContext.get();
	private final RtScene scene;
	final RawImage atlas;
	private final long setLayout, layout, pipeline;
	private final IdentityHashMap<GpuTexture, int[]> rects = new IdentityHashMap<>();
	private final List<int[]> shelves = new ArrayList<>(); // {y, height, nextX}
	private int shelfBottom;
	private final RawBuffer[] verts = new RawBuffer[RING];
	private @Nullable RawBuffer scratch;
	private final Map<String, Blas> blas = new HashMap<>();
	private final List<Blas> live = new ArrayList<>();
	private double liveX, liveY, liveZ;
	private int frameNo;
	/** Frames between copies of the textures in use into the atlas (they may change in place). */
	private static final int REFRESH_FRAMES = 10;
	// stats
	int draws, builds, refits, dropped, blits;
	long cpuNs;
	long triangles;

	RtEntities(RtScene scene) {
		this.scene = scene;
		atlas = new RawImage(ctx, VK_FORMAT_R8G8B8A8_UNORM, ATLAS, ATLAS, 1, 1, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "rt entity atlas");
		setLayout = new Descriptors.LayoutBuilder().add(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_COMPUTE_BIT)
				.add(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_SHADER_STAGE_COMPUTE_BIT).build(ctx, true);
		layout = Descriptors.pipelineLayout(ctx, 16, setLayout);
		long cs = ShaderModules.create(ctx, ShaderModules.resource("/assets/emberlight/spv/rt_entity_blit.comp.spv"));
		pipeline = ComputePipelines.create(ctx, cs, layout, ctx.pipelineCache().handle);
		ShaderModules.destroy(ctx, cs);
		EmrionShadersClient.LOG.info("RT: entities on (atlas {}², budget {} triangles/frame, {} refits per rebuild)", ATLAS, BUDGET, REFITS);
	}

	void destroy() {
		for (Blas b : blas.values()) free(b);
		blas.clear();
		live.clear();
		for (RawBuffer v : verts) if (v != null) v.destroyLater();
		if (scratch != null) scratch.destroyLater();
		atlas.destroyLater();
		ctx.destroyLater(() -> {
			vkDestroyPipeline(ctx.vk, pipeline, null);
			vkDestroyPipelineLayout(ctx.vk, layout, null);
			vkDestroyDescriptorSetLayout(ctx.vk, setLayout, null);
		});
		captured.clear();
	}

	/**
	 * levelStart, before the TLAS build (same command buffer): consumes the last capture into ring slot {@code slot}, copies new
	 * textures into the atlas and builds or refits the BLAS. Without a capture since the last call, no entity is traced.
	 */
	private boolean expectShadow;

	/** The last capture came from the shadow pass, so the next one probably will: RtScene adds entities after it. */
	boolean expectShadowCapture() {
		return expectShadow;
	}

	/** After a prepare that followed this frame's shadow pass: the main frame must not capture over it before the next one. */
	void keepShadowCapture() {
		shadowCaptured = expectShadow;
	}

	void prepare(VkCommandBuffer cb, int slot) {
		long t0 = System.nanoTime();
		try {
			prepareTimed(cb, slot);
		} finally {
			cpuNs = System.nanoTime() - t0;
			dev.emrion.shaders.mod.debug.HitchLog.add(dev.emrion.shaders.mod.debug.HitchLog.ENTITY_BLAS, t0, builds + refits);
		}
	}

	private void prepareTimed(VkCommandBuffer cb, int slot) {
		frameNo++;
		expectShadow = shadowCaptured;
		shadowCaptured = false;
		live.clear();
		draws = builds = refits = dropped = blits = 0;
		triangles = 0;
		if (captured.isEmpty()) {
			retire();
			return;
		}
		// previous frames' ray queries (BLAS refitted in place, atlas regions rewritten after a reset) before this frame's writes
		Barriers.memory(cb, AS_BUILD | Barriers.COMPUTE | Barriers.FRAGMENT | Barriers.RAYGEN, AS_RW | Barriers.SAMPLED_READ, AS_BUILD | Barriers.COMPUTE, AS_RW | Barriers.STORAGE_WRITE);

		long bytes = captured.getLast().offset() + (long) captured.getLast().vertices() * STRIDE;
		if (verts[slot] == null || verts[slot].size < bytes) {
			if (verts[slot] != null) verts[slot].destroyLater();
			verts[slot] = new RawBuffer(ctx, Math.max(1L << 20, Long.highestOneBit(bytes) << 1), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
					| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt entity vertices");
		}
		long base = verts[slot].mapped, baseAddr = scene.address(verts[slot].handle);
		GpuTexture blocks = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
		placeTextures(cb, blocks);
		Map<String, Integer> seen = new HashMap<>();
		List<Blas> work = new ArrayList<>();
		List<Boolean> update = new ArrayList<>();
		List<Long> offsets = new ArrayList<>();
		long tris = 0, scratchSize = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var geom = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack);
			var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
			var maxPrims = stack.mallocInt(1);
			for (Captured c : captured) {
				if (c.texture().isClosed()) continue;
				int quads = c.vertices() / 4;
				if (tris + quads * 2L > BUDGET) {
					dropped++;
					continue;
				}
				GpuTexture tex = c.texture().texture();
				int[] rect = tex == blocks ? null : rects.get(tex);
				long src = MemoryUtil.memAddress0(cap) + c.offset(), dst = base + c.offset(); // the capture layout is kept: offsets match
				int kind = KIND_BLOCKS;
				if (rect != null && rect != NO_FIT) {
					kind = KIND_ATLAS;
					float sx = rect[2] / (float) ATLAS, sy = rect[3] / (float) ATLAS, ox = rect[0] / (float) ATLAS, oy = rect[1] / (float) ATLAS;
					// remapped in the CPU copy (consumed by this build), so the write-combined ring gets one sequential copy
					for (int v = 0; v < c.vertices(); v++) {
						long o = src + (long) v * STRIDE + 16;
						MemoryUtil.memPutFloat(o, ox + Math.clamp(MemoryUtil.memGetFloat(o), 0f, 1f) * sx);
						MemoryUtil.memPutFloat(o + 4, oy + Math.clamp(MemoryUtil.memGetFloat(o + 4), 0f, 1f) * sy);
					}
				}
				MemoryUtil.memCopy(src, dst, (long) c.vertices() * STRIDE);
				boolean opaque = rect == NO_FIT || c.type().startsWith("entity_solid");
				String key = c.type() + '@' + System.identityHashCode(tex) + (c.player() ? "@p" : "");
				int nth = seen.merge(key, 1, Integer::sum);
				Blas b = blas.computeIfAbsent(key + '#' + nth, k -> new Blas());
				b.vertexAddr = baseAddr + c.offset();
				b.kind = kind;
				b.lastFrame = frameNo;
				b.player = c.player();
				boolean refit = b.as != 0 && b.vertices == c.vertices() && b.opaque == opaque && b.refits < REFITS;
				b.opaque = opaque;
				scene.ensureIndices(quads);
				fill(geom.get(0), b, quads);
				buildInfo(info, geom, refit, 0, 0);
				vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, maxPrims.put(0, quads * 2), sizes);
				if (!refit) {
					b.vertices = c.vertices();
					b.refits = 0;
					if (b.storage == null || b.size < sizes.accelerationStructureSize()) create(b, sizes.accelerationStructureSize());
				} else b.refits++;
				offsets.add(scratchSize);
				scratchSize += align(refit ? sizes.updateScratchSize() : sizes.buildScratchSize());
				work.add(b);
				update.add(refit);
				tris += quads * 2L;
			}
		}
		if (blits > 0) Barriers.storageToRead(cb);
		scratchSize += scene.scratchAlign(); // room to align the buffer base
		if (scratch == null || scratch.size < scratchSize) {
			if (scratch != null) scratch.destroyLater();
			scratch = new RawBuffer(ctx, Math.max(4L << 20, Long.highestOneBit(scratchSize) << 1), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
					| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt entity scratch");
		}
		record(cb, work, update, offsets);
		live.addAll(work);
		liveX = capX;
		liveY = capY;
		liveZ = capZ;
		draws = work.size();
		triangles = tris;
		captured.clear();
		retire();
	}

	/** BLAS of draws that disappeared: kept for a few frames (an entity leaving and coming back), then freed. */
	private void retire() {
		blas.values().removeIf(b -> {
			if (frameNo - b.lastFrame <= 3) return false;
			free(b);
			return true;
		});
	}

	/**
	 * Gives every texture the draws of this frame use a rect before any draw takes its UVs: an atlas reset halfway through
	 * the draws would hand rects that earlier draws already use to later textures. Same skip rules as the BLAS loop.
	 */
	private void placeTextures(VkCommandBuffer cb, GpuTexture blocks) {
		rects.keySet().removeIf(GpuTexture::isClosed);
		List<GpuTextureView> views = new ArrayList<>();
		List<int[]> placed = new ArrayList<>();
		// no change signal for animated item sprites or maps: every few frames the textures in use are copied again
		boolean refresh = frameNo % REFRESH_FRAMES == 0;
		java.util.Set<GpuTexture> refreshed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		boolean reset = false;
		pass:
		for (;;) {
			long tris = 0;
			for (Captured c : captured) {
				if (c.texture().isClosed()) continue;
				long t = c.vertices() / 4 * 2L;
				if (tris + t > BUDGET) continue;
				tris += t;
				GpuTexture tex = c.texture().texture();
				if (tex == blocks) continue;
				int[] had = rects.get(tex);
				if (had != null) {
					if (refresh && had != NO_FIT && refreshed.add(tex)) {
						views.add(c.texture());
						placed.add(had);
					}
					continue;
				}
				if (!reset && !fits(c.texture())) { // atlas full: start over once per frame, re-copying what is used
					rects.clear();
					shelves.clear();
					shelfBottom = 0;
					reset = true;
					views.clear();
					placed.clear();
					refreshed.clear();
					continue pass;
				}
				int[] rect = allocate(c.texture());
				rects.put(tex, rect);
				refreshed.add(tex); // copied below already: a later draw of it must not schedule a second, unordered copy
				if (rect != NO_FIT) {
					views.add(c.texture());
					placed.add(rect);
				}
			}
			break;
		}
		for (int i = 0; i < views.size(); i++) blit(cb, views.get(i), placed.get(i));
	}

	private void record(VkCommandBuffer cb, List<Blas> work, List<Boolean> update, List<Long> offsets) {
		int n = work.size();
		if (n == 0) return;
		var geoms = VkAccelerationStructureGeometryKHR.calloc(n);
		var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(n);
		var ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(n);
		PointerBuffer pRanges = MemoryUtil.memAllocPointer(n);
		try {
			long s = align(scene.address(scratch.handle));
			for (int i = 0; i < n; i++) {
				Blas b = work.get(i);
				boolean refit = update.get(i);
				int quads = b.vertices / 4;
				fill(geoms.get(i), b, quads);
				buildInfo(infos.get(i), VkAccelerationStructureGeometryKHR.create(geoms.address(i), 1), refit, b.as, s + offsets.get(i));
				ranges.get(i).primitiveCount(quads * 2);
				pRanges.put(i, ranges.address(i));
				if (refit) refits++;
				else builds++;
			}
			vkCmdBuildAccelerationStructuresKHR(cb, infos, pRanges);
		} finally {
			geoms.free();
			infos.free();
			ranges.free();
			MemoryUtil.memFree(pRanges);
		}
	}

	private void fill(VkAccelerationStructureGeometryKHR g, Blas b, int quads) {
		g.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(b.opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
		var t = g.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(STRIDE)
				.maxVertex(quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32);
		t.vertexData().deviceAddress(b.vertexAddr);
		t.indexData().deviceAddress(scene.indexAddress());
	}

	private static void buildInfo(VkAccelerationStructureBuildGeometryInfoKHR info, VkAccelerationStructureGeometryKHR.Buffer geom, boolean refit, long as, long scratchAddr) {
		info.sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
				.flags(BUILD_FLAGS)
				.mode(refit ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
				.srcAccelerationStructure(refit ? as : 0).dstAccelerationStructure(as).pGeometries(geom).geometryCount(1);
		info.scratchData().deviceAddress(scratchAddr);
	}

	private void create(Blas b, long size) {
		free(b);
		b.size = size;
		b.storage = new RawBuffer(ctx, size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false, "rt entity blas");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer p = stack.mallocLong(1);
			RtScene.check(vkCreateAccelerationStructureKHR(ctx.vk, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(b.storage.handle)
					.size(size).type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, p), "vkCreateAccelerationStructureKHR");
			b.as = p.get(0);
			b.addr = vkGetAccelerationStructureDeviceAddressKHR(ctx.vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(b.as));
		}
	}

	private void free(Blas b) {
		if (b.as == 0) return;
		long as = b.as;
		ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(ctx.vk, as, null));
		b.storage.destroyLater();
		b.storage = null;
		b.as = 0;
		b.size = 0;
	}

	private long align(long v) {
		return RtScene.align(v, scene.scratchAlign());
	}

	// ---- atlas --------------------------------------------------------------------------------------------------------------

	private static boolean usable(GpuTextureView v) {
		return v.texture().getFormat() == GpuFormat.RGBA8_UNORM && v.getWidth(v.baseMipLevel()) > 0 && v.getHeight(v.baseMipLevel()) > 0;
	}

	/** {w, h} of the texture's atlas copy: its base mip; above MAX_TEXTURE (HD resource pack mob skins) halved to at most half
	 * of it, so a few of them do not fill the atlas. Too big textures used to get no copy and were traced as opaque, so their
	 * cutout parts cast the full silhouette. */
	private static int[] copySize(GpuTextureView v) {
		int w = v.getWidth(v.baseMipLevel()), h = v.getHeight(v.baseMipLevel());
		if (w > MAX_TEXTURE || h > MAX_TEXTURE) while (w > MAX_TEXTURE / 2 || h > MAX_TEXTURE / 2) {
			w = Math.max(w / 2, 1);
			h = Math.max(h / 2, 1);
		}
		return new int[] {w, h};
	}

	/** Shelf packing: true if {@link #place} would find room without a reset. */
	private boolean fits(GpuTextureView v) {
		if (!usable(v)) return true;
		int[] size = copySize(v);
		int w = size[0], h = size[1];
		for (int[] s : shelves) if (s[1] >= h && s[2] + w <= ATLAS) return true;
		return shelfBottom + h <= ATLAS;
	}

	/** {x, y, w, h} of a fresh copy of the texture's base mip in the atlas, or NO_FIT (traced as opaque). */
	private int[] allocate(GpuTextureView v) {
		if (!usable(v)) return NO_FIT;
		int[] size = copySize(v);
		int w = size[0], h = size[1];
		int[] shelf = null;
		for (int[] s : shelves) if (s[1] >= h && s[2] + w <= ATLAS && (shelf == null || s[1] < shelf[1])) shelf = s;
		if (shelf == null) {
			if (shelfBottom + h > ATLAS) return NO_FIT;
			shelf = new int[] {shelfBottom, h, 0};
			shelves.add(shelf);
			shelfBottom += h;
		}
		int[] rect = {shelf[2], shelf[0], w, h};
		shelf[2] += w;
		return rect;
	}

	private void blit(VkCommandBuffer cb, GpuTextureView v, int[] rect) {
		int w = rect[2], h = rect[3];
		long nearest = ((VulkanGpuSampler) RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)).vkSampler();
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var writes = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(2, stack);
			writes.get(0).sType$Default().dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
					.pImageInfo(org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack).sampler(nearest).imageView(((VulkanGpuTextureView) v).vkImageView())
							.imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			writes.get(1).sType$Default().dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
					.pImageInfo(org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack).imageView(atlas.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, writes);
			vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_ALL, 0, stack.ints(rect));
		}
		vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
		blits++;
	}

	// ---- TLAS ---------------------------------------------------------------------------------------------------------------

	/** Appends this frame's entity instances at {@code i}; returns the next free index. */
	/** Hash of the instances the last writeInstances wrote (BLAS addresses, masks): RtScene refits the TLAS while it stays the same. */
	long instanceKey;

	int writeInstances(long inst, long sec, int i, int capacity, int region, Vec3 cam) {
		float tx = (float) (liveX - cam.x), ty = (float) (liveY - cam.y), tz = (float) (liveZ - cam.z);
		// third person: the player model is drawn, so it is an entity like any other
		int playerMask = Minecraft.getInstance().options.getCameraType().isFirstPerson() ? MASK_LOCAL_PLAYER : MASK;
		instanceKey = 1;
		for (Blas b : live) {
			if (i == capacity) break;
			long a = inst + (long) i * VkAccelerationStructureInstanceKHR.SIZEOF;
			MemoryUtil.memPutFloat(a, 1f); MemoryUtil.memPutFloat(a + 4, 0f); MemoryUtil.memPutFloat(a + 8, 0f); MemoryUtil.memPutFloat(a + 12, tx);
			MemoryUtil.memPutFloat(a + 16, 0f); MemoryUtil.memPutFloat(a + 20, 1f); MemoryUtil.memPutFloat(a + 24, 0f); MemoryUtil.memPutFloat(a + 28, ty);
			MemoryUtil.memPutFloat(a + 32, 0f); MemoryUtil.memPutFloat(a + 36, 0f); MemoryUtil.memPutFloat(a + 40, 1f); MemoryUtil.memPutFloat(a + 44, tz);
			MemoryUtil.memPutInt(a + 48, (region + i) | (b.player ? playerMask : MASK) << 24);
			MemoryUtil.memPutInt(a + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
			MemoryUtil.memPutLong(a + 56, b.addr);
			instanceKey = instanceKey * 0x9E3779B97F4A7C15L + b.addr * 2 + (b.player ? playerMask : MASK);
			long s = sec + i * 16L;
			MemoryUtil.memPutLong(s, b.vertexAddr);
			MemoryUtil.memPutInt(s + 8, b.kind);
			MemoryUtil.memPutInt(s + 12, 0);
			i++;
		}
		return i;
	}

	long blasBytes() {
		long n = 0;
		for (Blas b : blas.values()) n += b.size;
		return n;
	}

	String summary() {
		return String.format("entities: %d draws, %d triangles (%d built, %d refitted, %d over budget), %d KiB BLAS, CPU %.2f ms", draws, triangles, builds,
				refits, dropped, blasBytes() >> 10, cpuNs / 1e6);
	}
}
