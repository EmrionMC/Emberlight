package dev.emrion.shaders.converter;

import java.util.Locale;

/**
 * Raw custom texture data to packed texels of the promoted Vulkan format. Only layouts that need no value conversion are
 * accepted; RGB-only formats get an alpha channel.
 */
public final class RawTexels {
	private RawTexels() {}

	/** @throws IllegalArgumentException if the layout needs a conversion we do not do, or the data is too short. */
	public static byte[] convert(byte[] data, String internal, String promoted, String rawPixel, int[] size) {
		String[] px = rawPixel.split(" ");
		String pf = px[0].replace("_INTEGER", "");
		int channels = channels(internal);
		int pixelChannels = switch (pf) {
			case "RED", "R" -> 1;
			case "RG" -> 2;
			case "RGB", "BGR" -> 3;
			case "RGBA", "BGRA" -> 4;
			default -> throw new IllegalArgumentException("pixel format " + px[0]);
		};
		int bytes = switch (px[1]) {
			case "UNSIGNED_BYTE", "BYTE" -> 1;
			case "UNSIGNED_SHORT", "SHORT", "HALF_FLOAT" -> 2;
			case "UNSIGNED_INT", "INT", "FLOAT" -> 4;
			default -> throw new IllegalArgumentException("pixel type " + px[1]);
		};
		boolean floatType = px[1].endsWith("FLOAT");
		boolean floatFormat = internal.endsWith("F");
		String digits = internal.replaceAll("[^0-9]", "");
		int formatBytes = digits.isEmpty() ? -1 : Integer.parseInt(digits) / 8;
		if (pixelChannels != channels || bytes != formatBytes || floatType != floatFormat)
			throw new IllegalArgumentException(rawPixel + " into " + internal + " needs a value conversion");
		long texels = 1;
		for (int s : size) texels *= s;
		long need = texels * channels * bytes;
		if (data.length < need) throw new IllegalArgumentException("file has " + data.length + " bytes, " + need + " needed");
		boolean bgr = pf.startsWith("BGR");
		int outChannels = channels(promoted);
		byte[] one = one(internal, bytes);
		byte[] out = new byte[Math.toIntExact(texels * outChannels * bytes)];
		for (long t = 0; t < texels; t++) {
			int src = (int) (t * channels * bytes), dst = (int) (t * outChannels * bytes);
			for (int c = 0; c < outChannels; c++) {
				int sc = bgr && c < 3 ? 2 - c : c;
				if (c < channels) System.arraycopy(data, src + sc * bytes, out, dst + c * bytes, bytes);
				else System.arraycopy(one, 0, out, dst + c * bytes, bytes);
			}
		}
		return out;
	}

	private static int channels(String format) {
		String f = format.toUpperCase(Locale.ROOT);
		return f.startsWith("RGBA") ? 4 : f.startsWith("RGB") ? 3 : f.startsWith("RG") ? 2 : 1;
	}

	/** 1.0 (or 1 for integer formats) as one little-endian component: the alpha GL reads for RGB data. */
	private static byte[] one(String internal, int bytes) {
		long v;
		if (internal.endsWith("F")) v = bytes == 2 ? 0x3C00 : Float.floatToIntBits(1f);
		else if (internal.endsWith("I")) v = 1;
		else if (internal.endsWith("_SNORM")) v = (1L << (bytes * 8 - 1)) - 1;
		else v = (1L << (bytes * 8)) - 1;
		byte[] b = new byte[bytes];
		for (int i = 0; i < bytes; i++) b[i] = (byte) (v >>> (8 * i));
		return b;
	}
}
