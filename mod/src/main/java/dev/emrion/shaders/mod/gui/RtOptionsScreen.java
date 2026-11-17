package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.rt.RtConfig;
import dev.emrion.shaders.mod.rt.RtOmm;
import dev.emrion.shaders.mod.rt.RtPreset;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "Emberlight RT" page: our own options, not part of the pack's screen tree. Apply writes only
 * config/emberlight.properties and reconverts when the RT request changes (RT off = the exact Iris-equivalent conversion).
 */
public final class RtOptionsScreen extends EmberScreen {
	private static final List<Integer> RAYS = List.of(1, 2, 4, 8);
	private static final List<Integer> DISTANCES = List.of(0, 4, 8, 12, 16, 24, 32);

	private boolean enabled = RtConfig.enabled;
	private String shadows = RtConfig.shadows;
	private boolean reflections = RtConfig.reflections;
	private int rays = RtConfig.shadowRays, distance = RtConfig.distance;
	private boolean refraction = RtConfig.refraction, caustics = RtConfig.caustics;
	private boolean ao = RtConfig.ao;
	private boolean shadowEntities = RtConfig.shadowEntities;
	private static final List<Integer> BLOCK_LIGHT_RAYS = List.of(0, 2, 4, 8);
	private int blockLightRays = RtConfig.blockLightRays;
	private String gi = RtConfig.gi;
	private boolean giCache = RtConfig.giCache;
	private String style = RtConfig.style;
	private boolean omm = RtConfig.omm;

	public RtOptionsScreen(@Nullable Screen parent) {
		super(Component.literal("Ray tracing"), parent);
	}

	/** The loaded pack's RT support; null = no pack loaded (all options shown: they are global). */
	private final PackManager.@Nullable RtSupport support = PackManager.rtSupport();

