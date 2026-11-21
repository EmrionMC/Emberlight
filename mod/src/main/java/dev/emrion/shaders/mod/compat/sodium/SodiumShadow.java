package dev.emrion.shaders.mod.compat.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.emrion.shaders.mod.compat.SodiumTerrain;
import dev.emrion.shaders.mod.mixin.sodium.SodiumWorldRendererAccessor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Shadow-pass terrain under Sodium: our own draw lists over Sodium's region buffers, so the main view's lists stay untouched.
 * All built sections in the shadow volume, no block-face culling (as Iris in shadow). Render thread only.
 */
public final class SodiumShadow {
	private SodiumShadow() {}

	/** The shadow pass's tests, on a section's camera-relative minimum corner (it is 16 blocks wide). */
	public interface Culler {
		/** Inside the shadow volume. */
		default boolean visible(float x, float y, float z) {
			return visible(x, y, z, 16, 16, 16);
		}

		/** A box of that size may be inside the shadow volume (conservative: false means no part of it is). */
		boolean visible(float x, float y, float z, float sizeX, float sizeY, float sizeZ);

		/** Called for every visible built section, air included: it may receive shadows. */
		void receiver(float x, float y, float z);

		/** After all receivers: the section casts onto one of them. */
		boolean keep(float x, float y, float z);

		/** Distance of the section's centre from the light along the shadow view axis (smaller = nearer the light). */
		float depth(float x, float y, float z);
	}

	private static final TerrainRenderPass[] PASSES = DefaultTerrainRenderPasses.ALL;
	private static final class Lists {
		final MultiDrawBatch[] batches = new MultiDrawBatch[PASSES.length];
		/** Opaque passes: start of each depth band's commands in the batch (BANDS + 1 entries), or null. */
		final int[][] bands = new int[PASSES.length][];
		int frame;
	}

	/**
	 * -Demrion.perf.sodiumShadowBands=false: draw in region order. Otherwise the opaque shadow commands are added near to far from the
	 * light and drawn in depth bands across regions (as DefaultChunkRendererMixin does for the main view), so early depth testing
	 * rejects more of the hidden casters. Same commands, only the order changes; translucent keeps its order.
	 */
	private static final boolean SHADOW_BANDS = !"false".equals(System.getProperty("emrion.perf.sodiumShadowBands"));
	private static final int BANDS = 8;
	/** Depth band width in blocks along the light axis. */
	private static final float BAND_DEPTH = 64;
	/** Kept sections with opaque geometry: depth bits << 32 | candidate index (sorted near the light first). */
	private static final LongArrayList DEFERRED = new LongArrayList();
	private static final Map<RenderRegion, Lists> LISTS = new IdentityHashMap<>();
	private static final List<RenderRegion> DRAWN = new ArrayList<>();
	private static final List<RenderRegion> CANDIDATE_REGIONS = new ArrayList<>();
	private static final LongArrayList CANDIDATES = new LongArrayList();
	/** Block entities of the kept sections plus every section's global ones. */
	public static final List<BlockEntity> BLOCK_ENTITIES = new ArrayList<>();
	private static SharedQuadIndexBuffer quadIndices;
	private static DrawContext context;
	private static List<RenderPipeline> pipelines = List.of();
	private static int frame, maxElements;
	private static boolean sorting;
	/** Sections with geometry drawn last frame. */
	public static int sectionCount;

	/** -Demrion.perf.sodiumVisibleReceivers=false: every built section in the camera frustum receives (hidden ones included). */
	private static final boolean VISIBLE_RECEIVERS = !"false".equals(System.getProperty("emrion.perf.sodiumVisibleReceivers"));
	/** Per region: bit per local section that the main view's render lists hold this frame (only this frame's regions). */
	private static final Map<RenderRegion, long[]> VISIBLE = new IdentityHashMap<>();
	/** VISIBLE's bit arrays, reused. */
	private static final List<long[]> VISIBLE_POOL = new ArrayList<>();

	/** Client shutdown: the shared quad indices and draw context live for the session (the validation layer's one leaked buffer). */
	public static void shutdown() {
		if (quadIndices != null) quadIndices.delete();
		if (context != null) context.delete();
		quadIndices = null;
		context = null;
	}

