package dev.emrion.shaders.mod.uniforms;

import java.nio.ByteBuffer;

/**
 * Precompiled copy plan for one std140 block: entry k copies {@code len[k]} 32-bit words from the value store at
 * {@code src[k]} to byte offset {@code dst[k]}. Values are kept in the store already in std140 form (floats as raw
 * bits, bools as 0/1, mat3 as three vec4 columns), so writing is a plain word copy with no conversion. Pure.
 */
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
