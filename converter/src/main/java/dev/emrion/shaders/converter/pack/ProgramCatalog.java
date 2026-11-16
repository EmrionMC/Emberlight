package dev.emrion.shaders.converter.pack;

import dev.emrion.shaders.converter.glsl.ProgramGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Iris program names per group, in execution order (Iris ProgramSet / ProgramId /
 * ShaderPackSourceNames). Distant Horizons programs (dh_*) are not listed: we do not support DH.
 */
public final class ProgramCatalog {
	private ProgramCatalog() {}

	/** One pass slot: graphics program {@code name} (may be absent) and whether .csh computes are probed for it. */
	public record Slot(String name, ProgramGroup group, boolean graphics, boolean computes) {}

	/** Gbuffers and shadow programs in Iris ProgramId order (also the order Iris reads const directives in). */
	public static final List<String> GBUFFERS = List.of("gbuffers_basic", "gbuffers_line", "gbuffers_textured",
			"gbuffers_textured_lit", "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain",
			"gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_damagedblock", "gbuffers_block",
			"gbuffers_block_translucent", "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
			"gbuffers_entities_translucent", "gbuffers_lightning", "gbuffers_particles", "gbuffers_particles_translucent",
			"gbuffers_entities_glowing", "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand", "gbuffers_weather",
			"gbuffers_water", "gbuffers_hand_water");

	public static final List<String> SHADOW = List.of("shadow", "shadow_solid", "shadow_cutout", "shadow_water",
			"shadow_entities", "shadow_lightning", "shadow_block");

	public static List<Slot> slots() {
		List<Slot> s = new ArrayList<>();
		for (int i = 0; i < 100; i++) s.add(new Slot(numbered("setup", i), ProgramGroup.SETUP, false, false));
		array(s, "begin", ProgramGroup.BEGIN);
		for (String n : SHADOW) s.add(new Slot(n, ProgramGroup.SHADOW, true, n.equals("shadow")));
		array(s, "shadowcomp", ProgramGroup.SHADOWCOMP);
		array(s, "prepare", ProgramGroup.PREPARE);
		for (String n : GBUFFERS) s.add(new Slot(n, ProgramGroup.GBUFFERS, true, false));
		array(s, "deferred", ProgramGroup.DEFERRED);
		array(s, "composite", ProgramGroup.COMPOSITE);
		s.add(new Slot("final", ProgramGroup.FINAL, true, true));
		return s;
	}

	private static void array(List<Slot> s, String base, ProgramGroup g) {
		for (int i = 0; i < 100; i++) s.add(new Slot(numbered(base, i), g, true, true));
	}

	private static String numbered(String base, int i) {
		return i == 0 ? base : base + i;
	}

	/** Every file name Iris treats as a program start (for the include graph and option discovery). */
	public static List<String> startFileNames() {
		List<String> names = new ArrayList<>();
		for (Slot slot : slots()) {
			if (slot.group() == ProgramGroup.SETUP) {
				names.add(slot.name() + ".csh");
				continue;
			}
			for (String ext : new String[]{"vsh", "tcs", "tes", "gsh", "fsh"}) names.add(slot.name() + "." + ext);
			if (slot.computes()) {
				names.add(slot.name() + ".csh");
				for (char c = 'a'; c <= 'z'; c++) names.add(slot.name() + "_" + c + ".csh");
			}
		}
		return names;
	}

	/**
	 * Iris program order for pack-wide const directives (ProgramSet.locateDirectives): shadowcomp, begin, prepare,
	 * shadow + gbuffers + final, deferred, composite. A later program overrides an earlier one.
	 */
	public static int directiveRank(ProgramGroup g) {
		return switch (g) {
			case SHADOWCOMP -> 0;
			case BEGIN -> 1;
			case PREPARE -> 2;
			case SHADOW, GBUFFERS, FINAL -> 3;
			case DEFERRED -> 4;
			case COMPOSITE -> 5;
			case SETUP -> -1;
		};
	}
}
