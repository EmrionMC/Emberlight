package dev.emrion.shaders.mod.vertex;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Objects;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Finished entity-format vertices of block-entity model submits (chests, shulkers, bells, ...), reused while every input of
 * ModelFeatureRenderer.prepareModel is unchanged: render type, model, model state (value types only), light, overlay, tint, UV
 * mapping and the pose relative to the block. Positions are kept relative to the block's origin (the camera-relative translation
 * its pose had when the dispatcher submitted it) and moved back by the current origin on reuse. Main and shadow pass share
 * entries. Render thread only.
 */
public final class BlockEntityGeometryCache {
	private BlockEntityGeometryCache() {}

	/** -Demberlight.beCache=false turns it off (A/B comparisons). */
	public static boolean on = !"false".equals(System.getProperty("emberlight.beCache"));
	/** Camera-relative origin of the block entity being submitted, NaN outside one (FeatureSubmitIdsMixin copies it per submit). */
	public static float originX = Float.NaN, originY, originZ;
	/** A pose that differs by less than this (blocks) from an entry's is the same pose (camera rounding of the translation). */
	private static final float POSE_EPS = 2e-4f;
	/** Hits within this distance (blocks²) keep their object motion slot (see begin). */
	private static final float TRACK_RADIUS_SQ = 24 * 24;
	private static final int MAX_MISSES = 3, VOLATILE_FRAMES = 64, EVICT_FRAMES = 256;
	public static long hits, misses; // for the autotest profile

	private static final class Entry {
		Object renderType, model, state, uv, format;
		int light, overlay, tint;
		final float[] pose = new float[12]; // 3x3 linear part, then the translation relative to the origin
		ByteBuffer data;
		int count, lastFrame, misses, volatileUntil;
	}

	private static final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
	private static int frame;
	private static Object idTable;
	private static int level; // identity hash: no reference to an unloaded world

	/** Once per world frame. */
	public static void newFrame() {
		frame++;
		int l = System.identityHashCode(net.minecraft.client.Minecraft.getInstance().level);
		if (idTable != EmrionVertexFormats.ids() || level != l) { // pack switch (new render types and ids), world change
			idTable = EmrionVertexFormats.ids();
			level = l;
			clear();
		}
		if ((frame & (EVICT_FRAMES - 1)) == 0) entries.values().removeIf(e -> frame - e.lastFrame > EVICT_FRAMES);
	}

	public static void clear() {
		entries.clear();
	}

	// the submit being prepared (ModelFeatureRenderer is not reentrant)
	private static long key;
	private static Entry miss;
	private static float ox, oy, oz;
	private static int start = -1;

	/**
	 * Before setupAnim: writes the cached vertices and returns true (skip the model), or prepares the capture of a miss.
	 * {@code motionKey} identifies the submit within its block entity, {@code origin} is NaN for submits outside a block entity.
	 */
	public static boolean begin(ModelFeatureRenderer.Submit<?> s, Object raw, long motionKey, float x, float y, float z) {
		start = -1;
		miss = null;
		if (!on || motionKey == 0 || Float.isNaN(x) || !(raw instanceof ExtendedBufferBuilder b) || !b.acceptsPush()
				|| s.sheetedDecalPose() != null || !valueType(s.state()))
			return false;
		Matrix4f m = s.pose().pose();
		Entry e = entries.get(motionKey);
		if (e != null && e.format == b.format() && matches(e, s, m, x, y, z)) {
			// hits are static, but the first frame of a lid opening needs this frame's positions: tracked up close only
			int w = x * x + y * y + z * z < TRACK_RADIUS_SQ ? dev.emrion.shaders.mod.upscale.ObjectMotion.w : 0;
			if (b.appendCached(MemoryUtil.memAddress(e.data), e.count, x, y, z, w)) {
				e.lastFrame = frame;
				e.misses = 0;
				hits++;
				return true;
			}
			return false;
		}
		misses++;
		if (e == null) entries.put(motionKey, e = new Entry());
		else if (e.volatileUntil - frame > 0) return false; // animated: changes every frame, not worth copying
		else if (++e.misses >= MAX_MISSES) {
			e.misses = 0;
			e.volatileUntil = frame + VOLATILE_FRAMES;
			return false;
		}
		start = b.completeVertices();
		if (start < 0) return false;
		key = motionKey;
		miss = e;
		ox = x;
		oy = y;
		oz = z;
		return false;
	}

