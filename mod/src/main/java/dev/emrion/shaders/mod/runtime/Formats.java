package dev.emrion.shaders.mod.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FORMAT.md texture format names (Iris/GL, after promotion) to MC {@link GpuFormat}s. */
public final class Formats {
	private Formats() {}

	/** Custom texture usage bit: {@code VulkanConstMixin} turns it into VK_IMAGE_USAGE_STORAGE_BIT. */
	public static final int USAGE_STORAGE = 1 << 8;
	/** Custom buffer usage bit (above MC's 1..512): {@code VulkanConstMixin} turns it into SHADER_DEVICE_ADDRESS | AS_BUILD_INPUT_READ_ONLY. */
	public static final int USAGE_RT_INPUT = 1 << 12;

	private static final Pattern PLAIN = Pattern.compile("(R|RG|RGB|RGBA)(8|16|32)(F|I|UI|_SNORM)?");

	public static GpuFormat gpu(String name) {
		String n = name.toUpperCase(Locale.ROOT);
		switch (n) {
			case "D32F": return GpuFormat.D32_FLOAT;
			case "R11F_G11F_B10F": return GpuFormat.RG11B10_FLOAT;
			case "RGB10_A2": return GpuFormat.RGB10A2_UNORM;
			case "RGB10_A2UI": return GpuFormat.RGB10A2_UINT;
			// shortcut: packed low-precision GL formats get RGBA8 (more precision, same look); exact packing never matters to packs.
			case "RGBA2", "RGBA4", "RGB5_A1", "RGB565", "R3_G3_B2": return GpuFormat.RGBA8_UNORM;
			default:
		}
		Matcher m = PLAIN.matcher(n);
		if (!m.matches()) throw new IllegalArgumentException("unsupported texture format " + name);
		String type = switch (m.group(3) == null ? "" : m.group(3)) {
			case "F" -> "FLOAT";
			case "I" -> "SINT";
			case "UI" -> "UINT";
			case "_SNORM" -> "SNORM";
			default -> "UNORM";
		};
		return GpuFormat.valueOf(m.group(1) + m.group(2) + "_" + type);
	}

	public static boolean isInteger(GpuFormat f) {
		String s = f.name();
		return s.endsWith("_UINT") || s.endsWith("_SINT");
	}

	public static boolean isUnsigned(GpuFormat f) {
		return f.name().endsWith("_UINT");
	}

	public static int fullMipChain(int w, int h, int d) {
		return 32 - Integer.numberOfLeadingZeros(Math.max(w, Math.max(h, d)));
	}
}
