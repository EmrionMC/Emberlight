package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.runtime.ReceiverGrid;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** "Performance" page: our own runtime options (config/emberlight.properties), applied at once, no reconversion. */
public final class PerformanceScreen extends EmberScreen {

	public PerformanceScreen(@Nullable Screen parent) {
		super(Component.literal("Performance"), parent);
	}

	@Override
	protected void build() {
		section("Shadows");
		row(tile("Shadow culling", (d, s) -> {
			ReceiverGrid.mode = ReceiverGrid.MODES.get(Math.floorMod(ReceiverGrid.MODES.indexOf(ReceiverGrid.mode) + d, ReceiverGrid.MODES.size()));
			PackManager.saveConfig("perf.shadowReceivers", ReceiverGrid.mode);
			rebuild();
		}).value(label(ReceiverGrid.mode)).lit(() -> !ReceiverGrid.mode.equals("off")).tooltip(
				"Which terrain casts into the shadow map.\n"
						+ "Off: everything the camera's flood fill reaches in the shadow area (as Iris).\n"
						+ "Balanced: also skip casters far above/below you whose shadow cannot land on anything visible.\n"
						+ "Maximum: skip every caster whose shadow cannot land on anything visible (fastest; distant hidden blocks may "
						+ "then miss from world-space reflections and coloured light, never seen in our tests)."));
		footer(primary("Done", this::onClose));
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(Component.literal("Applied at once, no reconversion.").withColor(FAINT));
	}

	static String label(String mode) {
		return switch (mode) {
			case "off" -> "Off (as Iris)";
			case "outside" -> "Balanced";
			default -> "Maximum";
		};
	}

}