	@Override
	protected void build() {
		if (support != null && (support.nativePack() || support.effects().isEmpty())) {
			section("Ray tracing");
			note(() -> Component.literal(support.nativePack() ? "Built into the pack" : "Not available for this pack"), support.nativePack() ? GOLD : BAD);
			note(() -> Component.literal(support.nativePack()
					? "This pack does its own ray tracing; Emberlight's RT options do not apply to it. Its ray tracing settings are in its Shader Options."
					: "Emberlight adds ray tracing to Iris packs through RT patch tables. This pack has none, so it runs as it does with Iris. "
							+ "Packs with a table: Complementary (bundled), or packs that ship shaders/emberlight/rt.json."), DIM);
			footer(primary("Done", this::onClose));
			return;
		}
		boolean gpu = RtCapability.enabled, on = gpu && enabled, rtx = style.equals("rtx");
		section("Ray tracing");
		row(tile("Ray tracing", (d, s) -> set(() -> enabled = !enabled)).toggle(() -> enabled).active(gpu)
						.hint(!gpu ? Component.literal("Not supported by this GPU").withColor(BAD) : null)
						.labelColor(changed(enabled != RtConfig.enabled)).tooltip("Off: the pack runs exactly as converted for Iris."),
				tile("Style", (d, s) -> set(() -> style = cycle(RtConfig.STYLES, style, d))).value(style.equals("rtx") ? "RTX" : "Faithful").active(on)
						.labelColor(changed(!style.equals(RtConfig.style))).tooltip(
						"RTX: ray tracing lights the scene. Coloured sunlight and block light through stained glass, light blocks as real coloured\n"
								+ "lights with soft shadows and bounce light, multi-bounce GI, glossier materials (with RT block light and GI on).\n"
								+ "Faithful: ray tracing only makes the pack's own effects more accurate (subtle)."));
		section("Preset");
		RtPreset now = RtPreset.detect(pending());
		List<Tile> presets = new java.util.ArrayList<>();
		for (RtPreset p : RtPreset.values())
			presets.add(tile(p.label, (d, s) -> set(() -> use(p))).active(on).lit(() -> now == p).hint(Component.literal(p.hint).withColor(DIM))
					.tooltip(p.description));
		presets.add(tile("Custom", (d, s) -> {}).active(false).lit(() -> now == null).hint(Component.literal("Own choice").withColor(DIM))
				.tooltip("Shown when the options below match no preset."));
		grid(presets, presets.size());
		section("Quality");
		row(tile("Trace distance", (d, s) -> set(() -> distance = cycle(DISTANCES, distance, d))).value(distance == 0 ? "Render distance" : distance + " chunks")
						.active(on).labelColor(changed(distance != RtConfig.distance))
						.tooltip("Sections within this distance are meshed and traced, also behind the camera."),
				tile("Shadow rays per pixel", (d, s) -> set(() -> rays = cycle(RAYS, rays, d))).value(rays + (rays == 1 ? " ray" : " rays")).active(on && has("shadows") && !shadows.equals("off"))
						.hint(rtx && on && has("shadows") && rays < 4 && !shadows.equals("off") ? Component.literal("At least 4 with RTX").withColor(DIM) : null)
						.labelColor(changed(rays != RtConfig.shadowRays)).tooltip("The pack's TAA accumulates the penumbra, so 1 is usually enough."));
		row(tile("Opacity micromaps", (d, s) -> set(() -> omm = !omm)).toggle(() -> omm).active(on && RtOmm.supported)
				.hint(on && !RtOmm.supported ? Component.literal("Not supported by this GPU").withColor(DIM) : null)
				.labelColor(changed(omm != RtConfig.omm)).tooltip(
				"Leaves, grass, panes and other cut-out blocks: the GPU learns which parts of their textures are solid or empty, so rays "
						+ "skip most texture lookups. Same image, faster tracing; changing it rebuilds the terrain once."));
		section("Shadows");
		// RTX has no hybrid: its coloured sun shadows are always fully traced, so it offers only that and Off
		row(tile("Shadows", (d, s) -> set(() -> shadows = rtx ? cycle(List.of("full", "off"), shadows.equals("hybrid") ? "full" : shadows, d)
						: cycle(RtConfig.SHADOW_MODES, shadows, d))).value(shadowLabel(rtx && shadows.equals("hybrid") ? "full" : shadows)).active(on && has("shadows"))
						.hint(() -> or(missing("shadows"), or(effect(status("shadows"), !shadows.equals("off")),
								rtx && on && shadows.equals("hybrid") ? Component.literal("Always fully traced with RTX").withColor(DIM) : null))).lit(() -> !shadows.equals("off")).labelColor(changed(!shadows.equals(RtConfig.shadows))).tooltip(
						"Hybrid: ray-traced terrain shadows times the pack's shadow map (entity shadows, coloured glass, caustics).\n"
								+ "Fully traced: ray-traced shadows; the map only adds coloured glass and caustics (entity shadows with the entity switch below).\n"
								+ "RTX always traces fully."),
				tile("Entities cast shadows", (d, s) -> set(() -> shadowEntities = !shadowEntities)).toggle(() -> shadowEntities).active(on && has("shadows") && !shadows.equals("off"))
						.hint(() -> or(missing("shadows"), or(effect(entityStatus(), shadowEntities && !shadows.equals("off")), !rtx && on && !shadowEntities && shadows.equals("hybrid")
								? Component.literal("Shadow map still casts them").withColor(DIM) : null))).labelColor(changed(shadowEntities != RtConfig.shadowEntities)).tooltip(
						"Entities, block entities and the player are traced too (rebuilt every frame). Needed for entity shadows in Full mode and RTX; "
								+ "in Faithful Hybrid the shadow map already has them."));
		section("Lighting");
		row(tile("Block light", (d, s) -> set(() -> blockLightRays = cycle(BLOCK_LIGHT_RAYS, blockLightRays, d)))
						.value(blockLightRays == 0 ? "Off" : rtx ? blockLightQuality(blockLightRays) : blockLightRays + " rays").active(on && has("blocklight"))
						.hint(() -> or(missing("blocklight"), effect(status("blocklight"), blockLightRays > 0))).lit(() -> blockLightRays > 0)
						.labelColor(changed(blockLightRays != RtConfig.blockLightRays)).tooltip(rtx
						? "Every light block lights the scene with its own colour and soft ray-traced shadows (ReSTIR: each pixel picks the lights "
								+ "that matter from all sources in reach and shares good picks with its neighbours over space and time). "
								+ "Ores, lit redstone and other blocks the pack's coloured light makes glow count as lights too.\n"
								+ "Low: 2 neighbours. Medium: 4. High: 6 neighbours, less noise in dense light."
						: "Torches, lanterns, lava and other light blocks cast ray-traced shadows: the pack's block light keeps its colours and falloff "
								+ "and is dimmed where the sources are hidden. Rays per pixel; with no more sources nearby than rays the result is exact."),
				tile("Global illumination", (d, s) -> set(() -> gi = cycle(RtConfig.GI_MODES, gi, d))).value(label(gi)).active(on && has("gi"))
						.hint(() -> or(missing("gi"), effect(status("gi"), !gi.equals("off"))))
						.lit(() -> !gi.equals("off")).labelColor(changed(!gi.equals(RtConfig.gi))).tooltip(
						"Ray-traced bounce light (sun and sky) replaces the pack's flat ambient light.\n"
								+ "Low: half resolution, 1 ray per pixel. Medium: half resolution, 2 rays. High: full resolution, 2 rays."));
		row(tile("GI radiance cache", (d, s) -> set(() -> giCache = !giCache)).toggle(() -> giCache).active(on && rtx && has("gi") && !gi.equals("off"))
						.hint(() -> or(missing("gi"), effect(status("gi:cache"), giCache && rtx && !gi.equals("off")))).labelColor(changed(giCache != RtConfig.giCache)).tooltip(
						"RTX GI: a world-space cache of the light arriving at surfaces, filled by a few extra paths each frame. Bounce rays "
								+ "end at the cached light instead of tracing their own shadow rays: light bounces many times, also off screen."),
				tile("Ambient occlusion", (d, s) -> set(() -> ao = !ao)).toggle(() -> ao).active(on && has("ao")).hint(() -> or(missing("ao"), effect(status("ao"), ao)))
				.labelColor(changed(ao != RtConfig.ao)).tooltip(
				"Ray-traced AO (short rays, accumulated over frames) instead of the pack's screen-space AO: contact shadows in corners and under foliage, also at the screen edges."));
		section("Reflections and water");
		row(tile("Reflections", (d, s) -> set(() -> reflections = !reflections)).toggle(() -> reflections).active(on && has("reflections")).hint(() -> or(missing("reflections"), effect(status("reflections"), reflections)))
						.labelColor(changed(reflections != RtConfig.reflections)).tooltip(
						"The pack's world-space reflections find their hit with a ray against the real geometry (slabs, stairs, plants,\n"
								+ "fences, beyond the voxel volume) and shade it with the pack's own code. Frees the pack's reflection voxel data."),
				tile("Refraction", (d, s) -> set(() -> refraction = !refraction)).toggle(() -> refraction).active(on && has("refraction")).hint(() -> or(missing("refraction"), effect(status("refraction"), refraction)))
						.labelColor(changed(refraction != RtConfig.refraction)).tooltip(
						"Traces the view ray refracted by water and glass (index of refraction at the flat surface, plus the pack's subtle wave wobble) instead of the pack's "
								+ "screen-space offset, so things off screen or hidden behind the surface show too."));
		row(tile("Caustics", (d, s) -> set(() -> caustics = !caustics)).toggle(() -> caustics).active(on && has("caustics")).hint(() -> or(missing("caustics"), effect(status("caustics"), caustics)))
				.labelColor(changed(caustics != RtConfig.caustics)).tooltip(
				"Water caustics from sunlight refracted through the animated water surface (focus from the real water depth) instead "
						+ "of the pack's caustic texture."));
		footer(button("Cancel", this::onClose), button("Apply", this::apply), primary("Done", () -> {
			apply();
			onClose();
		}));
	}

