package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.rt.RtConfig;
import dev.emrion.shaders.mod.rt.RtScene;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "Emrion RT" page (RAYTRACING.md §5): our own options, not part of the pack's screen tree. Apply writes only
 * config/emrionshaders.properties and reconverts when the RT request changes (RT off = the exact Iris-equivalent conversion).
 */
public final class RtOptionsScreen extends Screen {
	private static final List<Integer> RAYS = List.of(1, 2, 4, 8);
	private static final List<Integer> DISTANCES = List.of(0, 4, 8, 12, 16, 24, 32);

	private final @Nullable Screen parent;
	private boolean enabled = RtConfig.enabled;
	private String shadows = RtConfig.shadows;
	private int rays = RtConfig.shadowRays, distance = RtConfig.distance;

	public RtOptionsScreen(@Nullable Screen parent) {
		super(Component.literal("Emrion RT"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100, y = 40;
		boolean gpu = RtCapability.enabled;
		addRenderableWidget(Button.builder(Component.literal("Ray tracing: " + (enabled ? "ON" : "OFF")), b -> {
			enabled = !enabled;
			rebuildWidgets();
		}).bounds(x, y, 200, 20).tooltip(Tooltip.create(Component.literal("Off: the pack runs exactly as converted for Iris."))).build()).active = gpu;
		addRenderableWidget(Button.builder(Component.literal("RT shadows: " + label(shadows)), b -> {
			shadows = RtConfig.SHADOW_MODES.get((RtConfig.SHADOW_MODES.indexOf(shadows) + 1) % RtConfig.SHADOW_MODES.size());
			rebuildWidgets();
		}).bounds(x, y + 24, 200, 20).tooltip(Tooltip.create(Component.literal(
				"Hybrid: ray-traced terrain shadows times the pack's shadow map (entity shadows, coloured glass, caustics).\n"
						+ "Full: ray-traced shadows; the map only adds coloured glass and caustics (no entity shadows yet)."))).build()).active = gpu && enabled;
		addRenderableWidget(Button.builder(Component.literal("Shadow rays per pixel: " + rays), b -> {
			rays = RAYS.get((RAYS.indexOf(rays) + 1) % RAYS.size());
			rebuildWidgets();
		}).bounds(x, y + 48, 200, 20).tooltip(Tooltip.create(Component.literal("The pack's TAA accumulates the penumbra, so 1 is usually enough."))).build())
				.active = gpu && enabled && !shadows.equals("off");
		addRenderableWidget(Button.builder(Component.literal("RT distance: " + (distance == 0 ? "render distance" : distance + " chunks")), b -> {
			distance = DISTANCES.get((Math.max(0, DISTANCES.indexOf(distance)) + 1) % DISTANCES.size());
			rebuildWidgets();
		}).bounds(x, y + 72, 200, 20).tooltip(Tooltip.create(Component.literal("Sections within this distance are meshed and traced, also behind the camera."))).build())
				.active = gpu && enabled;
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 - 151, by, 98, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).bounds(width / 2 - 49, by, 98, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			apply();
			onClose();
		}).bounds(width / 2 + 53, by, 98, 20).build());
	}

	private static String label(String mode) {
		return Character.toUpperCase(mode.charAt(0)) + mode.substring(1);
	}

	private void apply() {
		RtConfig.enabled = enabled;
		RtConfig.shadows = shadows;
		RtConfig.shadowRays = rays;
		RtConfig.distance = distance;
		PackManager.applyRt();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		g.centeredText(font, Component.literal("Shadows: " + status("shadows")), width / 2, 140, 0xFFE0E0E0);
		RtScene s = RtScene.get();
		if (s != null) g.centeredText(font, Component.literal(s.summary()), width / 2, 154, 0xFFA0A0A0);
		if (PackManager.progress >= 0) g.centeredText(font, PackManager.status, width / 2, 26, 0xFFE0E0E0);
	}

	/** "unsupported GPU", "unavailable: reason", "active (hybrid)" or "off". */
	public static String status(String effect) {
		if (!RtCapability.enabled) return "unsupported GPU (or rt.device=false)";
		Manifest.Rt rt = PackManager.rtState();
		if (rt == null) return RtConfig.enabled && PackManager.selected() != null && PackManager.progress >= 0 ? "converting..." : "off";
		if (rt.unavailable().containsKey(effect)) return "unavailable: " + rt.unavailable().get(effect);
		for (String e : rt.effects()) if (e.equals(effect)) return "active (" + (rt.effects().contains(effect + ":full") ? "full" : "hybrid") + ")";
		return "off";
	}
}
