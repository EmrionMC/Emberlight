package dev.emrion.shaders.mod.pack;

import com.mojang.blaze3d.platform.InputConstants;
import dev.emrion.shaders.mod.gui.ShaderOptionsScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Minimal pack selection (M2): one button per pack, "Off", status line, progress bar and the first errors. M5 replaces it.
 * On OpenGL (mod inert) it only explains that Vulkan is needed and opens Video Settings, where the Graphics API option is.
 * Iris's shortcuts: Tab opens the options, F1 hides the screen, Ctrl+D toggles debug mode, dropped packs are installed.
 */
public final class PackScreen extends Screen {
	private static final int ROW = 22;
	private final @Nullable Screen parent;
	private @Nullable Button options;
	private final boolean vulkan = dev.emrion.shaders.mod.EmrionShadersClient.active();
	/** F1: only the world is drawn (Iris). */
	private boolean hidden;
	private @Nullable String notice;

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
		options = addRenderableWidget(Button.builder(Component.literal("Shader Options..."), b -> openOptions()).bounds(x - 102, height - 28, 200, 20).build());
		options.active = PackManager.optionSession() != null;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 102, height - 28, 200, 20).build());
	}

	private void openOptions() {
		var session = PackManager.optionSession();
		if (session != null) minecraft.gui.setScreen(new ShaderOptionsScreen(this, session));
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (vulkan && e.input() == InputConstants.KEY_F1) {
			hidden = !hidden;
			return true;
		}
		if (vulkan && e.input() == InputConstants.KEY_TAB && !e.hasShiftDown() && !e.hasControlDown() && !e.hasAltDown()) { // Shift+Tab still moves the focus
			openOptions();
			return true;
		}
		if (vulkan && e.input() == InputConstants.KEY_D && e.hasControlDown()) {
			boolean on = !PackManager.debugMode();
			minecraft.gui.setScreen(new ConfirmScreen(yes -> {
				if (yes) PackManager.setDebugMode(on);
				minecraft.gui.setScreen(this);
			}, Component.literal((on ? "Enable" : "Disable") + " debug mode?"), Component.literal(
					"Debug mode shows every shader error in chat and the profiler overlay, and allows the wireframe key (singleplayer).")));
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (hidden) return true; // no clicks on invisible buttons
		return super.mouseClicked(e, doubleClick);
	}

	/** Dropped zips and pack folders (with a shaders/ folder) are copied into shaderpacks/ (Iris). */
	@Override
	public void onFilesDrop(List<Path> files) {
		if (!vulkan) return;
		for (Path f : files) {
			String name = f.getFileName().toString();
			Path dst = PackManager.SHADERPACKS.resolve(name);
			if (Files.isDirectory(f) ? !Files.isDirectory(f.resolve("shaders")) : !name.endsWith(".zip")) notice = "Not a shader pack: " + name;
			else if (Files.exists(dst)) notice = name + " is already installed";
			else try (Stream<Path> tree = Files.walk(f)) {
				Files.createDirectories(PackManager.SHADERPACKS);
				for (Path p : (Iterable<Path>) tree::iterator) Files.copy(p, dst.resolve(f.relativize(p).toString()));
				notice = "Installed " + name;
			} catch (IOException ex) {
				notice = "Could not install " + name + ": " + ex.getMessage();
				dev.emrion.shaders.mod.EmrionShadersClient.LOG.warn("Could not install dropped pack {}", f, ex);
			}
		}
		rebuildWidgets();
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
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (!hidden) super.extractBackground(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (hidden) return;
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
		if (notice != null) g.centeredText(font, notice, width / 2, y - 12, 0xFFFFE060);
		g.text(font, "Tab: options  F1: hide  Ctrl+D: debug mode" + (PackManager.debugMode() ? " (on)" : "") + "  Drop packs here to install", 4, 2, 0xFF909090);
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