	/** Status line only when it adds something: a wanted effect that is unavailable or not applied yet (on and running needs none). */
	private @Nullable Component effect(String status, boolean wanted) {
		if (!RtCapability.enabled || !enabled || !wanted || status.startsWith("active")) return null;
		if (status.startsWith("unavailable")) return Component.literal("Unavailable" + status.substring("unavailable".length())).withColor(BAD);
		if (status.startsWith("converting")) return Component.literal("Applying…");
		return Component.literal(modified() ? "Not applied yet" : "Not active").withColor(CHANGED);
	}

	private boolean has(String effect) {
		return support == null || support.effects().contains(effect);
	}

	/** One-line reason for an option the pack's RT table has no patches for (shown greyed instead of a switch that does nothing). */
	private @Nullable Component missing(String effect) {
		return has(effect) ? null : Component.literal("Not supported by this pack").withColor(DIM);
	}

	private static @Nullable Component or(@Nullable Component a, @Nullable Component b) {
		return a != null ? a : b;
	}

	/** The status lines change with conversions and Apply: rebuild when one appears or goes (it changes the tile height). */
	private String shownStatus = "";

	@Override
	public void tick() {
		String now = String.join("|", status("shadows"), entityStatus(), status("blocklight"), status("gi"), status("gi:cache"), status("ao"), status("reflections"),
				status("refraction"), status("caustics"), String.valueOf(modified()));
		if (!now.equals(shownStatus)) {
			boolean first = shownStatus.isEmpty();
			shownStatus = now;
			if (!first) rebuild();
		}
	}

