package dev.emrion.shaders.mod.pipeline;

import static net.minecraft.client.renderer.RenderPipelines.*;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla pipeline → Iris gbuffers program (iris-spec §1.3, Iris's IrisPipelines/ShaderKey). {@code alphaRef} is the
 * ShaderKey default alpha test ("greater", -1 = off), used when the pack sets no {@code alphaTest.<program>}.
 * {@code translucent} splits hand draws between gbuffers_hand (HAND_SOLID) and gbuffers_hand_water (HAND_TRANSLUCENT).
 */
public final class GbufferMapping {
	private GbufferMapping() {}

	public record Entry(String program, float alphaRef, boolean translucent) {}

	static final float OFF = -1f, NON_ZERO = 0.0001f, ONE_TENTH = 0.1f, HALF = 0.5f;

	private static final Map<RenderPipeline, Entry> MAP = new IdentityHashMap<>();

	private static void put(String program, float alpha, boolean translucent, RenderPipeline... pipelines) {
		for (RenderPipeline p : pipelines) MAP.put(p, new Entry(program, alpha, translucent));
	}

	static {
		put("gbuffers_terrain_solid", OFF, false, SOLID_TERRAIN, SOLID_TERRAIN_MULTIDRAW, SOLID_BLOCK, WIREFRAME, WIREFRAME_MULTIDRAW);
		put("gbuffers_terrain_cutout", HALF, false, CUTOUT_TERRAIN, CUTOUT_TERRAIN_MULTIDRAW, CUTOUT_BLOCK);
		put("gbuffers_water", NON_ZERO, true, TRANSLUCENT_TERRAIN, TRANSLUCENT_TERRAIN_MULTIDRAW);
		put("gbuffers_block", ONE_TENTH, true, TRANSLUCENT_BLOCK);
		put("gbuffers_entities", ONE_TENTH, false, ARMOR_CUTOUT_NO_CULL, ARMOR_DECAL_CUTOUT_NO_CULL, ENTITY_CUTOUT_CULL, ENTITY_CUTOUT,
				ENTITY_CUTOUT_DISSOLVE, ENTITY_CUTOUT_Z_OFFSET, ITEM_CUTOUT, ITEM_CUTOUT_GLINT, ITEM_CUTOUT_GLINT_SPECIAL,
				ARMOR_CUTOUT_NO_CULL_GLINT, ENERGY_SWIRL, END_CRYSTAL_BEAM);
		put("gbuffers_entities", OFF, false, ENTITY_SOLID, ENTITY_SOLID_Z_OFFSET_FORWARD, ENTITY_SOLID_GLINT);
		put("gbuffers_entities_translucent", ONE_TENTH, true, WOLF_ARMOR_CRACKS, ENTITY_TRANSLUCENT, ENTITY_TRANSLUCENT_CULL, ITEM_TRANSLUCENT,
				ITEM_TRANSLUCENT_GLINT, ITEM_TRANSLUCENT_GLINT_SPECIAL, BREEZE_WIND, BANNER_PATTERN, ENTITY_SHADOW, TEXT, TEXT_GRAYSCALE,
				TEXT_POLYGON_OFFSET, TEXT_GRAYSCALE_POLYGON_OFFSET, TEXT_SEE_THROUGH, TEXT_GRAYSCALE_SEE_THROUGH);
		put("gbuffers_spidereyes", NON_ZERO, true, EYES);
		put("gbuffers_spidereyes", ONE_TENTH, true, ENTITY_TRANSLUCENT_EMISSIVE);
		put("gbuffers_block", OFF, false, END_PORTAL, END_GATEWAY);
		put("gbuffers_lightning", OFF, true, LIGHTNING, DRAGON_RAYS);
		put("gbuffers_beaconbeam", OFF, false, BEACON_BEAM_OPAQUE);
		put("gbuffers_beaconbeam", OFF, true, BEACON_BEAM_TRANSLUCENT);
		put("gbuffers_line", OFF, false, LINES, LINES_TRANSLUCENT, LINES_TRANSLUCENT_NO_DEPTH_WRITE, LINES_DEPTH_BIAS, SECONDARY_BLOCK_OUTLINE);
		put("gbuffers_basic", OFF, false, DEBUG_POINTS, DEBUG_FILLED_BOX, DEBUG_QUADS, DEBUG_TRIANGLE_FAN, WATER_MASK, LEASH);
		put("gbuffers_skybasic", OFF, false, SKY, STARS);
		put("gbuffers_skybasic", NON_ZERO, false, SUNRISE_SUNSET);
		put("gbuffers_skytextured", OFF, false, CELESTIAL, END_SKY);
		put("gbuffers_particles", ONE_TENTH, false, OPAQUE_PARTICLE);
		put("gbuffers_particles_translucent", ONE_TENTH, true, TRANSLUCENT_PARTICLE);
		put("gbuffers_weather", ONE_TENTH, true, WEATHER);
		put("gbuffers_clouds", ONE_TENTH, true, CLOUDS, FLAT_CLOUDS);
		put("gbuffers_damagedblock", ONE_TENTH, true, CRUMBLING);
		put("gbuffers_armor_glint", NON_ZERO, true, GLINT);
		put("gbuffers_textured", OFF, true, WORLD_BORDER);
	}

	/** Never drawn inside a world phase: GUI, post, OIT, blits. No gbuffers pipeline is built for them. */
	private static final Set<RenderPipeline> NOT_WORLD = Set.of(OIT_WATER_MASK, GUI_TEXT, GUI_TEXT_GRAYSCALE, GUI, GUI_INVERT,
			GUI_TEXT_HIGHLIGHT, GUI_TEXTURED, GUI_TEXTURED_PREMULTIPLIED_ALPHA, BLOCK_SCREEN_EFFECT, FIRE_SCREEN_EFFECT,
			GUI_OPAQUE_TEXTURED_BACKGROUND, GUI_NAUSEA_OVERLAY, VIGNETTE, CROSSHAIR, MOJANG_LOGO, ENTITY_OUTLINE_BLIT, TRACY_BLIT, PANORAMA,
			OUTLINE_CULL, OUTLINE_NO_CULL, LIGHTMAP, ANIMATE_SPRITE_BLIT, ANIMATE_SPRITE_INTERPOLATE, BLIT_DEPTH_BOUNDS, OIT_DEPTH_BOUNDS_CULL,
			BLIT_DEPTH_DURING_DEPTH_BOUNDS, BLIT_DEPTH, OIT_COMPOSITE, INTEGRATE_DEPTH);

	/** Every vanilla pipeline that may draw in a world phase (all RenderPipelines constants minus GUI/post). */
	public static List<RenderPipeline> worldPipelines() {
		List<RenderPipeline> out = new ArrayList<>();
		for (var f : net.minecraft.client.renderer.RenderPipelines.class.getFields()) {
			if (f.getType() != RenderPipeline.class) continue;
			try {
				RenderPipeline p = (RenderPipeline) f.get(null);
				if (!NOT_WORLD.contains(p) && !out.contains(p)) out.add(p);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return out;
	}

	/** null for a pipeline the table does not know (it falls back to gbuffers_textured/basic by vertex format, logged once). */
	public static Entry get(RenderPipeline p) {
		return MAP.get(p);
	}

	/** In the hand phases, entity-like programs become gbuffers_hand / gbuffers_hand_water (iris-spec §1.3 "Hand"). */
	public static String handProgram(Entry e) {
		return switch (e.program()) {
			case "gbuffers_entities", "gbuffers_entities_translucent", "gbuffers_block", "gbuffers_spidereyes", "gbuffers_terrain_solid",
					"gbuffers_terrain_cutout" -> e.translucent() ? "gbuffers_hand_water" : "gbuffers_hand";
			default -> e.program();
		};
	}

	/** Iris's gbuffers fallback chain (iris-spec §1.2), starting at {@code program} itself. */
	public static List<String> chain(String program) {
		List<String> out = new ArrayList<>();
		for (String p = program; p != null; p = FALLBACK.get(p)) out.add(p);
		return out;
	}

	private static final Map<String, String> FALLBACK = Map.ofEntries(
			Map.entry("gbuffers_line", "gbuffers_basic"), Map.entry("gbuffers_textured", "gbuffers_basic"),
			Map.entry("gbuffers_textured_lit", "gbuffers_textured"), Map.entry("gbuffers_skybasic", "gbuffers_basic"),
			Map.entry("gbuffers_skytextured", "gbuffers_textured"), Map.entry("gbuffers_clouds", "gbuffers_textured"),
			Map.entry("gbuffers_terrain", "gbuffers_textured_lit"), Map.entry("gbuffers_terrain_solid", "gbuffers_terrain"),
			Map.entry("gbuffers_terrain_cutout", "gbuffers_terrain"), Map.entry("gbuffers_damagedblock", "gbuffers_terrain"),
			Map.entry("gbuffers_block", "gbuffers_terrain"), Map.entry("gbuffers_block_translucent", "gbuffers_block"),
			Map.entry("gbuffers_beaconbeam", "gbuffers_textured"), Map.entry("gbuffers_item", "gbuffers_textured_lit"),
			Map.entry("gbuffers_entities", "gbuffers_textured_lit"), Map.entry("gbuffers_entities_translucent", "gbuffers_entities"),
			Map.entry("gbuffers_lightning", "gbuffers_entities"), Map.entry("gbuffers_particles", "gbuffers_textured_lit"),
			Map.entry("gbuffers_particles_translucent", "gbuffers_particles"), Map.entry("gbuffers_entities_glowing", "gbuffers_entities"),
			Map.entry("gbuffers_armor_glint", "gbuffers_textured"), Map.entry("gbuffers_spidereyes", "gbuffers_textured"),
			Map.entry("gbuffers_hand", "gbuffers_textured_lit"), Map.entry("gbuffers_weather", "gbuffers_textured_lit"),
			Map.entry("gbuffers_water", "gbuffers_terrain"), Map.entry("gbuffers_hand_water", "gbuffers_hand"));
}
