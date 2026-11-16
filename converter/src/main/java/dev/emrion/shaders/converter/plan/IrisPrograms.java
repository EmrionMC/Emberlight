package dev.emrion.shaders.converter.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/** Static Iris tables the planner needs: gbuffers/shadow fallback chains and geometry → vertex layout. */
final class IrisPrograms {
	private IrisPrograms() {}

	/** Legacy colortex aliases, index = buffer. */
	static final List<String> LEGACY = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

	/** Iris ProgramId → fallback (null = Iris built-in shader). Order = Iris enum order (also the const-collection order). */
	static final SequencedMap<String, String> FALLBACK = new LinkedHashMap<>();

	/**
	 * Iris ProgramId → vertex layout variants of the MC geometry drawn with it.
	 * shortcut: derived by format family, re-check against MC RenderPipelines when a variant draws wrong.
	 */
	static final Map<String, List<String>> VARIANTS = new LinkedHashMap<>();

	private static void id(String name, String fallback, String... variants) {
		FALLBACK.put(name, fallback);
		VARIANTS.put(name, List.of(variants));
	}

	static {
		String tm = "terrain_mdi", tu = "terrain_ubo", e = "entity", pa = "particle", pc = "position_color", pt = "position_tex",
				ptc = "position_tex_color", po = "position", pctl = "position_color_tex_lightmap", li = "lines", bl = "block";
		id("gbuffers_basic", null, po, pc, pctl, "clouds");
		id("gbuffers_line", "gbuffers_basic", li);
		id("gbuffers_textured", "gbuffers_basic", pt, ptc, "clouds");
		id("gbuffers_textured_lit", "gbuffers_textured");
		id("gbuffers_skybasic", "gbuffers_basic", po, pc);
		id("gbuffers_skytextured", "gbuffers_textured", pt, ptc);
		id("gbuffers_clouds", "gbuffers_textured", "clouds", pc);
		id("gbuffers_terrain", "gbuffers_textured_lit");
		id("gbuffers_terrain_solid", "gbuffers_terrain", tm, tu, bl);
		id("gbuffers_terrain_cutout", "gbuffers_terrain", tm, tu, bl);
		id("gbuffers_damagedblock", "gbuffers_terrain", tu, bl);
		id("gbuffers_block", "gbuffers_terrain", e, tu, po, bl);
		id("gbuffers_block_translucent", "gbuffers_block", e, tu, pctl);
		id("gbuffers_beaconbeam", "gbuffers_textured", e, bl);
		id("gbuffers_item", "gbuffers_textured_lit");
		id("gbuffers_entities", "gbuffers_textured_lit", e);
		id("gbuffers_entities_translucent", "gbuffers_entities", e, pctl, pc);
		id("gbuffers_lightning", "gbuffers_entities", pc);
		id("gbuffers_particles", "gbuffers_textured_lit", pa);
		id("gbuffers_particles_translucent", "gbuffers_particles", pa);
		id("gbuffers_entities_glowing", "gbuffers_entities");
		id("gbuffers_armor_glint", "gbuffers_textured", e, pt);
		id("gbuffers_spidereyes", "gbuffers_textured", e);
		id("gbuffers_hand", "gbuffers_textured_lit", e, pctl);
		id("gbuffers_weather", "gbuffers_textured_lit", pa);
		id("gbuffers_water", "gbuffers_terrain", tm, tu);
		id("gbuffers_hand_water", "gbuffers_hand", e, pctl);
		id("shadow", null, li, pa, po, pctl, tu);
		id("shadow_solid", "shadow", tm, tu);
		id("shadow_cutout", "shadow", tm, tu);
		id("shadow_water", "shadow", tm, tu, e);
		id("shadow_entities", "shadow", e, pt);
		id("shadow_block", "shadow", e, po);
		id("shadow_lightning", "shadow_entities", pc);
	}

	static int legacyIndex(String name) { return LEGACY.indexOf(name); }
}