	/** RTX block light (ReSTIR): the rays setting picks the spatial reuse (rtx/bl_restir_b.csh). */
	private static String blockLightQuality(int rays) {
		return rays >= 8 ? "High" : rays >= 4 ? "Medium" : "Low";
	}

	private static String shadowLabel(String mode) {
		return switch (mode) {
			case "hybrid" -> "Hybrid";
			case "full" -> "Fully traced";
			default -> "Off";
		};
	}

	/** The page's values in the form RtConfig.store writes (for preset detection). */
	private java.util.Properties pending() {
		var p = new java.util.Properties();
		p.setProperty("rt.style", style);
		p.setProperty("rt.shadows", shadows);
		p.setProperty("rt.shadowRays", Integer.toString(rays));
		p.setProperty("rt.shadowEntities", Boolean.toString(shadowEntities));
		p.setProperty("rt.gi", gi);
		p.setProperty("rt.blockLightRays", Integer.toString(blockLightRays));
		p.setProperty("rt.ao", Boolean.toString(ao));
		p.setProperty("rt.reflections", Boolean.toString(reflections));
		p.setProperty("rt.refraction", Boolean.toString(refraction));
		p.setProperty("rt.caustics", Boolean.toString(caustics));
		p.setProperty("rt.distance", Integer.toString(distance));
		return p;
	}

	private void use(RtPreset preset) {
		var v = preset.values;
		style = v.get("style");
		shadows = v.get("shadows");
		rays = Integer.parseInt(v.get("shadowRays"));
		shadowEntities = Boolean.parseBoolean(v.get("shadowEntities"));
		gi = v.get("gi");
		blockLightRays = Integer.parseInt(v.get("blockLightRays"));
		ao = Boolean.parseBoolean(v.get("ao"));
		reflections = Boolean.parseBoolean(v.get("reflections"));
		refraction = Boolean.parseBoolean(v.get("refraction"));
		caustics = Boolean.parseBoolean(v.get("caustics"));
		distance = Integer.parseInt(v.get("distance"));
	}

