package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.mod.gui.ShaderOptionsScreen;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Minimal pack selection (M2): one button per pack, "Off", status line, progress bar and the first errors. M5 replaces it.
 * On OpenGL (mod inert) it only explains that Vulkan is needed and opens Video Settings, where the Graphics API option is.
 */
public final class PackScreen extends Screen {
	private static final int ROW = 22;
	private final @Nullable Screen parent;
	private @Nullable Button options;
	private final boolean vulkan = dev.emrion.shaders.mod.EmrionShadersClient.active();

	public PackScreen(@Nullable Screen parent) {
		super(Component.literal("Emrion Shaders"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = width / 2 - 100, y = 32;
		if (!vulkan) {
			addRenderableWidget(Button.builder(Component.literal("Open Video Settings..."), b -> minecraft.gui.setScreen(
					new net.minecraft.client.gui.screens.options.VideoSettingsScreen(this, minecraft, minecraft.options))).bounds(x - 102, height - 28, 200, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 102, height - 28, 200, 20).build());
			return;
		}
		addRenderableWidget(Button.builder(label("Off", PackManager.selected() == null), b -> pick(null)).bounds(x, y, 200, 20).build());
		for (String pack : PackManager.packs()) {
			y += ROW;
			if (y > height - 90) break;
			addRenderableWidget(Button.builder(label(pack, pack.equals(PackManager.selected())), b -> pick(pack)).bounds(x, y, 200, 20).build());
		}
		options = addRenderableWidget(Button.builder(Component.literal("Shader Options..."), b -> {
			var session = PackManager.optionSession();
			if (session != null) minecraft.gui.setScreen(new ShaderOptionsScreen(this, session));
		}).bounds(x - 102, height - 28, 200, 20).build());
		options.active = PackManager.optionSession() != null;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 102, height - 28, 200, 20).build());
	}

	private static Component label(String name, boolean selected) {
		return Component.literal(selected ? "> " + name + " <" : name);
	}

	private void pick(@Nullable String pack) {
		PackManager.select(pack, true);
		rebuildWidgets();
	}

	@Override
	public void tick() {
		if (options != null && !options.active) options.active = PackManager.optionSession() != null; // on once the pack finished loading
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
		if (!vulkan) {
			String[] lines = {"Emrion Shaders needs the Vulkan graphics backend.", "The game is running on OpenGL, so shader packs are off.", "",
					"Video Settings > Graphics API: \"Prefer Vulkan\",", "then restart the game."};
			for (int i = 0; i < lines.length; i++) g.centeredText(font, lines[i], width / 2, height / 2 - 40 + i * 12, i < 2 ? 0xFFFFFFFF : 0xFFFFE060);
			return;
		}
		int y = height - 80;
		g.centeredText(font, PackManager.status, width / 2, y, 0xFFE0E0E0);
		float p = PackManager.progress;
		if (p >= 0) {
			int x0 = width / 2 - 100;
			g.fill(x0, y + 12, x0 + 200, y + 18, 0xFF404040);
			g.fill(x0, y + 12, x0 + (int) (200 * Math.min(1, p)), y + 18, 0xFF40C040);
		}
		List<String> errs;
		synchronized (PackManager.errors) {
			errs = List.copyOf(PackManager.errors);
		}
		for (int i = 0; i < Math.min(2, errs.size()); i++) g.text(font, trim(errs.get(i)), 8, y + 24 + i * 10, 0xFFFF6060);
		if (errs.size() > 2) g.text(font, "... " + (errs.size() - 2) + " more errors (see log / report.json)", 8, y + 44, 0xFFFF6060);
	}

	private String trim(String s) {
		return font.plainSubstrByWidth(s, width - 16);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
