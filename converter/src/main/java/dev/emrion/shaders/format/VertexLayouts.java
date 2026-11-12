package dev.emrion.shaders.format;

import java.util.List;
import java.util.Map;

/**
 * Single source of truth for vertex-layout variants and fixed binding/location tables (FORMAT.md §7.1).
 * Both the transpiler (which attributes exist → defaults for missing ones) and the mod (which variant a vanilla
 * pipeline's vertex format maps to) use this class.
 */
public final class VertexLayouts {
	private VertexLayouts() {}

	/** Canonical vertex input locations; name = MC VertexFormatElement name or Iris attribute name. */
	public static final List<String> INPUT_LOCATIONS = List.of(
			"Position", "Color", "UV0", "UV1", "UV2", "Normal",
			"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock",
			"ChunkPosition", "ChunkVisibility", "LineWidth");

	public static int location(String input) {
		int i = INPUT_LOCATIONS.indexOf(input);
		if (i < 0) throw new IllegalArgumentException("unknown vertex input " + input);
		return i;
	}

	/** Placeholder set-0 bindings used in converter SPIR-V; the mod patches them to the vanilla pipeline's order. */
	public static final List<String> SET0_CANONICAL = List.of(
			"DynamicTransforms", "TerrainUniform", "ChunkSection", "Sampler0", "Sampler1", "Sampler2", "CloudInfo", "CloudFaces");

	/** Fixed set-1 bindings that every dimension plan shares. Resource bindings start at {@link #FIRST_RESOURCE_BINDING}. */
	public static final int BINDING_UNIFORMS = 0;
	public static final int BINDING_PHASE = 1;
	public static final int FIRST_RESOURCE_BINDING = 2;

	private static final List<String> TERRAIN = List.of("Position", "Color", "UV0", "UV2", "Normal",
			"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");

	/** Variant key → attributes present. Missing attributes get Iris defaults in the transpiler. */
	public static final Map<String, List<String>> VARIANTS = Map.ofEntries(
			Map.entry("terrain_mdi", concat(TERRAIN, List.of("ChunkPosition", "ChunkVisibility"))),
			Map.entry("terrain_ubo", TERRAIN),
			Map.entry("entity", List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal")),
			Map.entry("particle", List.of("Position", "UV0", "Color", "UV2")),
			Map.entry("position_color", List.of("Position", "Color")),
			Map.entry("position_tex", List.of("Position", "UV0")),
			Map.entry("position_tex_color", List.of("Position", "UV0", "Color")),
			Map.entry("position", List.of("Position")),
			Map.entry("position_color_tex_lightmap", List.of("Position", "Color", "UV0", "UV2")),
			Map.entry("lines", List.of("Position", "Color", "Normal", "LineWidth")),
			Map.entry("fullscreen", List.of())
	);

	private static List<String> concat(List<String> a, List<String> b) {
		var l = new java.util.ArrayList<>(a);
		l.addAll(b);
		return List.copyOf(l);
	}
}
