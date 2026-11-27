package dev.emrion.shaders.mod.upscale;

import java.util.Locale;
import java.util.Properties;

/** Upscaling options in {@code config/emberlight.properties}; {@code -Demrion.upscale.<key>} overrides. */
public final class UpscaleConfig {
	private UpscaleConfig() {}

	public enum Mode {
		OFF("Off", "Off"), FSR("AMD FSR 3.1", "FSR"), DLSS("NVIDIA DLSS", "DLSS"), XESS("Intel XeSS", "XeSS");

		public final String label, shortLabel;

		Mode(String label, String shortLabel) {
			this.label = label;
			this.shortLabel = shortLabel;
		}
	}

	/** Render size = display size / ratio (FSR's ratios; DLSS accepts the same render sizes). */
	public enum Preset {
		NATIVE("Native AA", 1.0f), QUALITY("Quality", 1.5f), BALANCED("Balanced", 1.7f), PERFORMANCE("Performance", 2.0f),
		ULTRA("Ultra Performance", 3.0f);

		public final String label;
		public final float ratio;

		Preset(String label, float ratio) {
			this.label = label;
			this.ratio = ratio;
		}
	}

	public static volatile Mode mode = Mode.OFF;
	public static volatile Preset preset = Preset.QUALITY;
	public static volatile float sharpness = 0.5f;
	/** DLSS Ray Reconstruction instead of DLSS Super Resolution (docs/UPSCALING.md §11); only with DLSS and RT on. */
	public static volatile boolean rayReconstruction;
	/** DLSS SR render preset: "auto" or a letter (K, L, M, ...). Auto = M on RTX 40/50 (FP8): in fast flight it trailed far less
	 * than NGX's default K (block edges smearing onto sky and clouds), for about 0.2 ms on a 4070 Ti; NGX's default elsewhere,
	 * where M costs more. */
	public static volatile String dlssModel = "auto";

	public static void load(Properties p) {
		mode = parse(Mode.class, get(p, "mode", "off"), Mode.OFF);
		preset = parse(Preset.class, get(p, "preset", "quality"), Preset.QUALITY);
		rayReconstruction = Boolean.parseBoolean(get(p, "rr", "false").trim());
		dlssModel = get(p, "dlssModel", "auto").trim().toLowerCase(Locale.ROOT);
		try {
			sharpness = Math.clamp(Float.parseFloat(get(p, "sharpness", "0.5").trim()), 0f, 1f);
		} catch (NumberFormatException e) {
			sharpness = 0.5f;
		}
	}

	public static void store(Properties p) {
		p.setProperty("upscale.mode", mode.name().toLowerCase(Locale.ROOT));
		p.setProperty("upscale.preset", preset.name().toLowerCase(Locale.ROOT));
		p.setProperty("upscale.sharpness", Float.toString(sharpness));
		p.setProperty("upscale.rr", Boolean.toString(rayReconstruction));
		p.setProperty("upscale.dlssModel", dlssModel);
	}

	/** {@link #dlssModel} as NGX's render preset number (A = 1 ... Z = 26), 0 = NGX's default. */
	public static int dlssPreset() {
		String m = dlssModel;
		if (m.length() == 1 && m.charAt(0) >= 'a' && m.charAt(0) <= 'z') return m.charAt(0) - 'a' + 1;
		if (!m.equals("auto")) return 0;
		String gpu = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name();
		return gpu.matches("(?i).*\\bRTX (PRO )?[45]0\\d\\d\\b.*|.*\\bRTX PRO \\d+ Blackwell\\b.*|.*\\bRTX \\d+ Ada\\b.*") ? 'm' - 'a' + 1 : 0;
	}

	/** The mode that really runs: DLSS or XeSS falls back to FSR (same render size and conversion, so no reload). */
	public static Mode effective() {
		Mode m = mode;
		return m == Mode.DLSS && Dlss.unavailable() != null || m == Mode.XESS && Xess.unavailable() != null ? Mode.FSR : m;
	}

	/**
	 * History length our GI and block-light temporal passes keep under RR (-Demrion.upscale.rrFrames). 1 (no history) left
	 * the raw noise to the pack's non-linear lighting maths, which darkened walls by 2-4 % and boiled more under water; 4
	 * frames matched SR's brightness and flickered less than SR (docs/UPSCALING.md §11.4).
	 */
	private static final int RR_FRAMES = Integer.getInteger("emrion.upscale.rrFrames", 4);

	/** Why Ray Reconstruction cannot run with the current settings and pack (null: it runs while selected). */
	public static String rrUnavailable() {
		var s = dev.emrion.shaders.mod.pack.PackManager.rtSupport();
		return rrUnavailable(s != null && s.nativePack());
	}

	/** A native pack traces its own rays (docs/NATIVE_FORMAT.md §14.4): Emberlight's RT switch does not matter for it. */
	public static String rrUnavailable(boolean nativePack) {
		if (effective() != Mode.DLSS) return "needs NVIDIA DLSS as the upscaler";
		if (!dev.emrion.shaders.mod.rt.RtCapability.enabled || !nativePack && !dev.emrion.shaders.mod.rt.RtConfig.enabled) return "needs ray tracing on";
		return Dlss.rrUnavailable();
	}

	/** Ray Reconstruction selected and able to run: DLSS-D replaces DLSS SR and our RT denoisers step aside. */
	public static boolean rrActive() {
		return rayReconstruction && rrUnavailable() == null;
	}

	/**
	 * Adds the converter effect that removes the pack's own TAA (empty when off, so Off converts exactly as without upscaling),
	 * and with Ray Reconstruction the options that turn our GI and block-light denoisers off (RR wants the noisy signal).
	 */
	public static void addRequest(java.util.Set<String> request, boolean nativePack) {
		if (effective() != Mode.OFF) request.add("upscale");
		if (!rayReconstruction || rrUnavailable(nativePack) != null) return;
		if (nativePack) request.add("rr"); // the pack compiles its guide passes and skips its own denoisers
		if (request.contains("gi")) {
			request.add("gi:frames=" + RR_FRAMES); // a short temporal history instead of 24 frames
			request.add("gi:boost=1"); // no extra rays for young history
			request.add("gi:filter=0"); // no a-trous
		}
		if (request.contains("blocklight:rtx")) {
			request.add("blocklight:frames=" + RR_FRAMES);
			request.add("blocklight:filter=0");
		}
	}

	private static String get(Properties p, String key, String def) {
		return System.getProperty("emrion.upscale." + key, p.getProperty("upscale." + key, def));
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String v, E def) {
		try {
			return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return def;
		}
	}
}
