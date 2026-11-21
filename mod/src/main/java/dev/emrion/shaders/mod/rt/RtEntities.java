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

	/** offset -1: a static draw whose BLAS from an earlier frame is reused (sig != 0, nothing copied). */
	private record Captured(int offset, int vertices, String type, GpuTextureView texture, boolean player, boolean solid, long sig) {}

	/**
	 * Static block entities. A draw whose every vertex came from block-entity cache hits (BlockEntityGeometryCache) holds the
	 * same triangles as long as the set of hits is the same, whatever the camera did: its signature is the sum of the hits'
	 * entry generations (new on every rewrite of an entry), with the render type and texture. Seen two frames in a row it gets a BLAS over its own vertex copy that later frames reuse (no copy, UV remap, refit
	 * or rebuild), moved by its instance transform. Off: -Demrion.rt.noStaticBlockEntities=true.
	 */
	private static final IdentityHashMap<Object, Hits> cachedHits = new IdentityHashMap<>();
	/** Static signatures with a BLAS kept by the scene: draws (regions) with one of these are not copied. */
	private static volatile java.util.Set<Long> staticSigs = java.util.Set.of();
	/**
	 * Static draws are split per chunk section (Sodium culls block entities per section, the shadow pass too): walking or
	 * turning only adds or drops whole sections, which add or drop instances instead of rebuilding one BLAS over the whole
	 * storage. Off (one static BLAS per draw, as before): -Demrion.rt.noStaticRegions=true.
	 */
	private static boolean regions = true;

	/** A draw's cache hits in vertex order, 3 longs each: first vertex << 32 | vertex count, entry generation, section key. */
	private static final class Hits {
		long[] a = new long[48];
		int n;
	}

	/** BlockEntityGeometryCache: a hit of {@code vertices} vertices of entry generation {@code key}, block origin x y z (camera-relative), was just appended to {@code b}. */
	public static void noteCached(dev.emrion.shaders.mod.vertex.ExtendedBufferBuilder b, long key, int vertices, float x, float y, float z) {
		Object draw = b.rtDraw;
		if (!capturing || draw == null) return;
		// the draw's finished builders plus this one's vertices before the hit
		long at = ((StagedDrawAccessor) draw).emrion$vertexCount() + b.completeVertices() - vertices;
		long section = 0;
		if (regions) {
			long sx = (long) Math.floor(capX + x + 0.5) >> 4, sy = (long) Math.floor(capY + y + 0.5) >> 4, sz = (long) Math.floor(capZ + z + 0.5) >> 4;
			section = (sx & 0x3FFFFF) << 42 | (sy & 0xFFFFF) << 22 | sz & 0x3FFFFF; // world border: 22 bits per horizontal axis
		}
		Hits h = cachedHits.computeIfAbsent(draw, d -> new Hits());
		if (h.n + 3 > h.a.length) h.a = java.util.Arrays.copyOf(h.a, h.a.length * 2);
		h.a[h.n++] = at << 32 | vertices;
		h.a[h.n++] = key;
		h.a[h.n++] = section;
	}

	private static long mix(long z) { // SplitMix64 finaliser
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

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
		regions = !Boolean.getBoolean("emrion.rt.noStaticRegions");
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
			if (c.offset() >= 0) for (long v = base + c.offset(), e = v + (long) c.vertices() * STRIDE; v < e; v += STRIDE) {
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
		cachedHits.clear();
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
			Hits hits = blockEntity && !Boolean.getBoolean("emrion.rt.noStaticBlockEntities") ? cachedHits.get(d) : null;
			if (hits != null && captureStatic(a, n & ~3, hits, name, tex)) continue;
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
			split(offset, n & ~3, name, tex, blockEntity && solidSheet(tex.texture()));
		}
	}

	/**
	 * A block-entity draw split by the sections of its cache hits. A section whose signature (its hits' entry generations, the
	 * section, render type and texture) has a kept BLAS is reused without a copy; every other section is copied as a static
	 * candidate of its own, the vertices between hits (not cached) as an ordinary draw. False if the hits do not fit the draw.
	 */
	private static boolean captureStatic(StagedDrawAccessor a, int n, Hits h, String name, GpuTextureView tex) {
		long[] hv = h.a;
		long prev = 0, covered = 0;
		for (int i = 0; i < h.n; i += 3) {
			long at = hv[i] >>> 32, end = at + (int) hv[i];
			if (at < prev || end > n) return false;
			prev = end;
			covered += (int) hv[i];
		}
		long typeSig = mix(name.hashCode() * 31L + System.identityHashCode(tex.texture()));
		// section -> {signature, vertices, cap offset (-1: reused), write cursor}
		java.util.LinkedHashMap<Long, long[]> groups = new java.util.LinkedHashMap<>();
		long[] g = null;
		for (int i = 0; i < h.n; i += 3) {
			if (i == 0 || hv[i + 2] != hv[i - 1]) g = groups.computeIfAbsent(hv[i + 2], k -> new long[4]);
			g[0] += mix(hv[i + 1]); // generations are unique per entry write (and fix its vertices)
			g[1] += (int) hv[i];
		}
		long rest = n - covered, bytes = rest * STRIDE;
		for (var e : groups.entrySet()) {
			long[] v = e.getValue();
			v[0] = mix(v[0] ^ v[1] ^ typeSig ^ mix(e.getKey())) | 1;
			if (staticSigs.contains(v[0])) v[2] = -1;
			else {
				v[2] = v[3] = bytes;
				bytes += v[1] * STRIDE;
			}
		}
		if (cap.remaining() < bytes) cap = MemoryUtil.memRealloc(cap, (int) Math.max(cap.position() + bytes, cap.capacity() * 2L));
		int base = cap.position();
		long addr = MemoryUtil.memAddress0(cap) + base, restAt = 0;
		long[] cur = {0, 0}; // slice index, its first byte in the draw
		var slices = a.emrion$slices();
		prev = 0;
		for (int i = 0; ; i += 3) {
			long at = i < h.n ? hv[i] >>> 32 : n;
			if (at > prev) { // not cached: the ordinary draw
				if (!copy(slices, prev * STRIDE, (at - prev) * STRIDE, addr + restAt, cur)) return true; // slices too short: drop the draw
				restAt += (at - prev) * STRIDE;
			}
			if (i >= h.n) break;
			if (i == 0 || hv[i + 2] != hv[i - 1]) g = groups.get(hv[i + 2]);
			long len = (long) (int) hv[i] * STRIDE;
			if (g[2] >= 0) {
				if (!copy(slices, at * STRIDE, len, addr + g[3], cur)) return true;
				g[3] += len;
			}
			prev = at + (int) hv[i];
		}
		cap.position((int) (base + bytes));
		boolean solid = solidSheet(tex.texture());
		for (long[] v : groups.values()) captured.add(new Captured(v[2] < 0 ? -1 : base + (int) v[2], (int) v[1], name, tex, false, solid, v[0]));
		if (rest > 0) split(base, (int) rest, name, tex, solid);
		return true;
	}

	/** Copies {@code len} bytes from byte {@code from} of a draw's slices; {@code cur} only moves forward. False past the end. */
	private static boolean copy(List<ByteBufferBuilder.Result> slices, long from, long len, long dst, long[] cur) {
		while (len > 0) {
			if (cur[0] >= slices.size()) return false;
			ByteBuffer b = slices.get((int) cur[0]).byteBuffer();
			long off = from - cur[1];
			if (off >= b.remaining()) {
				cur[1] += b.remaining();
				cur[0]++;
				continue;
			}
			long l = Math.min(len, b.remaining() - off);
			MemoryUtil.memCopy(MemoryUtil.memAddress(b) + off, dst, l);
			from += l;
			dst += l;
			len -= l;
		}
		return true;
	}

	/** Adds the draw at {@code offset}, its local-player quads (EntityIds.LOCAL_PLAYER_W) moved behind the others as a draw of their own. */
	private static void split(int offset, int vertices, String type, GpuTextureView tex, boolean solid) {
		long base = MemoryUtil.memAddress0(cap) + offset, quad = 4L * STRIDE;
		int quads = vertices / 4, player = 0;
		for (int q = 0; q < quads; q++) if (isPlayer(base + q * quad)) player++;
		if (player == 0 || player == quads) {
			captured.add(new Captured(offset, vertices, type, tex, player != 0, solid, 0));
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
			captured.add(new Captured(offset, others * 4, type, tex, false, solid, 0));
			captured.add(new Captured(offset + (int) (others * quad), player * 4, type, tex, true, solid, 0));
		} finally {
			MemoryUtil.memFree(tmp);
		}
	}

	/**
	 * Chests and shulker boxes draw as entity_cutout, so every ray crossing a storage wall alpha-tested each chest triangle in
	 * the shader; their models only sample opaque texels. Opaque in the BLAS: the hardware takes the hit (big storage view
	 * -1.1 ms in gbuffers opaque). Off: -Demrion.rt.cutoutChests=true.
	 */
	private static boolean solidSheet(GpuTexture tex) {
		String l = tex.getLabel();
		return !Boolean.getBoolean("emrion.rt.cutoutChests") && (l.contains("atlas/chest") || l.contains("atlas/shulker"));
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
		@Nullable RawBuffer data; // a static draw's own vertex copy
		int @Nullable [] rect; // a static draw's atlas rect when its UVs were remapped
		double ox, oy, oz; // the camera its vertices are relative to
		long sig; // static draws
		int vertices, refits, kind, lastFrame, retiredFrame;
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
	/**
	 * Retired big static BLAS kept with their vertex buffer and acceleration structure: the next promotion (the chests in view
	 * changed) reuses them instead of allocating ~30 MB again, which now and then stalled the frame for tens of ms.
	 */
	private final java.util.ArrayDeque<Blas> spare = new java.util.ArrayDeque<>();
	private static final int SPARES = 2, SPARE_MIN_BYTES = 1 << 20, SPARE_FRAMES = 600;
	private java.util.Set<Long> candidates = new java.util.HashSet<>(); // static signatures of the last frame
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
		for (Blas b : spare) free(b);
		spare.clear();
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
		staticSigs = java.util.Set.of();
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
			candidates = new java.util.HashSet<>();
			staticSigs = java.util.Set.of();
			retire();
			return;
		}
		// previous frames' ray queries (BLAS refitted in place, atlas regions rewritten after a reset) before this frame's writes
		Barriers.memory(cb, AS_BUILD | Barriers.COMPUTE | Barriers.FRAGMENT | Barriers.RAYGEN, AS_RW | Barriers.SAMPLED_READ, AS_BUILD | Barriers.COMPUTE, AS_RW | Barriers.STORAGE_WRITE);

		long bytes = 0;
		for (Captured c : captured) if (c.offset() >= 0) bytes = Math.max(bytes, c.offset() + (long) c.vertices() * STRIDE);
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
		List<Blas> reused = new ArrayList<>();
		java.util.Set<Long> sigs = new java.util.HashSet<>();
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
				if (tex != blocks && rect == null) { // left out by placeTextures' budget pass (its count includes skipped reuses): no UVs for it
					dropped++;
					continue;
				}
				if (c.offset() < 0) { // static: last frame's BLAS, unless an atlas reset moved its texture (then captured again next frame)
					Blas b = blas.get("static@" + c.sig());
					if (b == null || b.as == 0 || b.lastFrame == frameNo) continue;
					if (!java.util.Arrays.equals(b.rect, rect)) { // retired below, so the next capture copies it again
						b.lastFrame = Integer.MIN_VALUE / 2;
						continue;
					}
					b.lastFrame = frameNo;
					reused.add(b);
					tris += quads * 2L;
					continue;
				}
				boolean stat = c.sig() != 0 && sigs.add(c.sig()) && candidates.contains(c.sig()); // twice in a frame: one copy only
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
				long size = (long) c.vertices() * STRIDE;
				Blas b;
				if (stat) {
					b = blas.get("static@" + c.sig());
					if (b == null) blas.put("static@" + c.sig(), b = takeSpare(size));
					else if (b.data != null) { // earlier frames may still read it
						b.data.destroyLater();
						b.data = null;
					}
					if (b.data == null) b.data = new RawBuffer(ctx, size + size / 8, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
							| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt static entity vertices");
					MemoryUtil.memCopy(src, b.data.mapped, size);
					b.rect = rect;
					b.vertexAddr = scene.address(b.data.handle);
					b.sig = c.sig();
				} else {
					MemoryUtil.memCopy(src, dst, size);
					String key = c.type() + '@' + System.identityHashCode(tex) + (c.player() ? "@p" : "");
					int nth = seen.merge(key, 1, Integer::sum);
					b = blas.computeIfAbsent(key + '#' + nth, k -> new Blas());
					b.vertexAddr = baseAddr + c.offset();
				}
				boolean opaque = rect == NO_FIT || c.type().startsWith("entity_solid") || c.solid();
				b.ox = capX;
				b.oy = capY;
				b.oz = capZ;
				b.kind = kind;
				b.lastFrame = frameNo;
				b.player = c.player();
				boolean refit = !stat && b.as != 0 && b.vertices == c.vertices() && b.opaque == opaque && b.refits < REFITS;
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
		live.addAll(reused);
		candidates = sigs;
		draws = work.size() + reused.size();
		triangles = tris;
		captured.clear();
		retire();
		java.util.Set<Long> st = new java.util.HashSet<>();
		for (Blas b : blas.values()) if (b.sig != 0 && b.as != 0) st.add(b.sig); // kept ones too: a section coming back is not rebuilt
		staticSigs = st;
	}

	/**
	 * BLAS of draws that disappeared: kept for a few frames (an entity leaving and coming back), then freed. Static sections
	 * longer: turning away and back must not rebuild them (their signature still says whether they changed).
	 */
	private static final int STATIC_KEEP = Integer.getInteger("emrion.rt.staticKeepFrames", 300);

	private void retire() {
		int keep = regions ? STATIC_KEEP : 3;
		blas.values().removeIf(b -> {
			if (frameNo - b.lastFrame <= (b.sig != 0 ? keep : 3)) return false;
			if (b.sig != 0 && b.data != null && b.data.size >= SPARE_MIN_BYTES) {
				b.retiredFrame = frameNo;
				spare.addLast(b);
				if (spare.size() > SPARES) free(spare.removeFirst());
			} else free(b);
			return true;
		});
		while (!spare.isEmpty() && frameNo - spare.peekFirst().retiredFrame > SPARE_FRAMES) free(spare.removeFirst());
	}

	/**
	 * A retired static BLAS whose vertex buffer holds {@code bytes}, else a new one. Only once no frame in flight can still read
	 * it: retired means unused for 3 frames, plus RING more since then.
	 */
	private Blas takeSpare(long bytes) {
		for (var it = spare.iterator(); it.hasNext(); ) {
			Blas b = it.next();
			if (frameNo - b.retiredFrame > RING && b.data.size >= bytes) {
				it.remove();
				return b;
			}
		}
		return new Blas();
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
		freeAs(b);
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
		if (b.data != null) b.data.destroyLater();
		b.data = null;
		freeAs(b);
	}

	private void freeAs(Blas b) {
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
		// third person: the player model is drawn, so it is an entity like any other
		int playerMask = Minecraft.getInstance().options.getCameraType().isFirstPerson() ? MASK_LOCAL_PLAYER : MASK;
		instanceKey = 1;
		for (Blas b : live) {
			if (i == capacity) break;
			float tx = (float) (b.ox - cam.x), ty = (float) (b.oy - cam.y), tz = (float) (b.oz - cam.z);
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
