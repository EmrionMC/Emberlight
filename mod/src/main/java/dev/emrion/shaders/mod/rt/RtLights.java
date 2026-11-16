package dev.emrion.shaders.mod.rt;

import static org.lwjgl.vulkan.VK10.*;

import dev.emrion.shaders.mod.EmrionShadersClient;
import dev.emrion.shaders.mod.vk.Barriers;
import dev.emrion.shaders.mod.vk.RawBuffer;
import dev.emrion.shaders.mod.vk.UboRing;
import dev.emrion.shaders.mod.vk.VulkanContext;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Block-light sources for RT block light: emissive blocks found while a section compiles, and per frame the light lists around
 * the camera that the patched pack code reads ({@code emrion_rt_lights}, declared in {@code rt/complementary/bl_buffer.glsl}).
 * <p>Layout (std430, words): 0-3 grid origin relative to the camera (vec4, w = number of sources in the full list), 4-7 dims
 * (x, y, z, cell size), 8-15 the box where lights were just added or removed (min xyz relative to the camera + GI history cap in
 * frames, 0 = none; max xyz + 1 when this frame's full list replaced last frame's, so the remap table is valid).
 * <p>The capped grid (Faithful block light, refraction): {@link #CELLS} cells of {@link #SLOTS} words (count, then light indices,
 * strongest at the cell centre kept); {@link #MAX_LIGHTS} lights as vec4 (block centre relative to the camera, w = level + source
 * radius); {@link #MAX_LIGHTS} colours as vec4. Vanilla emitters and powered diodes only, as before the full list existed.
 * <p>The full list (RTX block light, ReSTIR, GI): {@link #MAX_ALL} sources as vec4 (block centre relative to the grid origin, w =
 * range level + source radius) and as vec4 (colour with the brightest channel 1, a = power, negative for sources only the pack
 * lights); the remap table (last list's index -> this one's, ~0 = gone); per cell the first index into the index list (plus
 * one end entry); the index list (every source whose range reaches the cell, at most {@link #CELL_CAP} per cell).
 * <p>The buffer is device-local and filled by copies from a host ring at levelStart, so one static descriptor serves every frame.
 * The full list is copied only when it was rebuilt; its positions are grid-relative, so they stay valid while the camera moves.
 */
public final class RtLights {
	static final int CELL = 8, GX = 16, GY = 16, GZ = 16, CELLS = GX * GY * GZ, SLOTS = 32, MAX_LIGHTS = 4096;
	static final int MAX_ALL = 16384, INDEX_MAX = 1 << 19, CELL_CAP = 512, BLOCK_SLOTS = 2 * MAX_ALL;
	/** Slots emrBlSourceIn (rtx/bl_common.glsl) probes before it gives up; insertion keeps to the same limit. */
	static final int BLOCK_PROBES = 64;
	private static final int HEADER = 16, CELL_WORDS = CELLS * SLOTS;
	private static final long COLORS = (HEADER + CELL_WORDS + MAX_LIGHTS * 4L) * 4;
	private static final long ALL_POS = (HEADER + CELL_WORDS + MAX_LIGHTS * 8L) * 4, ALL_COL = ALL_POS + MAX_ALL * 16L,
			REMAP = ALL_COL + MAX_ALL * 16L, START = REMAP + MAX_ALL * 4L, BLOCK = START + (CELLS + 1) * 4L,
			INDEX = BLOCK + BLOCK_SLOTS * 4L;
	static final long SIZE = INDEX + INDEX_MAX * 4L;

	/** Packed per section: x | y << 4 | z << 8 | level << 12 | fullBlock << 16 | type << 17 (section-relative; LightColors type). Written by compile workers. */
	private static final Map<Long, int[]> SECTIONS = new ConcurrentHashMap<>();
	private static final AtomicInteger VERSION = new AtomicInteger();
	/** Set while an RtScene exists: compile workers collect lights only then. */
	static volatile boolean collecting;
	/** Lights a recompile added or removed (world x, y, z, level), for the GI history reset; drained at upload. */
	private static final java.util.concurrent.ConcurrentLinkedQueue<int[]> CHANGED = new java.util.concurrent.ConcurrentLinkedQueue<>();
	/** Frames the change box stays (the light list can change a frame before or after the mesh), and the history cap inside it. */
	private static final int CHANGE_FRAMES = 4, CHANGE_CAP = 3;
	/** How far around a changed light the GI history goes stale: its range plus the bulk of one bounce. */
	private static final int CHANGE_REACH = 13; // shortcut: overworld GI rays reach 64 blocks; far bounces keep their lag
	/** A powered repeater's or comparator's level in the capped grid (Faithful block light), as it was before the full list. */
	static final int DIODE_LEVEL = 7;
	private static final ThreadLocal<int[]> FOUND = ThreadLocal.withInitial(() -> new int[17]); // [0] = count

	/** Which pack-only source groups the pack lights with its settings (LightColors.Kind order), and its ore brightness. */
	private static volatile boolean[] enabled = defaults();
	private static volatile float oreMult = 1f;

	private final VulkanContext ctx = VulkanContext.get();
	final RawBuffer buffer;
	private final RawBuffer staging;
	private final int[] cells = new int[CELL_WORDS];
	private final float[] cellWeight = new float[CELL_WORDS];
	private static final float REDUNDANT = 8f;
	private final java.util.HashSet<Long> positions = new java.util.HashSet<>(); // rebuild: every listed light's block
	private final int[] lights = new int[MAX_LIGHTS]; // level | fullBlock << 4
	private final int[] lightColor = new int[MAX_LIGHTS]; // RGB555
	private final int[] lightX = new int[MAX_LIGHTS], lightY = new int[MAX_LIGHTS], lightZ = new int[MAX_LIGHTS];
	private int lightCount, builtVersion = -1, ox, oy, oz;
	// the full list, its cells and the last one's keys (remap)
	private final float[] allPos = new float[MAX_ALL * 4], allCol = new float[MAX_ALL * 4];
	private final long[] allKey = new long[MAX_ALL];
	private final long[] prevKey = new long[MAX_ALL];
	private final int[] remap = new int[MAX_ALL];
	private final int[] cellStart = new int[CELLS + 1], index = new int[INDEX_MAX];
	private final int[] block = new int[BLOCK_SLOTS]; // open addressing by grid-relative block position: source index + 1, 0 = empty
	private int[] pairCell = new int[1 << 16], pairLight = new int[1 << 16];
	private float[] pairWeight = new float[1 << 16];
	private final int[] cellCount = new int[CELLS];
	private int allCount, prevCount, indexCount;
	private boolean firstBuild = true;
	private int changeFrames, bx0, by0, bz0, bx1, by1, bz1;
	private boolean overflowWarned, allOverflowWarned, indexOverflowWarned, blockOverflowWarned;
	private final Long2IntOpenHashMap keyIndex = new Long2IntOpenHashMap();

	RtLights() {
		buffer = new RawBuffer(ctx, SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "rt lights");
		staging = new RawBuffer(ctx, SIZE * UboRing.REGIONS, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, "rt lights staging");
		keyIndex.defaultReturnValue(-1);
	}

	void destroyLater() {
		buffer.destroyLater();
		staging.destroyLater();
	}

	static void clear() {
		SECTIONS.clear();
		VERSION.incrementAndGet();
	}

	private static boolean[] defaults() {
		boolean[] e = new boolean[LightColors.Kind.values().length];
		java.util.Arrays.fill(e, true);
		e[LightColors.Kind.REDSTONE_BLOCK.ordinal()] = e[LightColors.Kind.LAPIS_BLOCK.ordinal()] = false;
		return e;
	}

	/**
	 * Which sources the pack lights, from its option values (Complementary's names; absent options keep the pack defaults).
	 * Pack-only sources exist only with the coloured light ({@code COLORED_LIGHTING} > 0, shadows on), most of them only with
	 * {@code DO_IPBR_LIGHTS}; ores with {@code GLOWING_ORE_MASTER} 2 (1 in Unbound, {@code SHADER_STYLE} 4). Rebuilds the lists.
	 */
	public static void configure(Map<String, String> values) {
		boolean[] e = new boolean[LightColors.Kind.values().length];
		boolean colored = num(values, "COLORED_LIGHTING", 0) > 0 && num(values, "SHADOW_QUALITY", 1) > -1;
		boolean ipbr = colored && bool(values, "DO_IPBR_LIGHTS", true);
		int ores = (int) num(values, "GLOWING_ORE_MASTER", 1);
		e[LightColors.Kind.VANILLA.ordinal()] = true;
		e[LightColors.Kind.DIODE.ordinal()] = e[LightColors.Kind.WIRE.ordinal()] = e[LightColors.Kind.IPBR.ordinal()] = ipbr;
		e[LightColors.Kind.ORE.ordinal()] = ipbr && (ores == 2 || ores == 1 && num(values, "SHADER_STYLE", 1) == 4);
		e[LightColors.Kind.NETHER_TREE.ordinal()] = ipbr && bool(values, "GLOWING_NETHER_TREES", true);
		e[LightColors.Kind.REDSTONE_BLOCK.ordinal()] = ipbr && bool(values, "EMISSIVE_REDSTONE_BLOCK", false);
		e[LightColors.Kind.LAPIS_BLOCK.ordinal()] = ipbr && bool(values, "EMISSIVE_LAPIS_BLOCK", false);
		enabled = e;
		oreMult = (float) (num(values, "GLOWING_ORE_MULT", 1.0) * num(values, "ACT_ORE_INTENSITY", 1.0));
		VERSION.incrementAndGet();
		EmrionShadersClient.LOG.info("RT block light: pack-only sources {} (ore brightness {})", java.util.Arrays.toString(e), oreMult);
	}

	private static double num(Map<String, String> v, String key, double def) {
		try {
			return v.containsKey(key) ? Double.parseDouble(v.get(key)) : def;
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	private static boolean bool(Map<String, String> v, String key, boolean def) {
		return v.containsKey(key) ? Boolean.parseBoolean(v.get(key)) : def;
	}

	// ---- compile hooks (SectionCompilerMixin / ChunkBuilderMeshingTaskMixin, section worker threads) -------------------------------------------------------

	public static void begin() {
		FOUND.get()[0] = 0;
	}

	/**
	 * A block of the section being compiled: kept when it is a source (LightColors) and not buried (a neighbour lets light
	 * out), which drops the inside of lava lakes and glowstone walls and ores inside the rock.
	 */
	public static void block(BlockState state, BlockPos pos, net.minecraft.world.level.BlockGetter region) {
		if (!collecting || !LightColors.candidate(state)) return;
		int type = LightColors.type(state);
		if (type < 0) return;
		int level = LightColors.kind(type) == LightColors.Kind.VANILLA ? state.getLightEmission() : LightColors.level(type);
		boolean open = false;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			BlockState s = region.getBlockState(n.setWithOffset(pos, d));
			if (!s.isSolidRender() && s.getLightEmission() < level) {
				open = true;
				break;
			}
		}
		if (!open) return;
		int[] f = FOUND.get();
		if (f[0] + 1 == f.length) FOUND.set(f = java.util.Arrays.copyOf(f, f.length * 2));
		boolean full = state.isSolidRender() || !state.getFluidState().isEmpty();
		f[++f[0]] = (pos.getX() & 15) | (pos.getY() & 15) << 4 | (pos.getZ() & 15) << 8 | level << 12 | (full ? 1 << 16 : 0) | type << 17;
	}

	public static void end(SectionPos section) {
		if (!collecting) return;
		int[] f = FOUND.get();
		int[] prev = f[0] == 0 ? SECTIONS.remove(section.asLong()) : SECTIONS.put(section.asLong(), java.util.Arrays.copyOfRange(f, 1, f[0] + 1));
		if (prev != null || f[0] > 0) {
			VERSION.incrementAndGet();
			int[] now = java.util.Arrays.copyOfRange(f, 1, f[0] + 1);
			diff(section, prev == null ? new int[0] : prev, now);
			diff(section, now, prev == null ? new int[0] : prev);
		}
	}

	/**
	 * A section that became all air or was unloaded (ClientChunkCacheStorageMixin, client thread): neither vanilla nor Sodium
	 * compiles an empty section, so its lights leave the list here.
	 * shortcut: a compile of the section still in flight can put them back; a recompile or the next edit nearby fixes it.
	 */
	public static void emptied(long section) {
		int[] prev = SECTIONS.remove(section);
		if (prev == null) return;
		VERSION.incrementAndGet();
		diff(SectionPos.of(section), prev, new int[0]);
	}

	/** Queues every light of {@code a} that {@code b} does not hold. */
	private static void diff(SectionPos section, int[] a, int[] b) {
		int[] sorted = b.clone();
		java.util.Arrays.sort(sorted);
		for (int packed : a)
			if (java.util.Arrays.binarySearch(sorted, packed) < 0)
				CHANGED.add(new int[] {section.minBlockX() + (packed & 15), section.minBlockY() + (packed >> 4 & 15), section.minBlockZ() + (packed >> 8 & 15), packed >> 12 & 15});
	}

	/** Grows the change box by the lights recompiles added or removed since the last frame (inside the grid only). */
	private void collectChanges() {
		for (int[] c; (c = CHANGED.poll()) != null; ) {
			int r = c[3] + CHANGE_REACH;
			if (c[0] + r < ox || c[0] - r > ox + GX * CELL || c[1] + r < oy || c[1] - r > oy + GY * CELL || c[2] + r < oz || c[2] - r > oz + GZ * CELL) continue;
			if (changeFrames == 0) {
				bx0 = by0 = bz0 = Integer.MAX_VALUE;
				bx1 = by1 = bz1 = Integer.MIN_VALUE;
			}
			changeFrames = CHANGE_FRAMES; // shortcut: one box around all changes; far-apart edits in one frame reset what lies between
			bx0 = Math.min(bx0, c[0] - r);
			by0 = Math.min(by0, c[1] - r);
			bz0 = Math.min(bz0, c[2] - r);
			bx1 = Math.max(bx1, c[0] + 1 + r);
			by1 = Math.max(by1, c[1] + 1 + r);
			bz1 = Math.max(bz1, c[2] + 1 + r);
		}
	}

	// ---- per frame (RtScene.buildTlas, render thread) ------------------------------------------------------------------------

	/** Fills ring region {@code frame} and copies it into the device buffer, ordered after the previous frame's reads. */
	void upload(VkCommandBuffer cb, Vec3 cam, int frame) {
		int nx = Math.floorDiv((int) Math.floor(cam.x), CELL) * CELL - GX * CELL / 2;
		int ny = Math.floorDiv((int) Math.floor(cam.y), CELL) * CELL - GY * CELL / 2;
		int nz = Math.floorDiv((int) Math.floor(cam.z), CELL) * CELL - GZ * CELL / 2;
		int v = VERSION.get();
		boolean rebuilt = false;
		if (v != builtVersion || nx != ox || ny != oy || nz != oz) {
			builtVersion = v;
			ox = nx;
			oy = ny;
			oz = nz;
			rebuild();
			rebuilt = true;
		}
		collectChanges();
		long base = staging.mapped + frame * SIZE;
		MemoryUtil.memPutFloat(base, (float) (ox - cam.x));
		MemoryUtil.memPutFloat(base + 4, (float) (oy - cam.y));
		MemoryUtil.memPutFloat(base + 8, (float) (oz - cam.z));
		MemoryUtil.memPutFloat(base + 12, allCount);
		MemoryUtil.memPutFloat(base + 32, (float) (bx0 - cam.x));
		MemoryUtil.memPutFloat(base + 36, (float) (by0 - cam.y));
		MemoryUtil.memPutFloat(base + 40, (float) (bz0 - cam.z));
		MemoryUtil.memPutFloat(base + 44, changeFrames > 0 ? CHANGE_CAP : 0);
		MemoryUtil.memPutFloat(base + 48, (float) (bx1 - cam.x));
		MemoryUtil.memPutFloat(base + 52, (float) (by1 - cam.y));
		MemoryUtil.memPutFloat(base + 56, (float) (bz1 - cam.z));
		MemoryUtil.memPutFloat(base + 60, rebuilt && !firstBuild ? 1f : 0f); // the remap table maps last frame's indices
		firstBuild &= !rebuilt;
		if (changeFrames > 0) changeFrames--;
		MemoryUtil.memPutInt(base + 16, GX);
		MemoryUtil.memPutInt(base + 20, GY);
		MemoryUtil.memPutInt(base + 24, GZ);
		MemoryUtil.memPutInt(base + 28, CELL);
		long c = base + HEADER * 4;
		MemoryUtil.memIntBuffer(c, CELL_WORDS).put(cells); // bulk copy: 512 KiB every frame
		long l = c + CELL_WORDS * 4L;
		for (int i = 0; i < lightCount; i++, l += 16) {
			MemoryUtil.memPutFloat(l, (float) (lightX[i] + 0.5 - cam.x));
			MemoryUtil.memPutFloat(l + 4, (float) (lightY[i] + 0.5 - cam.y));
			MemoryUtil.memPutFloat(l + 8, (float) (lightZ[i] + 0.5 - cam.z));
			MemoryUtil.memPutFloat(l + 12, (lights[i] & 15) + ((lights[i] & 16) != 0 ? 0.45f : 0.15f));
		}
		long col = base + COLORS;
		for (int i = 0; i < lightCount; i++, col += 16) {
			int rgb = lightColor[i];
			MemoryUtil.memPutFloat(col, (rgb >> 10 & 31) / 31f);
			MemoryUtil.memPutFloat(col + 4, (rgb >> 5 & 31) / 31f);
			MemoryUtil.memPutFloat(col + 8, (rgb & 31) / 31f);
			MemoryUtil.memPutFloat(col + 12, 1f);
		}
		if (rebuilt) { // the full list: written only when it changed (grid-relative, the device copy stays valid)
			MemoryUtil.memFloatBuffer(base + ALL_POS, allCount * 4).put(allPos, 0, allCount * 4);
			MemoryUtil.memFloatBuffer(base + ALL_COL, allCount * 4).put(allCol, 0, allCount * 4);
			MemoryUtil.memIntBuffer(base + REMAP, Math.max(prevCount, 1)).put(remap, 0, Math.max(prevCount, 1));
			MemoryUtil.memIntBuffer(base + START, CELLS + 1).put(cellStart);
			MemoryUtil.memIntBuffer(base + BLOCK, BLOCK_SLOTS).put(block);
			MemoryUtil.memIntBuffer(base + INDEX, Math.max(indexCount, 1)).put(index, 0, Math.max(indexCount, 1));
		}
		long used = (HEADER + CELL_WORDS + lightCount * 4L) * 4;
		Barriers.memory(cb, Barriers.FRAGMENT | Barriers.COMPUTE, Barriers.STORAGE_READ, Barriers.TRANSFER, Barriers.TRANSFER_WRITE);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer regions = VkBufferCopy.calloc(8, stack);
			int n = 0;
			regions.get(n++).srcOffset(frame * SIZE).dstOffset(0).size(used);
			if (lightCount > 0) regions.get(n++).srcOffset(frame * SIZE + COLORS).dstOffset(COLORS).size(lightCount * 16L);
			if (rebuilt) {
				if (allCount > 0) {
					regions.get(n++).srcOffset(frame * SIZE + ALL_POS).dstOffset(ALL_POS).size(allCount * 16L);
					regions.get(n++).srcOffset(frame * SIZE + ALL_COL).dstOffset(ALL_COL).size(allCount * 16L);
				}
				regions.get(n++).srcOffset(frame * SIZE + REMAP).dstOffset(REMAP).size(Math.max(prevCount, 1) * 4L);
				regions.get(n++).srcOffset(frame * SIZE + START).dstOffset(START).size((CELLS + 1) * 4L);
				regions.get(n++).srcOffset(frame * SIZE + BLOCK).dstOffset(BLOCK).size(BLOCK_SLOTS * 4L);
				regions.get(n++).srcOffset(frame * SIZE + INDEX).dstOffset(INDEX).size(Math.max(indexCount, 1) * 4L);
			}
			regions.limit(n);
			vkCmdCopyBuffer(cb, staging.handle, buffer.handle, regions);
		}
		Barriers.memory(cb, Barriers.TRANSFER, Barriers.TRANSFER_WRITE, Barriers.FRAGMENT | Barriers.COMPUTE, Barriers.STORAGE_READ);
	}

	/** Both lists for the grid at (ox, oy, oz): a light enters every cell its range (level blocks) reaches. */
	private void rebuild() {
		lightCount = 0;
		java.util.Arrays.fill(cells, 0);
		int r = 15;
		int sx0 = (ox - r) >> 4, sx1 = (ox + GX * CELL + r) >> 4;
		int sy0 = (oy - r) >> 4, sy1 = (oy + GY * CELL + r) >> 4;
		int sz0 = (oz - r) >> 4, sz1 = (oz + GZ * CELL + r) >> 4;
		boolean[] on = enabled;
		positions.clear();
		for (int sx = sx0; sx <= sx1; sx++)
			for (int sy = sy0; sy <= sy1; sy++)
				for (int sz = sz0; sz <= sz1; sz++) {
					int[] list = SECTIONS.get(SectionPos.asLong(sx, sy, sz));
					if (list != null) for (int packed : list) if (capped(packed) > 0)
						positions.add(BlockPos.asLong(sx * 16 + (packed & 15), sy * 16 + (packed >> 4 & 15), sz * 16 + (packed >> 8 & 15)));
				}
		// the full list starts over; the last one's keys stay for the remap
		System.arraycopy(allKey, 0, prevKey, 0, allCount);
		prevCount = allCount;
		allCount = 0;
		keyIndex.clear();
		int pairs = 0;
		for (int sx = sx0; sx <= sx1; sx++)
			for (int sy = sy0; sy <= sy1; sy++)
				for (int sz = sz0; sz <= sz1; sz++) {
					int[] list = SECTIONS.get(SectionPos.asLong(sx, sy, sz));
					if (list == null) continue;
					for (int packed : list) {
						int x = sx * 16 + (packed & 15), y = sy * 16 + (packed >> 4 & 15), z = sz * 16 + (packed >> 8 & 15);
						int type = packed >>> 17, level = packed >> 12 & 15;
						LightColors.Kind kind = LightColors.kind(type);
						float lx = (x - ox) + 0.5f, ly = (y - oy) + 0.5f, lz = (z - oz) + 0.5f; // int difference first: exact at any distance from 0
						int old = capped(packed);
						if (old > 0) {
							if (lightCount == MAX_LIGHTS) {
								// shortcut: the first MAX_LIGHTS in section order win, not the nearest; sort by distance if this shows up
								if (!overflowWarned) EmrionShadersClient.LOG.warn("RT block light: more than {} light sources near the camera", MAX_LIGHTS);
								overflowWarned = true;
							} else if (addToCells(lightCount, lx, ly, lz, old, clustered(x, y, z) ? REDUNDANT : 0f)) {
								lights[lightCount] = old | (packed >> 12 & 16);
								lightColor[lightCount] = LightColors.rgb555(LightColors.rgb(type));
								lightX[lightCount] = x;
								lightY[lightCount] = y;
								lightZ[lightCount] = z;
								lightCount++;
							}
						}
						if (!on[kind.ordinal()] || level == 0) continue;
						if (allCount == MAX_ALL) {
							if (!allOverflowWarned) EmrionShadersClient.LOG.warn("RT block light: more than {} sources near the camera, the rest stay unlit", MAX_ALL);
							allOverflowWarned = true;
							continue;
						}
						int before = pairs;
						pairs = addPairs(allCount, lx, ly, lz, level, pairs);
						if (pairs == before) continue; // reaches no cell of the grid
						int i = allCount++;
						float[] rgb = LightColors.rgb(type);
						float power = LightColors.power(type, oreMult);
						allPos[i * 4] = lx;
						allPos[i * 4 + 1] = ly;
						allPos[i * 4 + 2] = lz;
						allPos[i * 4 + 3] = level + ((packed & 1 << 16) != 0 ? 0.45f : 0.15f);
						allCol[i * 4] = rgb[0];
						allCol[i * 4 + 1] = rgb[1];
						allCol[i * 4 + 2] = rgb[2];
						allCol[i * 4 + 3] = kind == LightColors.Kind.VANILLA ? power : -power;
						allKey[i] = BlockPos.asLong(x, y, z);
						keyIndex.put(allKey[i], i);
					}
				}
		buildIndex(pairs);
		if (fillBlocks(block, allPos, allCount) > 0 && !blockOverflowWarned) {
			EmrionShadersClient.LOG.warn("RT block light: the block hash table needs more than {} probes, some light blocks are not found", BLOCK_PROBES);
			blockOverflowWarned = true;
		}
		for (int i = 0; i < prevCount; i++) remap[i] = keyIndex.get(prevKey[i]); // -1 = ~0: gone
	}

	/**
	 * Enters sources {@code [0, count)} (grid-relative positions, vec4) into {@code block} by open addressing, each within the
	 * {@link #BLOCK_PROBES} slots rtx/bl_common.glsl emrBlSourceIn probes; returns how many found no free slot there (left out).
	 */
	static int fillBlocks(int[] block, float[] pos, int count) {
		java.util.Arrays.fill(block, 0);
		int lost = 0;
		next:
		for (int i = 0; i < count; i++) {
			int slot = blockSlot((int) Math.floor(pos[i * 4]), (int) Math.floor(pos[i * 4 + 1]), (int) Math.floor(pos[i * 4 + 2]));
			for (int p = 0; p < BLOCK_PROBES; p++, slot = (slot + 1) & (block.length - 1))
				if (block[slot] == 0) {
					block[slot] = i + 1;
					continue next;
				}
			lost++;
		}
		return lost;
	}

	/** First slot of the block at grid-relative x y z in {@link #block}: rtx/bl_common.glsl emrBlHash, masked. */
	static int blockSlot(int x, int y, int z) {
		int h = x * 1664525 + y * 22695477 + z * (int) 2891336453L + 1013904223;
		h ^= h >>> 16;
		h *= 0x7feb352d;
		h ^= h >>> 15;
		h *= 0x846ca68b;
		return (h ^ h >>> 16) & (BLOCK_SLOTS - 1);
	}

	/** Level of a packed source in the capped grid: vanilla emitters and powered diodes, as before the full list. */
	private static int capped(int packed) {
		int type = packed >>> 17;
		LightColors.Kind k = LightColors.kind(type);
		return k == LightColors.Kind.VANILLA ? packed >> 12 & 15 : k == LightColors.Kind.DIODE ? DIODE_LEVEL : 0;
	}

	/** Appends (cell, light, weight at the cell centre) for every cell the light's range reaches; returns the new pair count. */
	private int addPairs(int light, float lx, float ly, float lz, int level, int pairs) {
		int x0 = Math.max(0, (int) Math.floor((lx - level) / CELL)), x1 = Math.min(GX - 1, (int) Math.floor((lx + level) / CELL));
		int y0 = Math.max(0, (int) Math.floor((ly - level) / CELL)), y1 = Math.min(GY - 1, (int) Math.floor((ly + level) / CELL));
		int z0 = Math.max(0, (int) Math.floor((lz - level) / CELL)), z1 = Math.min(GZ - 1, (int) Math.floor((lz + level) / CELL));
		for (int cy = y0; cy <= y1; cy++)
			for (int cz = z0; cz <= z1; cz++)
				for (int cx = x0; cx <= x1; cx++) {
					float dx = Math.max(0, Math.max(cx * CELL - lx, lx - (cx + 1) * CELL));
					float dy = Math.max(0, Math.max(cy * CELL - ly, ly - (cy + 1) * CELL));
					float dz = Math.max(0, Math.max(cz * CELL - lz, lz - (cz + 1) * CELL));
					if (dx * dx + dy * dy + dz * dz >= level * level) continue;
					if (pairs == pairCell.length) {
						if (pairs == 4 * INDEX_MAX) return pairs; // shortcut: absurd scenes lose cells beyond this, warned in buildIndex
						pairCell = java.util.Arrays.copyOf(pairCell, pairs * 2);
						pairLight = java.util.Arrays.copyOf(pairLight, pairs * 2);
						pairWeight = java.util.Arrays.copyOf(pairWeight, pairs * 2);
					}
					float cdx = (cx + 0.5f) * CELL - lx, cdy = (cy + 0.5f) * CELL - ly, cdz = (cz + 0.5f) * CELL - lz;
					pairCell[pairs] = (cy * GZ + cz) * GX + cx;
					pairLight[pairs] = light;
					pairWeight[pairs] = level - (float) Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz);
					pairs++;
				}
		return pairs;
	}

	/** Counting sort of the pairs by cell into cellStart / index; a cell over CELL_CAP keeps its strongest sources. */
	private void buildIndex(int pairs) {
		java.util.Arrays.fill(cellCount, 0);
		for (int p = 0; p < pairs; p++) cellCount[pairCell[p]]++;
		int total = 0;
		for (int c = 0; c < CELLS; c++) {
			cellStart[c] = total;
			total += Math.min(cellCount[c], CELL_CAP);
		}
		boolean over = total > INDEX_MAX;
		if (over) total = INDEX_MAX;
		cellStart[CELLS] = total;
		int[] fill = cellCount; // reused: write position per cell
		int[] full = null;
		for (int c = 0; c < CELLS; c++) {
			if (cellCount[c] > CELL_CAP) (full == null ? full = new int[CELLS] : full)[c] = cellCount[c];
			fill[c] = cellStart[c];
		}
		if (full == null) {
			for (int p = 0; p < pairs; p++) {
				int at = fill[pairCell[p]]++;
				if (at < INDEX_MAX) index[at] = pairLight[p];
			}
		} else { // rare: gather the overfull cells' pairs, keep the CELL_CAP strongest at the cell centre
			java.util.Map<Integer, java.util.List<Integer>> extra = new java.util.HashMap<>();
			for (int p = 0; p < pairs; p++) {
				int c = pairCell[p];
				if (full[c] > 0) extra.computeIfAbsent(c, k -> new java.util.ArrayList<>()).add(p);
				else {
					int at = fill[c]++;
					if (at < INDEX_MAX) index[at] = pairLight[p];
				}
			}
			for (var e : extra.entrySet()) {
				var list = e.getValue();
				list.sort((a, b) -> Float.compare(pairWeight[b], pairWeight[a]));
				int c = e.getKey();
				for (int k = 0; k < CELL_CAP; k++) {
					int at = fill[c]++;
					if (at < INDEX_MAX) index[at] = pairLight[list.get(k)];
				}
			}
			if (!indexOverflowWarned) EmrionShadersClient.LOG.warn("RT block light: a light cell holds more than {} sources, the weakest are left out", CELL_CAP);
			indexOverflowWarned = true;
		}
		if (over && !indexOverflowWarned) {
			EmrionShadersClient.LOG.warn("RT block light: more than {} cell entries, some cells lose their sources", INDEX_MAX);
			indexOverflowWarned = true;
		}
		// a cell cut by INDEX_MAX ends where the list ends
		for (int c = 0; c <= CELLS; c++) cellStart[c] = Math.min(cellStart[c], INDEX_MAX);
		indexCount = total;
	}

	/** Whether another light source touches the block at x y z (diagonals included): a froglight wall, a lava pool, a torch row. */
	private boolean clustered(int x, int y, int z) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++)
					if ((dx | dy | dz) != 0 && positions.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) return true;
		return false;
	}

	/**
	 * Capped grid: enters the light into every cell its range reaches, at most SLOTS - 1 per cell.
	 * @param penalty levels the light counts less when a full cell picks what it keeps
	 * @return false when the light reaches no cell of the grid
	 */
	private boolean addToCells(int index, float lx, float ly, float lz, int level, float penalty) {
		boolean any = false;
		int x0 = Math.max(0, (int) Math.floor((lx - level) / CELL)), x1 = Math.min(GX - 1, (int) Math.floor((lx + level) / CELL));
		int y0 = Math.max(0, (int) Math.floor((ly - level) / CELL)), y1 = Math.min(GY - 1, (int) Math.floor((ly + level) / CELL));
		int z0 = Math.max(0, (int) Math.floor((lz - level) / CELL)), z1 = Math.min(GZ - 1, (int) Math.floor((lz + level) / CELL));
		for (int cy = y0; cy <= y1; cy++)
			for (int cz = z0; cz <= z1; cz++)
				for (int cx = x0; cx <= x1; cx++) {
					// distance from the light to the cell box: beyond the level the light adds nothing there
					float dx = Math.max(0, Math.max(cx * CELL - lx, lx - (cx + 1) * CELL));
					float dy = Math.max(0, Math.max(cy * CELL - ly, ly - (cy + 1) * CELL));
					float dz = Math.max(0, Math.max(cz * CELL - lz, lz - (cz + 1) * CELL));
					if (dx * dx + dy * dy + dz * dz >= level * level) continue;
					any = true;
					float cdx = (cx + 0.5f) * CELL - lx, cdy = (cy + 0.5f) * CELL - ly, cdz = (cz + 0.5f) * CELL - lz;
					float w = level - (float) Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz) - penalty;
					int b = ((cy * GZ + cz) * GX + cx) * SLOTS;
					int n = cells[b];
					if (n < SLOTS - 1) {
						cells[b + 1 + n] = index;
						cellWeight[b + 1 + n] = w;
						cells[b] = n + 1;
						continue;
					}
					// Full cell: replace the weakest light if this one is stronger at the cell centre. Lights in a cluster count
					// REDUNDANT levels less, so dense emitters keep a few representatives instead of crowding out the torches
					// around them (a hard-edged dark band on the wall, RAYTRACING.md §27).
					int weakest = 1;
					for (int s = 2; s < SLOTS; s++) if (cellWeight[b + s] < cellWeight[b + weakest]) weakest = s;
					if (w > cellWeight[b + weakest]) {
						cells[b + weakest] = index;
						cellWeight[b + weakest] = w;
					}
				}
		return any;
	}
}
