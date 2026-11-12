package dev.emrion.shaders.converter.pack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Iris standard macros (iris-spec §3.5) for a given environment. Owner: agent (a).
 * Values must match what Iris defines on the user's machine (NVIDIA, Linux, MC 26.3) so the pack takes the same branches.
 *
 * <p>Contents of {@link #build}, in Iris order (Iris StandardMacros + IrisDefines):
 * <ul>
 *   <li>MC_VERSION: "26.3" → 260300 (major + 2-digit minor + 2-digit patch).</li>
 *   <li>MC_MIPMAP_LEVEL: 4 (the user's video setting; the mod may override it).</li>
 *   <li>IRIS_VERSION: 11107, the Iris 1.11.7 the user runs (spec read from checkout bff1e69 = 1.11.4). The pack only tests ≥10800 and &lt;11103.</li>
 *   <li>MC_GL_VERSION 460, MC_GLSL_VERSION 460: NVIDIA reports "4.6.0 NVIDIA …" / "4.60 NVIDIA".</li>
 *   <li>MC_OS_LINUX | MC_OS_WINDOWS | MC_OS_MAC | MC_OS_UNKNOWN; MC_GL_VENDOR_* and MC_GL_RENDERER_* by prefix of the
 *       lower-cased vendor/renderer string, exactly Iris's tables.</li>
 *   <li>IS_IRIS, IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS, IRIS_HAS_TRANSLUCENCY_SORTING (empty), MAX_COLOR_BUFFERS 32,
 *       IRIS_TAG_SUPPORT 2.</li>
 *   <li>DH_BLOCK_UNKNOWN 0 … DH_BLOCK_ILLUMINATED 15.</li>
 *   <li>MC_NORMAL_MAP, MC_SPECULAR_MAP (empty), MC_RENDER_QUALITY 1.0, MC_SHADOW_QUALITY 1.0, MC_HAND_DEPTH 0.125.</li>
 *   <li>MC_RENDER_STAGE_&lt;phase&gt; = WorldRenderingPhase ordinal (NONE 0 … HAND_TRANSLUCENT 23).</li>
 *   <li>BIOME_&lt;PATH&gt; = registration index in MC 26.3's Biomes class (THE_VOID 0 … END_BARRENS 66).</li>
 *   <li>CAT_&lt;CATEGORY&gt; = Iris BiomeCategories ordinal (NONE 0 … UNDERGROUND 18); PPT_NONE 0, PPT_RAIN 1, PPT_SNOW 2.</li>
 *   <li>IRIS_FEATURE_&lt;flag&gt; (empty) for every flag in {@link #SUPPORTED_FEATURES}. Iris itself never adds these to
 *       programs unconditionally: {@link DefaultPackLoader} treats them as the "usable flags" set and re-adds them per
 *       Iris rules (all usable flags for shaders.properties, only the pack's optional flags for programs).</li>
 * </ul>
 * Not emitted (environment-dependent, unused by the target pack): MC_GL_&lt;extension&gt; for every GL extension,
 * labPBR texture-format macros, DISTANT_HORIZONS*, IRIS_HAS_CONNECTED_TEXTURES (Iris adds it when Continuity is loaded;
 * callers that want it add it to the map), IS_MONOCLE, ALLOWS_UNKNOWN_SHADERS.
 */
public final class StandardMacros {
	private StandardMacros() {}

	public record Environment(String mcVersion, String glVendor, String glRenderer, String os) {
		public static final Environment DEFAULT = new Environment("26.3", "NVIDIA Corporation", "NVIDIA GeForce RTX 4070 Ti", "linux");
	}

	/** Iris 1.11.4 → 1 + 11 + 04. */
	public static final String IRIS_VERSION = "11107";

	/** Feature flags (iris-spec §4.2) the converter and mod implement. */
	public static final Set<String> SUPPORTED_FEATURES = Set.of(
			"SEPARATE_HARDWARE_SAMPLERS", "HIGHER_SHADOWCOLOR", "CUSTOM_IMAGES", "PER_BUFFER_BLENDING", "COMPUTE_SHADERS",
			"ENTITY_TRANSLUCENT", "REVERSED_CULLING", "BLOCK_EMISSION_ATTRIBUTE", "CAN_DISABLE_WEATHER", "SSBO",
			"FADE_VARIABLE", "TEXTURE_FILTERING");

	/** Iris WorldRenderingPhase in ordinal order. */
	public static final List<String> RENDER_STAGES = List.of(
			"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED",
			"TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT",
			"TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");

	/** MC 26.3 biome keys in registration order; Iris numbers them in that order (MixinBiomes). */
	public static final List<String> BIOMES = List.of(
			"the_void", "plains", "sunflower_plains", "snowy_plains", "ice_spikes", "desert", "swamp", "mangrove_swamp", "forest",
			"flower_forest", "birch_forest", "dappled_forest", "dark_forest", "pale_garden", "old_growth_birch_forest",
			"old_growth_pine_taiga", "old_growth_spruce_taiga", "taiga", "snowy_taiga", "savanna", "savanna_plateau",
			"windswept_hills", "windswept_gravelly_hills", "windswept_forest", "windswept_savanna", "jungle", "sparse_jungle",
			"bamboo_jungle", "badlands", "eroded_badlands", "wooded_badlands", "meadow", "cherry_grove", "grove", "snowy_slopes",
			"frozen_peaks", "jagged_peaks", "stony_peaks", "river", "frozen_river", "beach", "snowy_beach", "stony_shore",
			"warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean",
			"frozen_ocean", "deep_frozen_ocean", "mushroom_fields", "dripstone_caves", "lush_caves", "deep_dark", "sulfur_caves",
			"nether_wastes", "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "the_end", "end_highlands",
			"end_midlands", "small_end_islands", "end_barrens");

	/** Iris BiomeCategories in ordinal order. */
	public static final List<String> BIOME_CATEGORIES = List.of(
			"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST",
			"OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND");

	private static final List<String> DH_BLOCKS = List.of(
			"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND", "TERRACOTTA",
			"NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED");

	public static Map<String, String> build(Environment env) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("MC_VERSION", formatVersion(env.mcVersion()));
		m.put("MC_MIPMAP_LEVEL", "4");
		m.put("IRIS_VERSION", IRIS_VERSION);
		m.put("MC_GL_VERSION", "460");
		m.put("MC_GLSL_VERSION", "460");
		m.put(osMacro(env.os()), "");
		m.put(vendorMacro(env.glVendor()), "");
		m.put(rendererMacro(env.glRenderer()), "");
		m.put("IS_IRIS", "");
		m.put("IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS", "");
		m.put("MAX_COLOR_BUFFERS", "32");
		m.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
		m.put("IRIS_TAG_SUPPORT", "2");
		for (int i = 0; i < DH_BLOCKS.size(); i++) m.put("DH_BLOCK_" + DH_BLOCKS.get(i), Integer.toString(i));
		m.put("MC_NORMAL_MAP", "");
		m.put("MC_SPECULAR_MAP", "");
		m.put("MC_RENDER_QUALITY", "1.0");
		m.put("MC_SHADOW_QUALITY", "1.0");
		m.put("MC_HAND_DEPTH", "0.125");
		for (int i = 0; i < RENDER_STAGES.size(); i++) m.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), Integer.toString(i));
		for (int i = 0; i < BIOMES.size(); i++) m.put("BIOME_" + BIOMES.get(i).toUpperCase(Locale.ROOT), Integer.toString(i));
		for (int i = 0; i < BIOME_CATEGORIES.size(); i++) m.put("CAT_" + BIOME_CATEGORIES.get(i), Integer.toString(i));
		m.put("PPT_NONE", "0");
		m.put("PPT_RAIN", "1");
		m.put("PPT_SNOW", "2");
		SUPPORTED_FEATURES.stream().sorted().forEach(f -> m.put("IRIS_FEATURE_" + f, ""));
		return m;
	}

	/** Iris formatVersionString: "26.3" → "260300", "1.21.4" → "12104". */
	static String formatVersion(String version) {
		String[] p = version.split("\\.");
		String minor = p[1].length() == 1 ? "0" + p[1] : p[1];
		String patch = p.length < 3 ? "00" : (p[2].length() == 1 ? "0" + p[2] : p[2]);
		return p[0] + minor + patch;
	}

	private static String osMacro(String os) {
		String o = os.toLowerCase(Locale.ROOT);
		if (o.contains("linux")) return "MC_OS_LINUX";
		if (o.contains("win")) return "MC_OS_WINDOWS";
		if (o.contains("mac") || o.contains("osx")) return "MC_OS_MAC";
		return "MC_OS_UNKNOWN";
	}

	private static String vendorMacro(String vendor) {
		String v = vendor.toLowerCase(Locale.ROOT);
		if (v.startsWith("ati")) return "MC_GL_VENDOR_ATI";
		if (v.startsWith("intel")) return "MC_GL_VENDOR_INTEL";
		if (v.startsWith("nvidia")) return "MC_GL_VENDOR_NVIDIA";
		if (v.startsWith("amd")) return "MC_GL_VENDOR_AMD";
		if (v.startsWith("x.org")) return "MC_GL_VENDOR_XORG";
		return "MC_GL_VENDOR_OTHER";
	}

	private static String rendererMacro(String renderer) {
		String r = renderer.toLowerCase(Locale.ROOT);
		if (r.startsWith("amd") || r.startsWith("ati") || r.startsWith("radeon")) return "MC_GL_RENDERER_RADEON";
		if (r.startsWith("gallium")) return "MC_GL_RENDERER_GALLIUM";
		if (r.startsWith("intel")) return "MC_GL_RENDERER_INTEL";
		if (r.startsWith("geforce") || r.startsWith("nvidia")) return "MC_GL_RENDERER_GEFORCE";
		if (r.startsWith("quadro") || r.startsWith("nvs")) return "MC_GL_RENDERER_QUADRO";
		if (r.startsWith("mesa")) return "MC_GL_RENDERER_MESA";
		if (r.startsWith("apple")) return "MC_GL_RENDERER_APPLE";
		return "MC_GL_RENDERER_OTHER";
	}
}
