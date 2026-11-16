package dev.emrion.shaders.mod.rt;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;

/**
 * The RT options, stored in {@code config/emberlight.properties}, never in the Iris settings file.
 * Keys: {@code rt.enabled} (master switch, default false), {@code rt.shadows} (hybrid | full | off), {@code rt.reflections}
 * (true | false), {@code rt.shadowRays} (1–16), {@code rt.distance} (chunks, 0 = render distance), {@code rt.refraction},
 * {@code rt.caustics}, {@code rt.ao} (RT ambient occlusion instead of the pack's SSAO), {@code rt.shadowEntities} (entities cast
 * RT shadows, default true), {@code rt.blockLightRays} (0 = off, 1–16, default 4), {@code rt.gi} (off | low | medium | high, default low),
 * {@code rt.style} (rtx | faithful, default rtx), {@code rt.omm} (opacity micromaps, default true), {@code rt.device} (read by {@link RtCapability} at boot).
 */
public final class RtConfig {
	private RtConfig() {}

	public static final List<String> SHADOW_MODES = List.of("hybrid", "full", "off");

	public static volatile boolean enabled;
	public static volatile String shadows = "hybrid";
	public static volatile boolean reflections = true;
	public static volatile int shadowRays = 1;
	public static volatile int distance;
	/** RT refraction through water and glass, RT water caustics. */
	public static volatile boolean refraction, caustics;
	public static volatile boolean ao;
	public static volatile boolean shadowEntities = true;
	/** RT block-light shadows: shadow rays per pixel, 0 = off. */
	public static volatile int blockLightRays;
	/** RT global illumination quality: off | low (half res, 1 ray) | medium (half res, 2 rays) | high (full res, 2 rays). */
	public static final List<String> GI_MODES = List.of("off", "low", "medium", "high");
	public static volatile String gi = "off";
	/**
	 * faithful = RT only makes the pack's own effects more accurate; rtx (default) = RT lights the scene. RTX sun shadows are
	 * always fully traced (no hybrid) with at least 4 rays; every other switch works in both styles.
	 */
	public static final List<String> STYLES = List.of("rtx", "faithful");
	public static volatile String style = "rtx";
	/** Opacity micromaps for cutout terrain where the GPU has them (RtOmm): same image, fewer alpha tests. */
	public static volatile boolean omm = true;
	/** Tuning only: extra converter request entries, '+'-separated ({@code rt.extra=blocklight:strength=6+gi:debug=2}). */
	public static volatile String extra = "";

	/** {@code -Demrion.rt.<key>} overrides the file (autotests). */
	public static void load(Properties p) {
		enabled = Boolean.parseBoolean(get(p, "enabled", "false"));
		String s = get(p, "shadows", "hybrid");
		shadows = SHADOW_MODES.contains(s) ? s : "hybrid";
		reflections = Boolean.parseBoolean(get(p, "reflections", "true"));
		shadowRays = parse(get(p, "shadowRays", null), 1, 1, 16);
		distance = parse(get(p, "distance", null), 0, 0, 64);
		refraction = Boolean.parseBoolean(get(p, "refraction", "false"));
		caustics = Boolean.parseBoolean(get(p, "caustics", "false"));
		ao = Boolean.parseBoolean(get(p, "ao", "false"));
		shadowEntities = Boolean.parseBoolean(get(p, "shadowEntities", "true"));
		blockLightRays = parse(get(p, "blockLightRays", null), 4, 0, 16);
		String g = get(p, "gi", "low");
		gi = GI_MODES.contains(g) ? g : "off";
		String st = get(p, "style", "rtx");
		style = STYLES.contains(st) ? st : "rtx";
		extra = get(p, "extra", "");
		omm = Boolean.parseBoolean(get(p, "omm", "true"));
	}

	public static boolean rtx() {
		return style.equals("rtx");
	}

	private static String get(Properties p, String key, String def) {
		return System.getProperty("emrion.rt." + key, p.getProperty("rt." + key, def));
	}

	public static void store(Properties p) {
		p.setProperty("rt.enabled", Boolean.toString(enabled));
		p.setProperty("rt.shadows", shadows);
		p.setProperty("rt.reflections", Boolean.toString(reflections));
		p.setProperty("rt.shadowRays", Integer.toString(shadowRays));
		p.setProperty("rt.distance", Integer.toString(distance));
		p.setProperty("rt.refraction", Boolean.toString(refraction));
		p.setProperty("rt.caustics", Boolean.toString(caustics));
		p.setProperty("rt.ao", Boolean.toString(ao));
		p.setProperty("rt.shadowEntities", Boolean.toString(shadowEntities));
		p.setProperty("rt.blockLightRays", Integer.toString(blockLightRays));
		p.setProperty("rt.gi", gi);
		p.setProperty("rt.style", style);
		if (!extra.isEmpty()) p.setProperty("rt.extra", extra);
		p.setProperty("rt.omm", Boolean.toString(omm));
	}

	/** The converter's RT effect set; empty (the plain Iris-equivalent conversion) unless RT is on and supported. */
	public static Set<String> request() {
		Set<String> s = new TreeSet<>();
		if (!enabled || !RtCapability.enabled) return s;
		boolean rtx = rtx();
		if (!shadows.equals("off")) {
			s.add("shadows");
			if (shadows.equals("full")) s.add("shadows:full");
			if (shadowRays != 1) s.add("shadows:rays=" + shadowRays);
			if (shadowEntities) s.add("shadows:entities");
			if (rtx) s.add("shadows:rtx");
		}
		if (reflections) s.add("reflections");
		if (rtx) {
			s.add("materials"); // material table for packs without labPBR maps
			s.add("shafts");
		}
		if (refraction) {
			s.add("refraction");
			s.add("refraction:entities"); // mobs standing in water refract with the ground around them
			if (rtx) s.add("refraction:glass=0"); // stained glass bent by its normal map looked jagged; water still refracts
		}
		if (caustics) s.add("caustics");
		if (ao) s.add("ao");
		int blockRays = blockLightRays;
		if (blockRays > 0) {
			s.add("blocklight");
			if (blockRays != 4) s.add("blocklight:rays=" + blockRays);
			if (rtx) s.add("blocklight:rtx");
		}
		if (!gi.equals("off")) {
			if (rtx) {
				s.add("gi:rtx");
				s.add("gi:entities"); // its primary rays hit entities: they stay in the TLAS, shadow rays skip them unless shadows:entities
			}
			s.add("gi");
			if (!gi.equals("low")) s.add("gi:rays=2");
			if (gi.equals("high")) s.add("gi:res=1.0");
			String debug = System.getProperty("emrion.rt.giDebug"); // 1 = history match (red), 2 = GI only (x4)
			if (debug != null) s.add("gi:debug=" + debug);
		}
		for (String e : extra.split("\\+")) if (!e.isBlank()) s.add(e.strip());
		return s;
	}

	/** True if an applied effect of {@code rt} traces entities ({@code <effect>:entities}): RtScene then builds entity BLAS. */
	public static boolean wantsEntities(java.util.Collection<String> applied) {
		return applied.stream().anyMatch(e -> e.endsWith(":entities"));
	}

	/** "shadows (hybrid), reflections" for messages; empty when nothing is selected. */
	public static String describe() {
		String r = shadows.equals("off") ? "" : "shadows (" + shadows + ")";
		r = reflections ? (r.isEmpty() ? "reflections" : r + ", reflections") : r;
		r = gi.equals("off") ? r : (r.isEmpty() ? "" : r + ", ") + "GI (" + gi + ")";
		return rtx() ? (r.isEmpty() ? "RTX" : "RTX: " + r) : r;
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
