package dev.emrion.shaders.mod.uniforms;

import java.nio.ByteBuffer;

/** Copy plan for one std140 block: values are stored in std140 form already, so writing is a plain word copy. */
public final class BlockPlan {
	private final int[] src, dst, len;

	public BlockPlan(int[] src, int[] dst, int[] len) {
		this.src = src;
		this.dst = dst;
		this.len = len;
	}

	/** Writes every entry relative to dst.position(); dst's byte order must be the GPU's (native, little-endian). */
	public void write(ByteBuffer out, int[] store) {
		int base = out.position();
		for (int k = 0; k < src.length; k++) {
			int s = src[k], d = base + dst[k];
			for (int w = 0, n = len[k]; w < n; w++) out.putInt(d + 4 * w, store[s + w]);
		}
	}

	/** Number of 32-bit words a std140 value of this GLSL type occupies (matrix columns padded to vec4). */
	public static int words(String glslType) {
		if (glslType.startsWith("mat")) return (glslType.charAt(3) - '0') * 4;
		char last = glslType.charAt(glslType.length() - 1);
		return Character.isDigit(last) ? last - '0' : 1;
	}
}
