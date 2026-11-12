package dev.emrion.shaders.mod.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.mixin.BufferBuilderAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * BufferBuilder for {@link EmrionVertexFormats#TERRAIN} / {@link EmrionVertexFormats#ENTITY} (Iris MixinBufferBuilder +
 * XHFPTerrainVertex). MC fills the vanilla elements as usual; per vertex we add mc_Entity + at_midBlock (terrain, from the
 * worker's {@link BlockContext}) or iris_Entity (entities, from {@link EntityIds}). When a polygon is complete (on the next
 * vertex or {@link #build}) its face normal, tangent and mid UV are computed and written into all its vertices.
 */
public final class ExtendedBufferBuilder extends BufferBuilder {
	private final int stride, uv, normal, mid, tangent, extra, midBlock;
	private final @Nullable BlockContext block;
	/** Vertices per polygon (4 quads, 3 triangles), 0 = other topologies (no per-polygon data). */
	private final int polygon;
	private int pending;
	private final float[] p = new float[12], t = new float[8], n = new float[3];

	/** {@code block} non-null = terrain format. */
	public ExtendedBufferBuilder(ByteBufferBuilder buffer, PrimitiveTopology topology, VertexFormat format, @Nullable BlockContext block) {
		super(buffer, topology, format);
		this.block = block;
		stride = format.getVertexSize();
		uv = format.getElement("UV0").offset();
		normal = format.getElement("Normal").offset();
		mid = format.getElement("mc_midTexCoord").offset();
		tangent = format.getElement("at_tangent").offset();
		extra = format.getElement(block != null ? "mc_Entity" : "iris_Entity").offset();
		midBlock = block != null ? format.getElement("at_midBlock").offset() : -1;
		polygon = topology == PrimitiveTopology.QUADS ? 4 : topology == PrimitiveTopology.TRIANGLES ? 3 : 0;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (pending == polygon && polygon != 0) finishPolygon();
		super.addVertex(x, y, z);
		long v = ((BufferBuilderAccessor) (Object) this).emrion$vertexPointer();
		BlockContext b = block;
		if (b != null) {
			MemoryUtil.memPutShort(v + extra, (short) b.id);
			MemoryUtil.memPutShort(v + extra + 2, (short) b.renderType);
			long m = v + midBlock;
			MemoryUtil.memPutShort(m, Float.floatToFloat16(QuadMath.midBlock(b.x, x)));
			MemoryUtil.memPutShort(m + 2, Float.floatToFloat16(QuadMath.midBlock(b.y, y)));
			MemoryUtil.memPutShort(m + 4, Float.floatToFloat16(QuadMath.midBlock(b.z, z)));
			MemoryUtil.memPutShort(m + 6, Float.floatToFloat16(b.emission));
		} else {
			MemoryUtil.memPutLong(v + extra, EntityIds.packed());
		}
		if (polygon == 0) {
			MemoryUtil.memPutLong(v + mid, 0L);
			MemoryUtil.memPutInt(v + tangent, 0);
		}
		pending++;
		return this;
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
		long first = last - (long) (count - 1) * stride; // a polygon's vertices are contiguous in the builder
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