	private void set(Runnable change) {
		change.run();
		rebuild();
	}

	/** Next (or with a right click previous) entry; unknown values start at the first. */
	private static <T> T cycle(List<T> values, T current, int dir) {
		return values.get(Math.floorMod(Math.max(dir < 0 ? 0 : -1, values.indexOf(current)) + dir, values.size()));
	}

	private static Supplier<Integer> changed(boolean c) {
		return () -> c ? CHANGED : TEXT;
	}

	private boolean modified() {
		return enabled != RtConfig.enabled || !shadows.equals(RtConfig.shadows) || reflections != RtConfig.reflections || rays != RtConfig.shadowRays
				|| distance != RtConfig.distance || refraction != RtConfig.refraction || caustics != RtConfig.caustics || ao != RtConfig.ao
				|| shadowEntities != RtConfig.shadowEntities || blockLightRays != RtConfig.blockLightRays || !gi.equals(RtConfig.gi) || !style.equals(RtConfig.style)
				|| omm != RtConfig.omm || giCache != RtConfig.giCache;
	}

	@Override
	protected void footerLines(List<Component> out) {
		if (modified()) out.add(Component.literal("Unapplied changes: Apply or Done reconverts the pack").withColor(CHANGED));
	}

	@Override
	protected @Nullable Component headerStatus() {
		if (support != null && support.nativePack()) return Component.literal("Built into the pack");
		if (support != null && support.effects().isEmpty()) return Component.literal("Not available for this pack").withColor(BAD);
		return !RtCapability.enabled ? Component.literal("Not supported by this GPU").withColor(BAD) : Component.literal(RtConfig.enabled ? "On" : "Off");
	}

	private static String label(String mode) {
		return Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
	}

	private void apply() {
		RtConfig.enabled = enabled;
		RtConfig.shadows = shadows;
		RtConfig.reflections = reflections;
		RtConfig.shadowRays = rays;
		RtConfig.distance = distance;
		RtConfig.refraction = refraction;
		RtConfig.caustics = caustics;
		RtConfig.ao = ao;
		RtConfig.shadowEntities = shadowEntities;
		RtConfig.blockLightRays = blockLightRays;
		RtConfig.gi = gi;
		RtConfig.style = style;
		RtConfig.omm = omm;
		RtConfig.giCache = giCache;
		PackManager.applyRt();
		rebuild();
	}

	/** Entities in the TLAS: "active (shadows)", the shadow effect's state when it is not active, or "off". */
	public static String entityStatus() {
		Manifest.Rt rt = PackManager.rtState();
		if (!RtCapability.enabled || rt == null) return status("shadows");
		List<String> users = rt.effects().stream().filter(e -> e.endsWith(":entities")).map(e -> e.substring(0, e.indexOf(':'))).toList();
		if (!users.isEmpty()) return "active (" + String.join(", ", users) + ")";
		return rt.unavailable().isEmpty() ? "off" : "unavailable: " + rt.unavailable().values().iterator().next();
	}

	/** "unsupported GPU", "unavailable: reason", "active (hybrid)" or "off". */
	public static String status(String effect) {
		if (!RtCapability.enabled) return "unsupported GPU (or rt.device=false)";
		Manifest.Rt rt = PackManager.rtState();
		if (rt == null) return RtConfig.enabled && PackManager.selected() != null && PackManager.progress >= 0 ? "converting..." : "off";
		if (rt.unavailable().containsKey(effect)) return "unavailable: " + rt.unavailable().get(effect);
		for (String e : rt.effects())
			if (e.equals(effect)) return !effect.equals("shadows") ? "active" : "active (" + (rt.effects().contains(effect + ":full") ? "full" : "hybrid") + ")";
		return "off";
	}
}
