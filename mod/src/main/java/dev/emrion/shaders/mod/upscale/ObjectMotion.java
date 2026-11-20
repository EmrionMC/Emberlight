package dev.emrion.shaders.mod.upscale;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.emrion.shaders.mod.framegen.FrameGenConfig;
import dev.emrion.shaders.mod.mixin.StagedDrawAccessor;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-object motion for entities, block entities and the first-person hand (docs/UPSCALING.md §2). MC rebuilds their vertices
 * every frame, so we match vertices by identity: each submit gets a key (owner = entity id, block position or hand pass, plus
 * the submit's ordinal within its owner) when it is created; while its vertices are written the key's per-frame slot goes into
 * the unused w of iris_Entity; at upload we read the positions back. A vertex of frame N is the vertex with the same ordinal in
 * the same key of frame N−1, if that key had the same vertex count. Quads that moved become a small triangle list of
 * (current clip, previous clip) that {@link MotionVectors} rasterises over the camera motion. Render thread only; nothing runs
 * unless an upscaler or frame generation is on.
 */
public final class ObjectMotion {
	private ObjectMotion() {}

	public static final int ENTITY = 1, BLOCK_ENTITY = 2, HAND = 3;
	private static final int STRIDE = 56, W_OFFSET = 54, MAX_SLOTS = 0x7FFF; // w bit 15: EntityIds.LOCAL_PLAYER_W
	/** Below this (blocks) a quad counts as static: the camera motion is already right. */
	private static final float STILL = 1e-4f;

	/** Implemented (via mixin) by EntityRenderState: the entity's network id. */
	public interface Owned {
		int emrion$entityId();

		void emrion$setEntityId(int id);
	}

	// submit side
	private static long owner;
	private static int ordinal;
	// build side: per-frame slots
	private static boolean capturing, hand;
	private static long[] slotKeys = new long[1024];
	private static boolean[] slotHand = new boolean[1024];
	private static int slots;
	/** w written into the vertices of the submit being built (slot + 1, 0 = untracked). */
	public static int w;
	// this frame's tracked vertices, in upload order
	private static float[] pos = new float[3 * 4096];
	private static int[] vslot = new int[4096], vord = new int[4096];
	private static int vertices;
	private static int[] slotCount = new int[1024];

	// previous frame, slot-major
	/** key → offset << 32 | count; -1 = absent (no boxing: thousands of block entity slots per frame in storage rooms) */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap prevIndex = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	static {
		prevIndex.defaultReturnValue(-1);
	}
	private static float[] prevPos = new float[0];
	private static boolean havePrev;
	private static final Matrix4f prevViewProj = new Matrix4f(), prevHandProj = new Matrix4f();

	/** -Demberlight.upscale.objectMotion=false (or the autotest step {@code upscaleopts}) leaves only the camera motion, for comparisons. */
	public static boolean on = !"false".equals(System.getProperty("emberlight.upscale.objectMotion"));

	/** True when per-object motion is wanted this frame. */
	public static boolean enabled() {
		return on && PackManager.running() && EmrionVertexFormats.entitiesActive()
				&& (Upscaling.active() || FrameGenConfig.effective() != FrameGenConfig.Mode.OFF);
	}

	/** Around an entity / block entity submit or a hand pass: the owner of the submits created inside (0 = none). */
	public static long setOwner(int kind, long id) {
		long before = owner;
		owner = kind == 0 ? 0 : (id * 0x9E3779B97F4A7C15L) ^ ((long) kind << 58) ^ 1;
		ordinal = 0;
		return before;
	}

	public static void restoreOwner(long before) {
		owner = before;
		ordinal = 0;
	}

	/** A submit record is created: its key (0 = untracked). */
	public static long submitKey() {
		if (owner == 0) return 0;
		return owner + (ordinal++) * 0x632BE59BD9B4E019L;
	}

	/** Around the world (reset = true) and hand feature frames. */
	public static void begin(boolean world) {
		if (!enabled()) return;
		capturing = true;
		hand = !world;
		if (world) {
			slots = 0;
			vertices = 0;
		}
	}

	public static void end() {
		capturing = false;
		w = 0;
	}

	/** A feature renderer starts writing the vertices of a submit with this key. */
	public static void beginSubmit(long key) {
		if (!capturing || key == 0 || slots >= MAX_SLOTS) {
			w = 0;
			return;
		}
		if (slots == slotKeys.length) {
			slotKeys = Arrays.copyOf(slotKeys, slots * 2);
			slotHand = Arrays.copyOf(slotHand, slots * 2);
			slotCount = Arrays.copyOf(slotCount, slots * 2);
		}
		slotKeys[slots] = key;
		slotHand[slots] = hand;
		slotCount[slots] = 0;
		w = ++slots;
	}

	/** StagedVertexBuffer.uploadDrawsToBuffers HEAD: read back the positions of tracked vertices. */
	public static void onUpload(List<StagedVertexBuffer.Draw> draws) {
		if (!capturing) return;
		for (StagedVertexBuffer.Draw d : draws) {
			StagedDrawAccessor a = (StagedDrawAccessor) d;
			if (a.emrion$topology() != PrimitiveTopology.QUADS
					|| a.emrion$format() != EmrionVertexFormats.ENTITY && a.emrion$format() != EmrionVertexFormats.GLYPH) continue;
			int left = a.emrion$vertexCount();
			for (ByteBufferBuilder.Result r : a.emrion$slices()) {
				ByteBuffer b = r.byteBuffer();
				long base = MemoryUtil.memAddress(b);
				int n = Math.min(left, b.remaining() / STRIDE);
				left -= n;
				for (int i = 0; i < n; i++) {
					long v = base + (long) i * STRIDE;
					int slot = (MemoryUtil.memGetShort(v + W_OFFSET) & 0x7FFF) - 1;
					if (slot < 0 || slot >= slots) continue;
					if (vertices == vslot.length) {
						vslot = Arrays.copyOf(vslot, vertices * 2);
						vord = Arrays.copyOf(vord, vertices * 2);
						pos = Arrays.copyOf(pos, vertices * 6);
					}
					pos[vertices * 3] = MemoryUtil.memGetFloat(v);
					pos[vertices * 3 + 1] = MemoryUtil.memGetFloat(v + 4);
					pos[vertices * 3 + 2] = MemoryUtil.memGetFloat(v + 8);
					vslot[vertices] = slot;
					vord[vertices] = slotCount[slot]++;
					vertices++;
				}
			}
		}
	}

	private static Vec3 prevCam = Vec3.ZERO;
	private static final Vector4f c = new Vector4f(), p = new Vector4f();
	/** Output of {@link #build}: floats of (cur clip, prev clip) per vertex, world triangles first, then the hand's. */
	static float[] out = new float[8 * 6 * 256];
	static int worldVertices, handVertices;

	/**
	 * Once per frame, when the motion vectors are drawn: builds the triangle list for this frame against the previous one,
	 * then keeps this frame as the next one's previous.
	 * @param viewProj this frame's unjittered projection × gbufferModelView (world, camera-relative)
	 * @param handProj this frame's unjittered hand projection × view rotation (hand vertices are camera-relative, world-aligned)
	 */
	static void build(Matrix4fc viewProj, Matrix4fc handProj, boolean reset) {
		Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
		float dx = (float) (cam.x - prevCam.x), dy = (float) (cam.y - prevCam.y), dz = (float) (cam.z - prevCam.z);
		int[] offsets = new int[slots + 1];
		for (int s = 0; s < slots; s++) offsets[s + 1] = offsets[s] + slotCount[s];
		int nw = 0, nh = 0;
		float[] world = out, handOut = new float[0];
		if (havePrev && !reset) {
			int lastSlot = -1;
			long prev = -1;
			for (int i = 0; i + 3 < vertices; i += 4) {
				int s = vslot[i];
				if (vslot[i + 3] != s || vord[i + 3] != vord[i] + 3) continue;
				if (s != lastSlot) { // a slot's quads come in a row: one lookup per slot
					prev = prevIndex.get(slotKeys[s]);
					lastSlot = s;
				}
				if (prev == -1 || (int) prev != slotCount[s]) continue;
				int po = (int) (prev >>> 32) + vord[i];
				boolean h = slotHand[s];
				boolean moved = false;
				for (int k = 0; k < 4 && !moved; k++) {
					int ci = (i + k) * 3, pi = (po + k) * 3;
					float ox = h ? 0 : dx, oy = h ? 0 : dy, oz = h ? 0 : dz;
					// current position minus previous position, both relative to the current camera
					moved = Math.abs(pos[ci] - prevPos[pi] + ox) > STILL || Math.abs(pos[ci + 1] - prevPos[pi + 1] + oy) > STILL
							|| Math.abs(pos[ci + 2] - prevPos[pi + 2] + oz) > STILL;
				}
				if (!moved) continue;
				Matrix4fc cm = h ? handProj : viewProj, pm = h ? prevHandProj : prevViewProj;
				if (h) {
					if (handOut.length < (nh + 6) * 8) handOut = Arrays.copyOf(handOut, Math.max(8 * 6 * 64, handOut.length * 2));
				} else if (world.length < (nw + 6) * 8) world = Arrays.copyOf(world, world.length * 2);
				for (int k : QUAD) {
					int ci = (i + k) * 3, pi = (po + k) * 3;
					cm.transform(c.set(pos[ci], pos[ci + 1], pos[ci + 2], 1f));
					pm.transform(p.set(prevPos[pi], prevPos[pi + 1], prevPos[pi + 2], 1f));
					float[] dst = h ? handOut : world;
					int o = (h ? nh++ : nw++) * 8;
					dst[o] = c.x; dst[o + 1] = c.y; dst[o + 2] = c.z; dst[o + 3] = c.w;
					dst[o + 4] = p.x; dst[o + 5] = p.y; dst[o + 6] = p.z; dst[o + 7] = p.w;
				}
			}
		}
		if (world.length < (nw + nh) * 8) world = Arrays.copyOf(world, (nw + nh) * 8);
		System.arraycopy(handOut, 0, world, nw * 8, nh * 8);
		out = world;
		worldVertices = nw;
		handVertices = nh;

		// this frame becomes the previous one, slot-major
		if (prevPos.length < vertices * 3) prevPos = new float[vertices * 3 + 1024];
		prevIndex.clear();
		for (int s = 0; s < slots; s++) prevIndex.putIfAbsent(slotKeys[s], (long) offsets[s] << 32 | slotCount[s]);
		for (int i = 0; i < vertices; i++) System.arraycopy(pos, i * 3, prevPos, (offsets[vslot[i]] + vord[i]) * 3, 3);
		havePrev = true;
		prevCam = cam;
		prevViewProj.set(viewProj);
		prevHandProj.set(handProj);
		vertices = 0; // the next frame's capture starts empty even if its world frame is skipped
		slots = 0;
	}

	/** Two triangles per quad, as MC's quad index buffer. */
	private static final int[] QUAD = {0, 1, 2, 2, 3, 0};
}
