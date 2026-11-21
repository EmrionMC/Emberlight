package dev.emrion.shaders.mod.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.mixin.BufferBuilderAccessor;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** BufferBuilder for the extended formats (Iris MixinBufferBuilder): adds ids per vertex, and normal, tangent and mid UV per finished polygon. */
public final class ExtendedBufferBuilder extends BufferBuilder {
	private final int stride, color, uv, uv1, uv2, normal, mid, tangent, extra;
	private final VertexFormat format;
	/** GLYPH: MC's text code sets neither Normal nor UV1; we set them so the vertex is complete (Normal is replaced per quad). */
	private final boolean glyph;
	/** Vertices per polygon (4 quads, 3 triangles), 0 = other topologies (no per-polygon data). */
	private final int polygon;
	private int pending;
	private final float[] p = new float[12], t = new float[8], n = new float[3];

	/** EMRION_ENTITY or GLYPH. */
	public ExtendedBufferBuilder(ByteBufferBuilder buffer, PrimitiveTopology topology, VertexFormat format) {
		super(buffer, topology, format);
		this.format = format;
		glyph = format == EmrionVertexFormats.GLYPH;
		stride = format.getVertexSize();
		color = format.getElement("Color").offset();
		uv = format.getElement("UV0").offset();
		uv1 = format.getElement("UV1").offset();
		uv2 = format.getElement("UV2").offset();
		normal = format.getElement("Normal").offset();
		mid = format.getElement("mc_midTexCoord").offset();
		tangent = format.getElement("at_tangent").offset();
		extra = format.getElement("iris_Entity").offset();
		polygon = topology == PrimitiveTopology.QUADS ? 4 : topology == PrimitiveTopology.TRIANGLES ? 3 : 0;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (pending == polygon && polygon != 0) finishPolygon();
		super.addVertex(x, y, z);
		if (glyph) setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0f, 1f, 0f); // Iris injects the same up normal before the face normal
		long v = ((BufferBuilderAccessor) (Object) this).emrion$vertexPointer();
		// w = object motion slot (0 while untracked) | local player flag
		MemoryUtil.memPutLong(v + extra, EntityIds.packed()
				| (long) (dev.emrion.shaders.mod.upscale.ObjectMotion.w | (EntityIds.localPlayer ? EntityIds.LOCAL_PLAYER_W : 0)) << 48);
		if (polygon == 0) {
			MemoryUtil.memPutLong(v + mid, 0L);
			MemoryUtil.memPutInt(v + tangent, 0);
		}
		pending++;
		return this;
	}

	/**
	 * Whole vertices (items' putBakedQuad and others): BufferBuilder writes these in one go only for vanilla ENTITY by identity, so
	 * ours took the setter chain. The same bytes as that chain (vanilla's putRgba, putPackedUv, putNormals), written directly.
	 */
	@Override
	public void addVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		if (glyph || !LITTLE_ENDIAN) { // big endian: vanilla's byte order handling
			super.addVertex(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
			return;
		}
		addVertex(x, y, z);
		var self = (BufferBuilderAccessor) (Object) this;
		long p = self.emrion$vertexPointer();
		MemoryUtil.memPutInt(p + color, net.minecraft.util.ARGB.toABGR(argb));
		MemoryUtil.memPutFloat(p + uv, u);
		MemoryUtil.memPutFloat(p + uv + 4, v);
		MemoryUtil.memPutInt(p + uv1, overlay);
		MemoryUtil.memPutInt(p + uv2, light);
		MemoryUtil.memPutByte(p + normal, normalByte(nx));
		MemoryUtil.memPutByte(p + normal + 1, normalByte(ny));
		MemoryUtil.memPutByte(p + normal + 2, normalByte(nz));
		self.emrion$setElementsToFill(0);
	}

	private static final boolean LITTLE_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;

	private static byte normalByte(float c) {
		return (byte) ((int) (net.minecraft.util.Mth.clamp(c, -1.0F, 1.0F) * 127.0F) & 0xFF);
	}

	/** Sodium's VertexBufferWriter fast path (ExtendedBufferBuilderMixin): entity quads only; text keeps the VertexConsumer calls. */
	public boolean acceptsPush() {
		return !glyph && polygon == 4;
	}

	/** Normal too: finishPolygon recomputes it per quad, but a vertex left outside a complete quad keeps the source's. */
	private static final String[] PUSHED = {"Color", "UV0", "UV1", "UV2", "Normal"};
	private @Nullable VertexFormat planFormat;
	private int[] plan = new int[0];
	/** Bytes at the start of a vertex that are laid out the same in source and destination (Position to Normal for Sodium's EntityVertex), 0 = copy by element. */
	private int prefix;

	/**
	 * VertexBufferWriter.push (Sodium's cuboid, item and shadow writers), written in bulk like Sodium's own push: the same ids,
	 * normal, tangent and mid UV as through {@link #addVertex}; the other elements are copied as they are.
	 */
	public void push(long src, int count, VertexFormat from) {
		if (from != planFormat) { // one format in practice (Sodium's EntityVertex): no map lookup per cuboid
			plan = plan(from, format);
			planFormat = from;
			prefix = prefix(plan);
		}
		int[] plan = this.plan;
		int pos = plan[0], srcStride = from.getVertexSize(), prefix = this.prefix;
		var self = (BufferBuilderAccessor) (Object) this;
		if (pending == polygon) finishPolygon();
		if (pending != 0 || count % polygon != 0) { // a polygon split across calls: vertex by vertex
			for (int i = 0; i < count; i++, src += srcStride) {
				addVertex(MemoryUtil.memGetFloat(src + pos), MemoryUtil.memGetFloat(src + pos + 4), MemoryUtil.memGetFloat(src + pos + 8));
				copy(plan, src, self.emrion$vertexPointer());
				self.emrion$setElementsToFill(0);
			}
			return;
		}
		if (count == 0) return;
		long dst = self.emrion$buffer().reserve(count * stride);
		long ids = EntityIds.packed() | (long) (dev.emrion.shaders.mod.upscale.ObjectMotion.w | (EntityIds.localPlayer ? EntityIds.LOCAL_PLAYER_W : 0)) << 48;
		for (int i = 0; i < count; i++, src += srcStride) {
			long v = dst + (long) i * stride;
			if (prefix != 0) copyPrefix(src, v, prefix);
			else {
				MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(src + pos)); // Position is at 0 in our formats
				MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(src + pos + 4));
				MemoryUtil.memPutFloat(v + 8, MemoryUtil.memGetFloat(src + pos + 8));
				copy(plan, src, v);
			}
			MemoryUtil.memPutLong(v + extra, ids);
		}
		self.emrion$setVertices(self.emrion$vertices() + count);
		self.emrion$setVertexPointer(dst + (long) (count - 1) * stride);
		self.emrion$setElementsToFill(0);
		for (int q = 0; q < count; q += polygon) finishPolygon(dst + (long) q * stride, polygon);
	}

	/** Finishes a complete pending polygon; the vertex count, or -1 while a polygon is incomplete. */
	public int completeVertices() {
		if (pending == polygon && polygon != 0) finishPolygon();
		return pending == 0 ? ((BufferBuilderAccessor) (Object) this).emrion$vertices() : -1;
	}

	public VertexFormat format() {
		return format;
	}

	public int stride() {
		return stride;
	}

	/** Address of vertex i (< the vertex count): the builder's vertices are contiguous. */
	public long vertexAddress(int i) {
		var self = (BufferBuilderAccessor) (Object) this;
		return self.emrion$vertexPointer() - (long) (self.emrion$vertices() - 1 - i) * stride;
	}

	/**
	 * Finished vertices from BlockEntityGeometryCache (positions relative to the origin x, y, z): copied, moved to the origin and
	 * given the current ids and object motion slot w (0 = untracked).
	 */
	public boolean appendCached(long src, int count, float x, float y, float z, int w) {
		if (completeVertices() < 0) return false;
		var self = (BufferBuilderAccessor) (Object) this;
		long dst = self.emrion$buffer().reserve(count * stride);
		MemoryUtil.memCopy(src, dst, (long) count * stride);
		long ids = EntityIds.packed() | (long) (w | (EntityIds.localPlayer ? EntityIds.LOCAL_PLAYER_W : 0)) << 48;
		for (int i = 0; i < count; i++) {
			long v = dst + (long) i * stride;
			MemoryUtil.memPutFloat(v, MemoryUtil.memGetFloat(v) + x);
			MemoryUtil.memPutFloat(v + 4, MemoryUtil.memGetFloat(v + 4) + y);
			MemoryUtil.memPutFloat(v + 8, MemoryUtil.memGetFloat(v + 8) + z);
			MemoryUtil.memPutLong(v + extra, ids);
		}
		self.emrion$setVertices(self.emrion$vertices() + count);
		self.emrion$setVertexPointer(dst + (long) (count - 1) * stride);
		self.emrion$setElementsToFill(0);
		return true;
	}

	/** The plan's elements as one block: each at the same offset in both formats, together filling [0, end) without gaps. */
	static int prefix(int[] plan) {
		if (plan[0] != 0) return 0;
		int end = 12, bytes = 12; // Position
		for (int k = 1; k < plan.length; k += 3) {
			if (plan[k] != plan[k + 1]) return 0;
			end = Math.max(end, plan[k] + plan[k + 2]);
			bytes += plan[k + 2];
		}
		return bytes == end ? end : 0;
	}

	private static void copyPrefix(long src, long dst, int bytes) {
		int b = 0;
		for (; b + 8 <= bytes; b += 8) MemoryUtil.memPutLong(dst + b, MemoryUtil.memGetLong(src + b));
		if (b < bytes) MemoryUtil.memPutInt(dst + b, MemoryUtil.memGetInt(src + b));
	}

	private static void copy(int[] plan, long src, long dst) {
		for (int k = 1; k < plan.length; k += 3)
			for (int b = 0; b < plan[k + 2]; b += 4) MemoryUtil.memPutInt(dst + plan[k + 1] + b, MemoryUtil.memGetInt(src + plan[k] + b));
	}

	/** {position offset, then (src offset, dst offset, bytes) per copied element}; Sodium itself fails on formats it cannot convert. */
	private static int[] plan(VertexFormat from, VertexFormat to) {
		var p = from.getElement("Position");
		if (p == null || p.format() != com.mojang.renderpearl.api.GpuFormat.RGB32_FLOAT) throw new IllegalArgumentException("push from " + from);
		int[] plan = new int[1 + PUSHED.length * 3];
		plan[0] = p.offset();
		for (int i = 0; i < PUSHED.length; i++) {
			var s = from.getElement(PUSHED[i]);
			var d = to.getElement(PUSHED[i]);
			if (s == null || s.format() != d.format() || s.format().blockSize() % 4 != 0) throw new IllegalArgumentException("push from " + from + ": " + PUSHED[i]);
			plan[1 + i * 3] = s.offset();
			plan[2 + i * 3] = d.offset();
			plan[3 + i * 3] = s.format().blockSize();
		}
		return plan;
	}

	@Override
	public @Nullable MeshData build() {
		if (pending == polygon && polygon != 0) finishPolygon();
		return super.build();
	}

	private void finishPolygon() {
		int count = pending;
		pending = 0;
		long last = ((BufferBuilderAccessor) (Object) this).emrion$vertexPointer();
		finishPolygon(last - (long) (count - 1) * stride, count); // a polygon's vertices are contiguous in the builder
	}

	private void finishPolygon(long first, int count) {
		float midU = 0, midV = 0;
		for (int i = 0; i < count; i++) {
			long v = first + (long) i * stride;
			p[i * 3] = MemoryUtil.memGetFloat(v);
			p[i * 3 + 1] = MemoryUtil.memGetFloat(v + 4);
			p[i * 3 + 2] = MemoryUtil.memGetFloat(v + 8);
			t[i * 2] = MemoryUtil.memGetFloat(v + uv);
			t[i * 2 + 1] = MemoryUtil.memGetFloat(v + uv + 4);
			midU += t[i * 2];
			midV += t[i * 2 + 1];
		}
		midU /= count;
		midV /= count;
		if (count == 4) {
			// Iris recomputes the normal of level quads from the positions and derives the tangent from it
			QuadMath.faceNormal(p, n);
			int packedNormal = QuadMath.pack(n[0], n[1], n[2], 0);
			int packedTangent = QuadMath.quadTangent(n[0], n[1], n[2], p, t);
			for (int i = 0; i < 4; i++) {
				long v = first + (long) i * stride;
				MemoryUtil.memPutInt(v + normal, packedNormal);
				MemoryUtil.memPutFloat(v + mid, midU);
				MemoryUtil.memPutFloat(v + mid + 4, midV);
				MemoryUtil.memPutInt(v + tangent, packedTangent);
			}
		} else {
			// triangles keep their (smooth) vertex normals; tangent per vertex on the normal's plane (computeTangentSmooth)
			for (int i = 0; i < 3; i++) {
				long v = first + (long) i * stride;
				int vn = MemoryUtil.memGetInt(v + normal);
				int tan = QuadMath.tangent(QuadMath.unpack(vn, 0), QuadMath.unpack(vn, 1), QuadMath.unpack(vn, 2), p, t, 0, 1, 2, true);
				MemoryUtil.memPutFloat(v + mid, midU);
				MemoryUtil.memPutFloat(v + mid + 4, midV);
				MemoryUtil.memPutInt(v + tangent, tan == QuadMath.NO_TANGENT ? QuadMath.ZERO_TANGENT : tan);
			}
		}
	}
}
