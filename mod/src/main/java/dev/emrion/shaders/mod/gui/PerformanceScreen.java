package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.runtime.ReceiverGrid;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** "Performance" page: our own runtime options (config/emrionshaders.properties), applied at once, no reconversion. */
public final class PerformanceScreen extends Screen {
	private final @Nullable Screen parent;

	public PerformanceScreen(@Nullable Screen parent) {
		super(Component.literal("Performance"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Shadow culling: " + label(ReceiverGrid.mode)), b -> {
			ReceiverGrid.mode = ReceiverGrid.MODES.get((ReceiverGrid.MODES.indexOf(ReceiverGrid.mode) + 1) % ReceiverGrid.MODES.size());
			PackManager.saveConfig("perf.shadowReceivers", ReceiverGrid.mode);
			rebuildWidgets();
		}).bounds(width / 2 - 125, 40, 250, 20).tooltip(Tooltip.create(Component.literal(
				"Which terrain casts into the shadow map.\n"
						+ "Off: everything the camera's flood fill reaches in the shadow area (as Iris).\n"
						+ "Outside voxel volume: also skip casters far above/below you whose shadow cannot land on anything visible.\n"
						+ "Full: skip every caster whose shadow cannot land on anything visible (fastest; distant hidden blocks may "
						+ "then miss from world-space reflections and coloured light, never seen in our tests)."))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 49, height - 26, 98, 20).build());
	}

	private static String label(String mode) {
		return switch (mode) {
			case "off" -> "Off";
			case "outside" -> "Outside voxel volume";
			default -> "Full";
		};
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
	}
}
