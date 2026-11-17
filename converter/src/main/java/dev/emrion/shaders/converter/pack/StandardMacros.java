package dev.emrion.shaders.converter.pack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Iris standard macros (StandardMacros + IrisDefines) for a given environment, in Iris order. Values must match Iris on the
 * same machine so the pack takes the same branches. IRIS_FEATURE_* here are the usable flags; DefaultPackLoader re-adds them
 * per Iris rules. DISTANT_HORIZONS* are left out; the MC_GL_&lt;extension&gt; macros ({@link #GL_EXTENSIONS}) are added by the loader so the
 * manifest's macro list (and with it the cache key) stays as it was.
 */
public final class StandardMacros {
	private StandardMacros() {}

	public record Environment(String mcVersion, String glVendor, String glRenderer, String os) {
		public static final Environment DEFAULT = new Environment("26.3", "NVIDIA Corporation", "NVIDIA GeForce RTX 4070 Ti", "linux");
	}

	/** Iris 1.11.4 → 1 + 11 + 04. */
	public static final String IRIS_VERSION = "11107";

	/** Feature flags the converter and mod implement. */
	public static final Set<String> SUPPORTED_FEATURES = Set.of(
			"SEPARATE_HARDWARE_SAMPLERS", "HIGHER_SHADOWCOLOR", "CUSTOM_IMAGES", "PER_BUFFER_BLENDING", "COMPUTE_SHADERS",
			"ENTITY_TRANSLUCENT", "REVERSED_CULLING", "BLOCK_EMISSION_ATTRIBUTE", "CAN_DISABLE_WEATHER", "SSBO",
			"FADE_VARIABLE", "TEXTURE_FILTERING", "TESSELLATION_SHADERS");

	/**
	 * GL extensions Iris reports as MC_GL_&lt;name&gt; that are core in GL 4.6 and compile unchanged to SPIR-V, so a pack's
	 * {@code #ifdef MC_GL_ARB_shader_image_load_store} branches (Eclipse's Nether/End voxelization) take Iris's path.
	 * shortcut: a fixed subset of what the NVIDIA driver lists; add one when a pack tests another core extension.
	 */
	public static final List<String> GL_EXTENSIONS = List.of(
			"GL_ARB_compute_shader", "GL_ARB_conservative_depth", "GL_ARB_derivative_control", "GL_ARB_explicit_attrib_location",
			"GL_ARB_explicit_uniform_location", "GL_ARB_gpu_shader5", "GL_ARB_separate_shader_objects", "GL_ARB_shader_bit_encoding",
			"GL_ARB_shader_image_load_store", "GL_ARB_shader_image_size", "GL_ARB_shader_storage_buffer_object",
			"GL_ARB_shader_texture_lod", "GL_ARB_shading_language_420pack", "GL_ARB_shading_language_packing",
			"GL_ARB_tessellation_shader", "GL_ARB_texture_cube_map_array", "GL_ARB_texture_gather", "GL_ARB_texture_query_levels",
			"GL_ARB_texture_query_lod");

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
