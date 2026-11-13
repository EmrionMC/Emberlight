package dev.emrion.shaders.mod.rt;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;

/**
 * The RT options (RAYTRACING.md §5), stored in {@code config/emrionshaders.properties}, never in the Iris settings file.
 * Keys: {@code rt.enabled} (master switch, default false), {@code rt.shadows} (hybrid | full | off), {@code rt.shadowRays} (1–16),
 * {@code rt.distance} (chunks, 0 = render distance), {@code rt.device} (read by {@link RtCapability} at boot).
 */
public final class RtConfig {
	private RtConfig() {}

	public static final List<String> SHADOW_MODES = List.of("hybrid", "full", "off");

	public static volatile boolean enabled;
	public static volatile String shadows = "hybrid";
	public static volatile int shadowRays = 1;
	public static volatile int distance;

	/** {@code -Demrion.rt.<key>} overrides the file (autotests). */
	public static void load(Properties p) {
		enabled = Boolean.parseBoolean(get(p, "enabled", "false"));
		String s = get(p, "shadows", "hybrid");
		shadows = SHADOW_MODES.contains(s) ? s : "hybrid";
		shadowRays = parse(get(p, "shadowRays", null), 1, 1, 16);
		distance = parse(get(p, "distance", null), 0, 0, 64);
	}

	private static String get(Properties p, String key, String def) {
		return System.getProperty("emrion.rt." + key, p.getProperty("rt." + key, def));
	}

	/** Writes every RT key into {@code p}. */
	public static void store(Properties p) {
		p.setProperty("rt.enabled", Boolean.toString(enabled));
		p.setProperty("rt.shadows", shadows);
		p.setProperty("rt.shadowRays", Integer.toString(shadowRays));
		p.setProperty("rt.distance", Integer.toString(distance));
	}

	/**
	 * The converter's RT effect set. Empty (= the exact Iris-equivalent conversion) unless RT is on, the GPU supports it and an
	 * effect is selected.
	 */
	public static Set<String> request() {
		Set<String> s = new TreeSet<>();
		if (!enabled || !RtCapability.enabled) return s;
		if (!shadows.equals("off")) {
			s.add("shadows");
			if (shadows.equals("full")) s.add("shadows:full");
			if (shadowRays != 1) s.add("shadows:rays=" + shadowRays);
		}
		return s;
	}

	/** RT distance in blocks, 0 = no limit beyond the render distance. */
	public static double distanceBlocks() {
		int d = distance;
		return d <= 0 ? 0 : Math.min(d, Minecraft.getInstance().options.getEffectiveRenderDistance()) * 16.0;
	}

	private static int parse(String v, int def, int min, int max) {
		try {
			return v == null ? def : Math.clamp(Integer.parseInt(v.trim()), min, max);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
