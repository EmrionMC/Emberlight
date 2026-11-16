package dev.emrion.shaders.mod.compat.sodium;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.emrion.shaders.mod.vertex.EmrionVertexFormats;
import dev.emrion.shaders.mod.vertex.QuadMath;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.system.MemoryUtil;

/**
 * Sodium chunk vertex while a pack is active: Sodium's compact encoding with Iris's XHFPTerrainVertex semantics, plus the
 * same extra attributes as our vanilla EMRION_BLOCK path.
 */
public final class EmrionChunkVertex implements ChunkVertexType {
	public static final EmrionChunkVertex INSTANCE = new EmrionChunkVertex();
	private static final int STRIDE = 40;

	static { if (EmrionVertexFormats.SODIUM_TERRAIN.getVertexSize() != STRIDE) throw new IllegalStateException("SODIUM_TERRAIN layout changed"); }

	@Override public VertexFormat getVertexFormat() { return EmrionVertexFormats.SODIUM_TERRAIN; }

	@Override public ChunkVertexEncoder getEncoder() { return new Encoder(); }

	/** One per mesh buffer builder (one worker thread), so the scratch arrays need no locks. */
	private static final class Encoder implements ChunkVertexEncoder {
		private final float[] p = new float[12], t = new float[8], n = new float[3];

		@Override
		public long write(long ptr, int material, Vertex[] vertices, int section) {
			float cu = 0, cv = 0;
			for (int i = 0; i < 4; i++) {
				Vertex v = vertices[i];
				p[i * 3] = v.x; p[i * 3 + 1] = v.y; p[i * 3 + 2] = v.z;
				t[i * 2] = v.u; t[i * 2 + 1] = v.v;
				cu += v.u; cv += v.v;
			}
			cu *= 0.25f;
			cv *= 0.25f;
			QuadMath.faceNormal(p, n);
			int normal = QuadMath.pack(n[0], n[1], n[2], 0);
			int tangent = QuadMath.quadTangent(n[0], n[1], n[2], p, t);
			int mid = Math.round(cu * 65535f) & 0xFFFF | (Math.round(cv * 65535f) & 0xFFFF) << 16;
			boolean separateAo = EmrionVertexFormats.separateAo();
			for (int i = 0; i < 4; i++) {
				Vertex v = vertices[i];
				VertexBlockData b = (VertexBlockData) v;
				int x = quantize(v.x), y = quantize(v.y), z = quantize(v.z);
				MemoryUtil.memPutInt(ptr, (x >>> 10 & 1023) | (y >>> 10 & 1023) << 10 | (z >>> 10 & 1023) << 20);
				MemoryUtil.memPutInt(ptr + 4, (x & 1023) | (y & 1023) << 10 | (z & 1023) << 20);
				MemoryUtil.memPutInt(ptr + 8, separateAo ? ColorABGR.withAlpha(v.color, v.ao) : ColorARGB.mulRGB(v.color, v.ao));
				MemoryUtil.memPutInt(ptr + 12, texture(cu, v.u) | texture(cv, v.v) << 16);
				int sky = Math.clamp(v.light >>> 16 & 0xFF, 0, 240), block = Math.clamp(v.light & 0xFF, 0, 240); // Iris: no +8 bias
				MemoryUtil.memPutInt(ptr + 16, block | sky << 8 | (material & 0xFF) << 16 | (section & 0xFF) << 24);
				MemoryUtil.memPutInt(ptr + 20, (b.emrion$id() & 0xFFFF) | b.emrion$renderType() << 16);
				MemoryUtil.memPutInt(ptr + 24, normal);
				MemoryUtil.memPutInt(ptr + 28, tangent);
				MemoryUtil.memPutInt(ptr + 32, mid);
				MemoryUtil.memPutInt(ptr + 36, ((int) QuadMath.midBlock(b.emrion$x(), v.x) & 0xFF) | ((int) QuadMath.midBlock(b.emrion$y(), v.y) & 0xFF) << 8
						| ((int) QuadMath.midBlock(b.emrion$z(), v.z) & 0xFF) << 16 | (b.emrion$emission() & 0xFF) << 24);
				ptr += STRIDE;
			}
			return ptr;
		}

		private static int quantize(float position) {
			return (int) ((8.0f + position) / 32.0f * 1048576.0f) & 1048575;
		}

		/** Sodium's texture encoding: 15 bits plus a sign bit that biases the coordinate towards the quad's centroid. */
		private static int texture(float center, float x) {
			int bias = x < center ? 1 : -1;
			return (Math.round(x * 32768.0f) + bias) & 32767 | (bias >>> 31) << 15;
		}
	}
}
