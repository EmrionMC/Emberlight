package dev.emrion.shaders.mod.framegen;

import java.util.Locale;
import java.util.Properties;

/**
 * The frame-generation option, stored in {@code config/emberlight.properties} as {@code framegen.mode}
 * (off | fsr | dlss); {@code -Demberlight.framegen.mode} (= {@code -Demrion.framegen.mode}) overrides the file.
 */
public final class FrameGenConfig {
	private FrameGenConfig() {}

	public enum Mode {
		OFF("Off"), FSR("AMD FSR 3 Frame Generation"), DLSS("NVIDIA DLSS Frame Generation");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	public static volatile Mode mode = Mode.OFF;
	/** Pause interpolation while the real FPS already reach the monitor's refresh rate. */
	public static volatile boolean autoPause = true;
	/** Set at client stop: MC still renders frames while it saves the world, the presenter must not start again. */
	static volatile boolean closed;

	public static void load(Properties p) {
		mode = parse(System.getProperty("emrion.framegen.mode", p.getProperty("framegen.mode", "off")));
		autoPause = !"false".equals(System.getProperty("emberlight.framegen.autoBypass",
				System.getProperty("emrion.framegen.autoBypass", p.getProperty("framegen.autoPause", "true"))));
	}

	public static void store(Properties p) {
		p.setProperty("framegen.mode", mode.name().toLowerCase(Locale.ROOT));
		p.setProperty("framegen.autoPause", Boolean.toString(autoPause));
	}

	static Mode parse(String v) {
		try {
			return Mode.valueOf(v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return Mode.OFF;
		}
	}

	/** Why DLSS FG cannot run on this machine, or null. */
	public static String dlssUnavailable() {
		return DlssFrameGen.unavailable();
	}

	/** The mode that really runs: DLSS falls back to Off when DLSS FG is unavailable on this machine. */
	public static Mode effective() {
		if (closed || Presenter.wedged()) return Mode.OFF;
		Mode m = mode;
		return m == Mode.DLSS && DlssFrameGen.unavailable() != null ? Mode.OFF : m;
	}
}
