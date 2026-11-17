package dev.emrion.shaders.format;

import java.util.List;
import java.util.Map;

/**
 * Vertex-layout variants and fixed binding/location tables, shared by the transpiler (defaults for missing attributes)
 * and the mod (which variant a vanilla pipeline's vertex format maps to).

 */
public final class VertexLayouts {
	private VertexLayouts() {}

	/**
	 * Canonical vertex input locations; name = MC VertexFormatElement name or Iris attribute name. New inputs are appended so
	 * existing locations keep their numbers. {@code iris_Entity} = (entityId, blockEntityId, currentRenderedItemId) as uvec3.
	 */
	public static final List<String> INPUT_LOCATIONS = List.of(
			"Position", "Color", "UV0", "UV1", "UV2", "Normal",
			"mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock",
			"ChunkPosition", "ChunkVisibility", "LineWidth", "iris_Entity");

	public static int location(String input) {
		int i = INPUT_LOCATIONS.indexOf(input);
		if (i < 0) throw new IllegalArgumentException("unknown vertex input " + input);
		return i;
	}

	/**
	 * Placeholder set-0 bindings used in converter SPIR-V; the mod patches them to the vanilla pipeline's order.
	 * {@code EmrionNormals}/{@code EmrionSpecular} are the labPBR maps of the draw's Sampler0, which the mod pushes itself.
	 */
	public static final List<String> SET0_CANONICAL = List.of(
			"DynamicTransforms", "TerrainUniform", "ChunkSection", "Sampler0", "Sampler1", "Sampler2", "CloudInfo", "CloudFaces",
			"EmrionNormals", "EmrionSpecular", "u_Globals", "u_SectionTimeInfo");

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
			// Sodium's chunk meshes in the mod's extended format (compact 20 B + canonical extras): Position/UV0/UV2 are decoded
			// from SODIUM_INPUTS, at_midBlock is RGBA8_SINT; inputs the vertex format lacks get the mod's constant defaults
			Map.entry("terrain_sodium", TERRAIN),
			Map.entry("entity", List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal", "mc_midTexCoord", "at_tangent", "iris_Entity")),
			Map.entry("particle", List.of("Position", "UV0", "Color", "UV2")),
			// vanilla BLOCK format outside chunk meshes (block in hand/moving blocks, crumbling, beacon beam): DynamicTransforms
			Map.entry("block", List.of("Position", "Color", "UV0", "UV2")),
			Map.entry("position_color", List.of("Position", "Color")),
			// leads (POSITION_COLOR_LIGHTMAP): without UV2 they got the full-bright default lightmap
			Map.entry("position_color_lightmap", List.of("Position", "Color", "UV2")),
			Map.entry("position_tex", List.of("Position", "UV0")),
			Map.entry("position_tex_color", List.of("Position", "UV0", "Color")),
			Map.entry("position", List.of("Position")),
			Map.entry("position_color_tex_lightmap", List.of("Position", "Color", "UV0", "UV2")),
			Map.entry("lines", List.of("Position", "Color", "Normal", "LineWidth")),
			// MC 26.3 clouds: no vertex buffer; Position/Color/Normal are pulled from CloudFaces + CloudInfo by gl_VertexIndex
			Map.entry("clouds", List.of("Position", "Color", "Normal")),
			Map.entry("fullscreen", List.of())
	);

	/** Element names of Sodium's compact chunk vertex format, by input location (0-3; never collide with what they replace). */
	public static final List<String> SODIUM_INPUTS = List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData");
	/** Sodium pushes its 20 B (region offset, time, region id) at offset 0; we move them here, behind EmrionDraw. */
	public static final int SODIUM_PUSH_OFFSET = 48, SODIUM_PUSH_SIZE = 20;

	/** Input location of a vertex format element (canonical or Sodium name), -1 if no shader input reads it. */
	public static int inputLocation(String element) {
		int i = INPUT_LOCATIONS.indexOf(element);
		return i >= 0 ? i : SODIUM_INPUTS.indexOf(element);
	}

	private static List<String> concat(List<String> a, List<String> b) {
		var l = new java.util.ArrayList<>(a);
		l.addAll(b);
		return List.copyOf(l);
	}
}