	/** Sodium unloads its world or rebuilds its renderer (SodiumWorldRendererMixin): forget its regions and their block entities. */
	public static void reset() {
		VISIBLE.clear();
		DRAWN.clear();
		BLOCK_ENTITIES.clear();
		CANDIDATE_REGIONS.clear();
		for (Lists l : LISTS.values()) for (MultiDrawBatch b : l.batches) if (b != null) b.delete();
		LISTS.clear();
	}

	/** Fills {@link #VISIBLE} from Sodium's render lists, which its extraction-time setupTerrain built for this frame. */
	private static void markVisible(SodiumWorldRendererAccessor acc) {
		VISIBLE.clear(); // never keeps an unloaded region (and its block entities, its world) alive
		if (!VISIBLE_RECEIVERS) return;
		int used = 0;
		for (var lists = acc.emrion$sections().getRenderLists().iterator(false); lists.hasNext(); ) {
			var list = lists.next();
			if (used == VISIBLE_POOL.size()) VISIBLE_POOL.add(new long[RenderRegion.REGION_SIZE / 64]);
			long[] bits = VISIBLE_POOL.get(used++);
			java.util.Arrays.fill(bits, 0);
			VISIBLE.put(list.getRegion(), bits);
			for (var it : new net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator[] {list.sectionsWithGeometryIterator(false),
					list.sectionsWithEntitiesIterator(), list.sectionsWithSpritesIterator()}) {
				if (it == null) continue;
				while (it.hasNext()) {
					int id = it.nextByteAsInt();
					bits[id >>> 6] |= 1L << (id & 63);
				}
			}
		}
	}

