package dev.emrion.shaders.mod.rt;

import dev.emrion.shaders.mod.upscale.UpscaleConfig.Preset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * RT quality presets (Low / Medium / High / Ultra) as plain {@code rt.*} values, and the GPU table that picks one on the first
 * start. Calibrated on an RTX 4070 Ti at 1440p with Complementary Unbound + Euphoria Patches (docs/RAYTRACING.md, "Presets").
 * {@code rt.enabled} and {@code rt.omm} are not part of a preset.
 */
public enum RtPreset {
	LOW("Low", "Fastest", "Faithful style: hybrid sun shadows and RT reflections; no GI, no RT block light",
			"style=faithful,shadows=hybrid,shadowRays=1,shadowEntities=false,gi=off,blockLightRays=0,ao=false,reflections=true,refraction=false,caustics=false,distance=8", Preset.PERFORMANCE),
	MEDIUM("Medium", "RTX, light GI", "RTX style: traced sun shadows, coloured block light, half-resolution GI (1 ray)",
			"style=rtx,shadows=full,shadowRays=4,shadowEntities=true,gi=low,blockLightRays=2,ao=false,reflections=true,refraction=false,caustics=false,distance=12", Preset.BALANCED),
	HIGH("High", "Most effects", "RTX style: adds RT AO and caustics, GI with 2 rays (no refraction: it costs a third of the frame rate over water)",
			"style=rtx,shadows=full,shadowRays=4,shadowEntities=true,gi=medium,blockLightRays=2,ao=true,reflections=true,refraction=false,caustics=true,distance=16", Preset.QUALITY),
	ULTRA("Ultra", "Everything on", "RTX style, everything on: full-resolution GI, full trace distance",
			"style=rtx,shadows=full,shadowRays=4,shadowEntities=true,gi=high,blockLightRays=2,ao=true,reflections=true,refraction=true,caustics=true,distance=0", Preset.QUALITY);

	/** hint: a few words under the preset tile; description: its tooltip. */
	public final String label, hint, description;
	/** The upscaling preset suggested with this RT preset on the first start (only when the user never chose an upscaler). */
	public final Preset upscale;
	/** rt.KEY (without "rt.") → value, in the form {@link RtConfig#store} writes. */
	public final Map<String, String> values = new LinkedHashMap<>();

	RtPreset(String label, String hint, String description, String values, Preset upscale) {
		this.label = label;
		this.hint = hint;
		this.upscale = upscale;
		this.description = description;
		for (String kv : values.split(",")) this.values.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
	}

	/** Writes this preset's keys into {@code p} (others untouched); {@link RtConfig#load} then applies them. */
	public void applyTo(Properties p) {
		values.forEach((k, v) -> p.setProperty("rt." + k, v));
	}

	/** Sets the live {@link RtConfig} to this preset (enabled and omm unchanged). */
	public void apply() {
		Properties p = new Properties();
		RtConfig.store(p);
		applyTo(p);
		RtConfig.load(p);
	}

	/** The preset the stored values match, null = Custom. */
	public static RtPreset detect(Properties p) {
		for (RtPreset r : values())
			if (r.values.entrySet().stream().allMatch(e -> e.getValue().equals(p.getProperty("rt." + e.getKey())))) return r;
		return null;
	}

	/** The live values' preset, null = Custom. */
	public static RtPreset current() {
		Properties p = new Properties();
		RtConfig.store(p);
		return detect(p);
	}

	public static RtPreset parse(String name) {
		return valueOf(name.strip().toUpperCase(Locale.ROOT));
	}

	// ---- first-start pick from the GPU ---------------------------------------------------------------------------------

	public static final int NVIDIA = 0x10DE, AMD = 0x1002, INTEL = 0x8086;

	private record Rule(int vendor, Pattern name, RtPreset preset) {}

	/**
	 * First match wins; matched against the lower-case device name. Edit this table to move a GPU between tiers. A GPU with
	 * RT but no match falls back to its VRAM ({@link #byVram}); "laptop"/"mobile" parts go one tier down.
	 */
	private static final List<Rule> TABLE = List.of(
			// NVIDIA RTX 50
			rule(NVIDIA, "rtx 50(90|80)|rtx 5070 ti", ULTRA), rule(NVIDIA, "rtx 5070", HIGH), rule(NVIDIA, "rtx 5060", MEDIUM), rule(NVIDIA, "rtx 50", LOW),
			// RTX 40
			rule(NVIDIA, "rtx 40(90|80)", ULTRA), rule(NVIDIA, "rtx 4070", HIGH), rule(NVIDIA, "rtx 4060", MEDIUM), rule(NVIDIA, "rtx 40", LOW),
			// RTX 30 and 20
			rule(NVIDIA, "rtx 30(90|80)", HIGH), rule(NVIDIA, "rtx 30(70|60)", MEDIUM), rule(NVIDIA, "rtx 2080 ti", MEDIUM), rule(NVIDIA, "rtx [23]0", LOW),
			// AMD RDNA 4, 3, 2
			rule(AMD, "rx 9070", HIGH), rule(AMD, "rx 9060", MEDIUM), rule(AMD, "rx 7900|rx 7800", HIGH), rule(AMD, "rx 7[67]00", MEDIUM),
			rule(AMD, "rx 6[789][05]0", MEDIUM), rule(AMD, "rx [67]", LOW),
			// Intel Arc
			rule(INTEL, "arc.*\\b(a7[0-9]{2}|b5[0-9]{2})", MEDIUM), rule(INTEL, "arc", LOW));

	private static Rule rule(int vendor, String regex, RtPreset preset) {
		return new Rule(vendor, Pattern.compile(regex), preset);
	}

	/** Unknown RT GPUs: by VRAM, at most High (an unknown name is more likely old or a workstation card than a top gaming GPU). */
	static RtPreset byVram(long vramBytes) {
		long gib = vramBytes >> 30;
		return gib >= 15 ? HIGH : gib >= 11 ? MEDIUM : LOW;
	}

	/**
	 * The preset for a GPU, null = RT off (no RT support).
	 * @param integrated VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: shares system memory, always Low
	 * @param vramBytes the largest device-local heap
	 */
	public static RtPreset forGpu(boolean rt, int vendor, String deviceName, long vramBytes, boolean integrated) {
		if (!rt) return null;
		String name = deviceName.toLowerCase(Locale.ROOT);
		if (integrated || vramBytes < (7L << 30)) return LOW; // < 8 GB (heaps report a little under the nominal size)
		RtPreset pick = null;
		for (Rule r : TABLE)
			if (r.vendor == vendor && r.name.matcher(name).find()) {
				pick = r.preset;
				break;
			}
		if (pick == null) pick = byVram(vramBytes);
		if (name.contains("laptop") || name.contains("mobile") || name.contains("max-q")) pick = values()[Math.max(0, pick.ordinal() - 1)];
		return pick;
	}
}