	/** After renderToBuffer of a miss prepared by {@link #begin}: copies the submit's vertices into its entry. */
	public static void end(ModelFeatureRenderer.Submit<?> s, Object raw) {
		Entry e = miss;
		miss = null;
		if (e == null || !(raw instanceof ExtendedBufferBuilder b)) return;
		int end = b.completeVertices(), count = end - start;
		if (end < 0 || count <= 0 || count % 4 != 0) {
			entries.remove(key);
			return;
		}
		int bytes = count * b.stride();
		if (e.data == null || e.data.capacity() < bytes) e.data = ByteBuffer.allocateDirect(bytes);
		long dst = MemoryUtil.memAddress(e.data);
		MemoryUtil.memCopy(b.vertexAddress(start), dst, bytes);
		for (int i = 0; i < count; i++) { // positions relative to the origin
			long v = dst + (long) i * b.stride();
			MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(v) - ox);
			MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(v + 4) - oy);
			MemoryUtil.memPutFloat(v + 8, MemoryUtil.memGetFloat(v + 8) - oz);
		}
		e.count = count;
		e.format = b.format();
		e.renderType = s.renderType();
		e.model = s.model();
		e.state = s.state();
		e.uv = s.uvMapping();
		e.light = s.lightCoords();
		e.overlay = s.overlayCoords();
		e.tint = s.tintedColor();
		Matrix4f m = s.pose().pose();
		float[] p = e.pose;
		p[0] = m.m00(); p[1] = m.m01(); p[2] = m.m02();
		p[3] = m.m10(); p[4] = m.m11(); p[5] = m.m12();
		p[6] = m.m20(); p[7] = m.m21(); p[8] = m.m22();
		p[9] = m.m30() - ox; p[10] = m.m31() - oy; p[11] = m.m32() - oz;
		e.lastFrame = frame;
	}

	private static boolean matches(Entry e, ModelFeatureRenderer.Submit<?> s, Matrix4f m, float x, float y, float z) {
		float[] p = e.pose;
		return e.renderType == s.renderType() && e.model == s.model() && e.light == s.lightCoords() && e.overlay == s.overlayCoords()
				&& e.tint == s.tintedColor() && Objects.equals(e.uv, s.uvMapping()) && Objects.equals(e.state, s.state())
				&& p[0] == m.m00() && p[1] == m.m01() && p[2] == m.m02() && p[3] == m.m10() && p[4] == m.m11() && p[5] == m.m12()
				&& p[6] == m.m20() && p[7] == m.m21() && p[8] == m.m22()
				&& Math.abs(m.m30() - x - p[9]) < POSE_EPS && Math.abs(m.m31() - y - p[10]) < POSE_EPS && Math.abs(m.m32() - z - p[11]) < POSE_EPS;
	}

	/** States compared by value (chest/shulker openness, Unit, bell state record); mutable render states are never cached. */
	private static boolean valueType(Object state) {
		return state != null && VALUE.get(state.getClass());
	}

	/** Boxed primitives, strings, enums and records made only of those (a record holding a mutable object could change in place). */
	private static final ClassValue<Boolean> VALUE = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> c) {
			if (scalar(c)) return true;
			if (!c.isRecord()) return false;
			for (var rc : c.getRecordComponents()) if (!scalar(rc.getType())) return false;
			return true;
		}
	};

	private static boolean scalar(Class<?> c) {
		return c.isPrimitive() || c == Float.class || c == Integer.class || c == Double.class || c == Long.class || c == Short.class
				|| c == Byte.class || c == Boolean.class || c == Character.class || c == String.class || c.isEnum()
				|| c.getSuperclass() != null && c.getSuperclass().isEnum();
	}
}