	/** Collects this frame's shadow sections and fills the per-region draw lists (before the shadow render passes open). */
	public static void collect(Culler c, double camX, double camY, double camZ) {
		frame++;
		for (RenderRegion r : DRAWN) for (MultiDrawBatch b : LISTS.get(r).batches) if (b != null) b.clear();
		DRAWN.clear();
		BLOCK_ENTITIES.clear();
		CANDIDATES.clear();
		CANDIDATE_REGIONS.clear();
		DEFERRED.clear();
		sectionCount = 0;
		maxElements = 0;
		var renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null || ((SodiumWorldRendererAccessor) renderer).emrion$sections() == null) return;
		var acc = (SodiumWorldRendererAccessor) renderer;
		sorting = acc.emrion$sorting();
		if (context == null) context = DrawContext.create();
		context.rotate();
		markVisible(acc);
		for (RenderRegion region : acc.emrion$sections().regions.getLoadedRegions()) {
			if (region.isEmpty()) continue;
			// a whole region outside the shadow volume skips its 256 section tests (1 block slack for float rounding)
			if (!c.visible((float) ((region.getChunkX() << 4) - camX) - 1, (float) ((region.getChunkY() << 4) - camY) - 1, (float) ((region.getChunkZ() << 4) - camZ) - 1,
					RenderRegion.REGION_WIDTH * 16 + 2, RenderRegion.REGION_HEIGHT * 16 + 2, RenderRegion.REGION_LENGTH * 16 + 2)) continue;
			int regionIndex = -1;
			long[] visible = VISIBLE_RECEIVERS ? VISIBLE.get(region) : null;
			for (int id = 0; id < RenderRegion.REGION_SIZE; id++) {
				int flags = region.getSectionFlags(id);
				if ((flags & RenderSectionFlags.MASK_IS_BUILT) == 0) continue;
				float x = (float) (((region.getChunkX() + LocalSectionIndex.unpackX(id)) << 4) - camX);
				float y = (float) (((region.getChunkY() + LocalSectionIndex.unpackY(id)) << 4) - camY);
				float z = (float) (((region.getChunkZ() + LocalSectionIndex.unpackZ(id)) << 4) - camZ);
				if (!c.visible(x, y, z)) continue;
				// receivers: air in the camera frustum (volumetrics sample the shadow map there) and the sections the main view draws;
				// a section with blocks that Sodium culls as hidden shows no pixel, so it receives nothing visible
				if (!VISIBLE_RECEIVERS || (flags & RenderSectionFlags.MASK_NEEDS_RENDER) == 0 || visible != null && (visible[id >>> 6] >>> (id & 63) & 1) != 0)
					c.receiver(x, y, z);
				if ((flags & (RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY | RenderSectionFlags.MASK_HAS_BLOCK_ENTITIES)) == 0) continue;
				if (regionIndex < 0) {
					regionIndex = CANDIDATE_REGIONS.size();
					CANDIDATE_REGIONS.add(region);
				}
				CANDIDATES.add((long) regionIndex << 32 | id);
			}
		}
		RenderRegion last = null;
		Lists lists = null;
		for (int i = 0; i < CANDIDATES.size(); i++) {
			long packed = CANDIDATES.getLong(i);
			RenderRegion region = CANDIDATE_REGIONS.get((int) (packed >>> 32));
			int id = (int) packed;
			float x = (float) (((region.getChunkX() + LocalSectionIndex.unpackX(id)) << 4) - camX);
			float y = (float) (((region.getChunkY() + LocalSectionIndex.unpackY(id)) << 4) - camY);
			float z = (float) (((region.getChunkZ() + LocalSectionIndex.unpackZ(id)) << 4) - camZ);
			if (!c.keep(x, y, z)) continue;
			BlockEntity[] culled = region.getCulledBlockEntities(id);
			if (culled != null) BLOCK_ENTITIES.addAll(List.of(culled));
			if ((region.getSectionFlags(id) & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0 || region.getResources() == null) continue;
			if (region != last) {
				last = region;
				lists = LISTS.computeIfAbsent(region, r -> new Lists());
				if (lists.frame != frame) {
					lists.frame = frame;
					DRAWN.add(region);
				}
			}
			boolean any = false;
			for (int p = 0; p < PASSES.length; p++) {
				if (SHADOW_BANDS && !PASSES[p].isTranslucent()) continue;
				SectionRenderDataStorage storage = region.getStorage(PASSES[p]);
				if (storage != null) any |= add(lists, p, storage.getDataPointer(id), PASSES[p].isTranslucent() && sorting);
			}
			if (SHADOW_BANDS) {
				// positive float bits sort like the floats; the offset keeps camera-relative depths (within +-1e5) positive
				DEFERRED.add((long) Float.floatToIntBits(c.depth(x, y, z) + 1e5f) << 32 | i);
				any = false; // counted below
			}
			if (any) sectionCount++;
		}
		if (SHADOW_BANDS) addSorted();
		for (RenderSection s : acc.emrion$sections().getSectionsWithGlobalEntities()) {
			BlockEntity[] global = s.getRegion().getGlobalBlockEntities(s.getSectionIndex());
			if (global != null) BLOCK_ENTITIES.addAll(List.of(global));
		}
		// drop the lists of regions Sodium unloaded (or of a renderer it replaced)
		LISTS.values().removeIf(l -> {
			if (l.frame == frame || frame - l.frame < 600) return false;
			for (MultiDrawBatch b : l.batches) if (b != null) b.delete();
			return true;
		});
		if (quadIndices == null) quadIndices = new SharedQuadIndexBuffer(SharedQuadIndexBuffer.IndexFormat.INTEGER);
		quadIndices.ensureCapacity(maxElements);
		for (RenderRegion r : DRAWN) for (MultiDrawBatch b : LISTS.get(r).batches) if (b != null && !b.isEmpty()) b.prepare(context);
	}

