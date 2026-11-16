package dev.emrion.shaders.mod.pbr;

import com.mojang.blaze3d.platform.NativeImage;

/** Resize and mip generation for PBR maps with Iris's per-channel blend rules, so specular ids never blend. */
final class PbrImages {
	private PbrImages() {}

	static NativeImage scale(NativeImage src, int w, int h) {
		int sw = src.getWidth(), sh = src.getHeight();
		NativeImage out = new NativeImage(w, h, false);
		boolean nearest = w % sw == 0 && h % sh == 0;
		float sx = (float) sw / w, sy = (float) sh / h;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (nearest) {
					out.setPixel(x, y, src.getPixel((int) ((x + 0.5F) * sx), (int) ((y + 0.5F) * sy)));
					continue;
				}
				float fx = Math.clamp((x + 0.5F) * sx - 0.5F, 0, sw - 1), fy = Math.clamp((y + 0.5F) * sy - 0.5F, 0, sh - 1);
				int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(x0 + 1, sw - 1), y1 = Math.min(y0 + 1, sh - 1);
				float tx = fx - x0, ty = fy - y0;
				out.setPixel(x, y, bilinear(src.getPixel(x0, y0), src.getPixel(x1, y0), src.getPixel(x0, y1), src.getPixel(x1, y1), tx, ty));
			}
		}
		return out;
	}

	private static int bilinear(int c00, int c10, int c01, int c11, float tx, float ty) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			float top = (c00 >>> shift & 0xFF) * (1 - tx) + (c10 >>> shift & 0xFF) * tx;
			float bottom = (c01 >>> shift & 0xFF) * (1 - tx) + (c11 >>> shift & 0xFF) * tx;
			out |= Math.round(top * (1 - ty) + bottom * ty) << shift;
		}
		return out;
	}

	/** Levels 0..maxLevel; level 0 is {@code base} itself. */
	static NativeImage[] mips(NativeImage base, int maxLevel, boolean specular) {
		NativeImage[] levels = new NativeImage[maxLevel + 1];
		levels[0] = base;
		for (int level = 1; level <= maxLevel; level++) {
			NativeImage prev = levels[level - 1];
			NativeImage mip = new NativeImage(prev.getWidth() >> 1, prev.getHeight() >> 1, false);
			for (int y = 0; y < mip.getHeight(); y++) {
				for (int x = 0; x < mip.getWidth(); x++) {
					mip.setPixel(x, y, blend(prev.getPixel(2 * x, 2 * y), prev.getPixel(2 * x + 1, 2 * y),
							prev.getPixel(2 * x, 2 * y + 1), prev.getPixel(2 * x + 1, 2 * y + 1), specular));
				}
			}
			levels[level] = mip;
		}
		return levels;
	}

	static int blend(int c0, int c1, int c2, int c3, boolean specular) {
		int a = channel(c0, c1, c2, c3, 24, specular ? 3 : 0);
		int r = channel(c0, c1, c2, c3, 16, 0); // smoothness: linear in both
		int g = channel(c0, c1, c2, c3, 8, specular ? 1 : 0);
		int b = channel(c0, c1, c2, c3, 0, specular ? 2 : 0);
		return a << 24 | r << 16 | g << 8 | b;
	}

	/** kind 0 = linear; 1 = labPBR F0/metal id (G); 2 = porosity/SSS (B); 3 = emission (A). */
	private static int channel(int c0, int c1, int c2, int c3, int shift, int kind) {
		int v0 = c0 >>> shift & 0xFF, v1 = c1 >>> shift & 0xFF, v2 = c2 >>> shift & 0xFF, v3 = c3 >>> shift & 0xFF;
		if (kind == 0) return (v0 + v1 + v2 + v3) / 4;
		int t0 = type(v0, kind), t1 = type(v1, kind), t2 = type(v2, kind), t3 = type(v3, kind);
		int target = majority(t0, t1, t2, t3);
		int sum = 0, n = 0;
		if (t0 == target) { sum += v0; n++; }
		if (t1 == target) { sum += v1; n++; }
		if (t2 == target) { sum += v2; n++; }
		if (t3 == target) { sum += v3; n++; }
		return sum / n;
	}

	private static int type(int v, int kind) {
		return switch (kind) {
			case 1 -> v < 230 ? 0 : v - 229;
			case 2 -> v < 65 ? 0 : 1;
			default -> v < 255 ? 0 : 1;
		};
	}

	/** Most frequent of four; ties go to the earlier argument. */
	static int majority(int t0, int t1, int t2, int t3) {
		if (t0 != t1 && t0 != t2) {
			if (t2 == t3) return t2;
			if (t0 != t3 && (t1 == t2 || t1 == t3)) return t1;
		}
		return t0;
	}
}