	/** The deferred opaque commands, nearest the light first, with each region's band starts. */
	private static void addSorted() {
		long[] keys = DEFERRED.elements();
		int n = DEFERRED.size();
		java.util.Arrays.sort(keys, 0, n);
		if (n == 0) return;
		float first = Float.intBitsToFloat((int) (keys[0] >>> 32));
		var order = new java.util.LinkedHashSet<RenderRegion>();
		for (int k = 0; k < n; k++) {
			int band = Math.min(BANDS - 1, (int) ((Float.intBitsToFloat((int) (keys[k] >>> 32)) - first) / BAND_DEPTH));
			long packed = CANDIDATES.getLong((int) keys[k]);
			RenderRegion region = CANDIDATE_REGIONS.get((int) (packed >>> 32));
			int id = (int) packed;
			order.add(region);
			Lists lists = LISTS.get(region);
			boolean any = false;
			for (int p = 0; p < PASSES.length; p++) {
				if (PASSES[p].isTranslucent()) continue;
				SectionRenderDataStorage storage = region.getStorage(PASSES[p]);
				if (storage == null) continue;
				long data = storage.getDataPointer(id);
				int size = lists.batches[p] == null ? 0 : lists.batches[p].size;
				if (!add(lists, p, data, false)) continue;
				any = true;
				int[] b = lists.bands[p];
				if (b == null || b[BANDS] != -1 - frame) { // first command of this frame: bands up to ours start at 0
					b = lists.bands[p] = new int[BANDS + 1];
					java.util.Arrays.fill(b, -1);
					b[BANDS] = -1 - frame; // frame >= 1, so this never reads as an open band
				}
				// open every band up to this one at the command just added (bands before it that got nothing are empty)
				for (int q = 0; q <= band; q++) if (b[q] < 0) b[q] = size;
			}
			if (any) sectionCount++;
		}
		order.addAll(DRAWN); // regions with translucent geometry only, last
		DRAWN.clear();
		DRAWN.addAll(order);
		for (RenderRegion r : DRAWN)
			for (int p = 0; p < PASSES.length; p++) {
				int[] b = LISTS.get(r).bands[p];
				if (b == null || b[BANDS] != -1 - frame) continue;
				int size = LISTS.get(r).batches[p].size;
				for (int q = 0; q < BANDS; q++) if (b[q] < 0) b[q] = size;
				b[BANDS] = size;
			}
	}

	/** Debug (-Demrion.debug.shadowStats): shadow lists and main-view render lists as "sections, kTri, draws" per pass group. */
	public static String stats(double camX, double camY, double camZ) {
		long[] shadow = new long[4];
		for (RenderRegion r : DRAWN)
			for (int p = 0; p < PASSES.length; p++) {
				MultiDrawBatch b = LISTS.get(r).batches[p];
				if (b == null) continue;
				shadow[PASSES[p].isTranslucent() ? 2 : 0] += b.size;
			}
		var renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null) return "";
		long sections = 0, tri = 0, triCulled = 0, triT = 0, draws = 0, drawsCulled = 0;
		long[] perPass = new long[PASSES.length];
		int cx = (int) Math.floor(camX), cy = (int) Math.floor(camY), cz = (int) Math.floor(camZ);
		var it = ((SodiumWorldRendererAccessor) renderer).emrion$sections().getRenderLists().iterator(false);
		while (it.hasNext()) {
			var list = it.next();
			RenderRegion region = list.getRegion();
			var sec = list.sectionsWithGeometryIterator(false);
			if (sec == null) continue;
			while (sec.hasNext()) {
				int id = sec.nextByteAsInt();
				sections++;
				int visible = net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer.getVisibleFaces(cx, cy, cz,
						region.getChunkX() + LocalSectionIndex.unpackX(id), region.getChunkY() + LocalSectionIndex.unpackY(id), region.getChunkZ() + LocalSectionIndex.unpackZ(id));
				for (int p = 0; p < PASSES.length; p++) {
					SectionRenderDataStorage storage = region.getStorage(PASSES[p]);
					if (storage == null) continue;
					long data = storage.getDataPointer(id), facings = SectionRenderDataUnsafe.getFacingList(data);
					for (int f = 0; f < ModelQuadFacing.COUNT; f++) {
						long v = SectionRenderDataUnsafe.getVertexCount(data, f);
						if (v == 0) continue;
						perPass[p] += v / 2;
						if (PASSES[p].isTranslucent()) { triT += v / 2; continue; }
						tri += v / 2;
						draws++;
						if ((visible >>> (int) (facings >>> f * 8 & 255) & 1) != 0) { triCulled += v / 2; drawsCulled++; }
					}
				}
			}
		}
		return String.format(java.util.Locale.ROOT, "Sodium shadow: %d sections, opaque draws %d, translucent draws %d; main view: %d sections, opaque %d kTri (%d after face culling) in %d facing ranges (%d), translucent %d kTri; per pass kTri %s",
				sectionCount, shadow[0], shadow[2], sections, tri / 1000, triCulled / 1000, draws, drawsCulled, triT / 1000, java.util.Arrays.toString(java.util.Arrays.stream(perPass).map(x -> x / 1000).toArray()));
	}

	/** One section's draw commands for one pass with every facing (no block-face culling). Mirrors DefaultChunkRenderer's. */
	private static boolean add(Lists lists, int pass, long data, boolean localIndexBuffer) {
		long baseVertex = SectionRenderDataUnsafe.getBaseVertex(data), total = 0;
		for (int f = 0; f < ModelQuadFacing.COUNT; f++) total += SectionRenderDataUnsafe.getVertexCount(data, f);
		if (total == 0) return false;
		MultiDrawBatch b = lists.batches[pass];
		if (b == null) b = lists.batches[pass] = MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE + 1);
		if (localIndexBuffer && SectionRenderDataUnsafe.isLocalIndex(data)) {
			long element = SectionRenderDataUnsafe.getBaseElement(data);
			for (int f = 0; f < ModelQuadFacing.COUNT; f++) {
				long count = SectionRenderDataUnsafe.getVertexCount(data, f), elements = (count >> 2) * 6;
				if (count != 0) b.put(b.size++, (int) elements, (int) baseVertex, element);
				baseVertex += count;
				element += elements;
			}
		} else {
			int elements = (int) ((total >> 2) * 6);
			b.put(b.size++, elements, (int) baseVertex, SectionRenderDataUnsafe.getBaseElement(data));
			if (!localIndexBuffer) maxElements = Math.max(maxElements, elements);
		}
		return true;
	}

	/** Draws the opaque or translucent lists; u_Globals matrices are unused (our shaders read shadowModelView). */
	public static void draw(RenderPass pass, boolean translucent, Matrix4fc projection, Matrix4fc modelView, double camX, double camY, double camZ,
			GpuSampler sampler) {
		if (DRAWN.isEmpty()) return;
		var renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null) return;
		var acc = (SodiumWorldRendererAccessor) renderer;
		if (pipelines.isEmpty()) pipelines = SodiumTerrain.pipelines();
		if (pipelines.size() != PASSES.length) return;
		var uniforms = acc.emrion$uniforms();
		CameraTransform camera = new CameraTransform(camX, camY, camZ);
		uniforms.update(new ChunkRenderMatrices(projection, modelView), acc.emrion$fog(), camera);
		for (int p = 0; p < PASSES.length; p++) {
			TerrainRenderPass tp = PASSES[p];
			if (tp.isTranslucent() != translucent) continue;
			boolean local = translucent && sorting;
			RenderPipeline pipeline = pipelines.get(p);
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			context.setContext(pass, pipeline);
			if (!local) pass.setIndexBuffer(quadIndices.getBufferObject(), IndexType.INT);
			pass.setUniform("u_Globals", uniforms.getUniformBuffer());
			pass.setUniform("u_SectionTimeInfo", uniforms.getSectionTimeInfo());
			pass.setUniform("u_LightTex", Minecraft.getInstance().gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("u_BlockTex", tp.getAtlas(), sampler);
			for (int band = 0; band < (SHADOW_BANDS && !translucent ? BANDS : 1); band++)
			for (RenderRegion region : DRAWN) { // DRAWN is in order of each region's nearest kept section
				MultiDrawBatch b = LISTS.get(region).batches[p];
				var resources = region.getResources();
				if (b == null || b.isEmpty() || resources == null) continue;
				int[] bands = SHADOW_BANDS && !translucent ? LISTS.get(region).bands[p] : null;
				boolean banded = bands != null && bands[BANDS] == b.size && b instanceof BandedBatch;
				if (banded ? bands[band] == bands[band + 1] : band > 0) continue;
				if (local) pass.setIndexBuffer(resources.getIndexBuffer(), IndexType.INT);
				pass.setVertexBuffer(0, resources.getGeometryBuffer().slice());
				try (MemoryStack stack = MemoryStack.stackPush()) {
					ByteBuffer push = stack.malloc(20);
					long a = MemoryUtil.memAddress(push);
					// integer region offset; the camera fraction is u_Globals.u_CameraOffset
					MemoryUtil.memPutFloat(a, region.getOriginX() - camera.intX);
					MemoryUtil.memPutFloat(a + 4, region.getOriginY() - camera.intY);
					MemoryUtil.memPutFloat(a + 8, region.getOriginZ() - camera.intZ);
					MemoryUtil.memPutInt(a + 12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
					MemoryUtil.memPutInt(a + 16, region.getId());
					pass.pushConstants(push);
				}
				if (banded) ((BandedBatch) b).emrion$draw(context, bands[band], bands[band + 1]);
				else b.draw(context);
			}
			context.endDraw();
		}
	}
}
