package dev.emrion.shaders.mod.pack;

import com.mojang.blaze3d.platform.InputConstants;
import dev.emrion.shaders.mod.gui.EmberScreen;
import dev.emrion.shaders.mod.gui.LoadingHud;
import dev.emrion.shaders.mod.gui.ShaderOptionsScreen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Pack selection screen with Iris's shortcuts; on OpenGL it only explains that Vulkan is needed. */
public final class PackScreen extends EmberScreen {
	private @Nullable Tile options;
	private final boolean vulkan = dev.emrion.shaders.mod.EmrionShadersClient.active();
	/** F1: only the world is drawn (Iris). */
	private boolean hidden;
	private volatile @Nullable String notice;
	/** Set while dropped packs are copied in the background; closing the screen cancels the copy. */
	private volatile java.util.concurrent.atomic.@Nullable AtomicBoolean installing;
	private boolean loading = LoadingHud.busy() != null;

	public PackScreen(@Nullable Screen parent) {
		super(Component.literal("Shader packs"), parent);
	}

	@Override
	protected void build() {
		if (!vulkan) {
			section("Vulkan needed");
			note(() -> Component.literal("Emberlight needs the Vulkan graphics backend. The game is running on OpenGL, so shader packs are off."), TEXT);
			space(4);
			note(() -> Component.literal("Video Settings > Graphics API: \"Prefer Vulkan\", then restart the game."), 0xFFFFE060);
			footer(button("Open Video Settings...", () -> minecraft.gui.setScreen(
					new net.minecraft.client.gui.screens.options.VideoSettingsScreen(this, minecraft, minecraft.options))), primary("Done", this::onClose));
			return;
		}
		section("Shader pack");
		List<Tile> packs = new java.util.ArrayList<>();
		packs.add(packTile(null));
		for (String pack : PackManager.packs()) packs.add(packTile(pack));
		grid(packs, 1);
		section("Emberlight");
		grid(pageTiles(), 2);
		options = button("Shader Options...", this::openOptions);
		options.active = PackManager.optionSession() != null;
		footer(options, primary("Done", this::onClose));
	}

	private Tile packTile(@Nullable String pack) {
		boolean selected = java.util.Objects.equals(pack, PackManager.selected());
		return tile(pack == null ? "Off" : pack, (d, s) -> pick(pack))
				.value(() -> !selected ? null : Component.literal(pack != null && LoadingHud.busy() != null ? "Loading…"
						: pack != null && !PackManager.enabled() ? "Turned off (K)" : "Selected"))
				.hint(selected && pack != null ? () -> { String busy = LoadingHud.busy(); return busy == null ? null : Component.literal(busy); } : () -> null).lit(() -> selected);
	}

	@Override
	protected @Nullable Component headerStatus() {
		return !vulkan ? Component.literal("OpenGL: shaders off").withColor(BAD) : PackManager.debugMode() ? Component.literal("Debug mode") : null;
	}

	@Override
	protected void footerLines(List<Component> out) {
		if (!vulkan) return;
		if (notice != null) out.add(Component.literal(notice).withColor(0xFFFFE060));
		List<String> errs;
		int count;
		synchronized (PackManager.errors) {
			count = PackManager.errors.size();
			errs = List.copyOf(PackManager.errors.subList(0, Math.min(2, count)));
		}
		for (String err : errs) out.add(Component.literal(font.plainSubstrByWidth(err, width - 2 * PAD)).withColor(BAD));
		if (count > 2) out.add(Component.literal("... " + (count - 2) + " more errors (see log / report.json)").withColor(BAD));
		out.add(Component.literal("Tab: options   F1: hide   Ctrl+D: debug mode" + (PackManager.debugMode() ? " (on)" : "") + "   Drop packs here to install").withColor(FAINT));
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

	/** Dropped zips and pack folders (with a shaders/ folder) are copied into shaderpacks/ (Iris), in the background. */
	@Override
	public void onFilesDrop(List<Path> files) {
		if (!vulkan) return;
		if (installing != null) {
			notice = "Still installing, drop again when done";
			return;
		}
		List<Path> todo = new java.util.ArrayList<>();
		for (Path f : files) {
			String name = f.getFileName().toString();
			if (Files.isDirectory(f) ? !Files.isDirectory(f.resolve("shaders")) : !name.endsWith(".zip")) notice = "Not a shader pack: " + name;
			else if (Files.exists(PackManager.SHADERPACKS.resolve(name))) notice = name + " is already installed";
			else todo.add(f);
		}
		if (todo.isEmpty()) return;
		var cancel = new java.util.concurrent.atomic.AtomicBoolean();
		installing = cancel;
		Thread.ofPlatform().daemon().name("Emberlight pack install").start(() -> {
			for (Path f : todo) {
				String name = f.getFileName().toString();
				try {
					PackInstaller.install(f, PackManager.SHADERPACKS, cancel::get, n -> notice = "Installing " + name + ": " + n + " files (closing this screen cancels)");
					notice = "Installed " + name;
				} catch (java.util.concurrent.CancellationException ex) {
					break;
				} catch (IOException | RuntimeException ex) {
					notice = "Could not install " + name + ": " + ex.getMessage();
					dev.emrion.shaders.mod.EmrionShadersClient.LOG.warn("Could not install dropped pack {}", f, ex);
				}
			}
			minecraft.execute(() -> {
				installing = null;
				rebuildWidgets();
			});
		});
		notice = "Installing " + todo.getFirst().getFileName() + " (closing this screen cancels)";
	}

	@Override
	public void onClose() {
		var cancel = installing;
		if (cancel != null) cancel.set(true);
		super.onClose();
	}

	private void pick(@Nullable String pack) {
		PackManager.select(pack, true);
		rebuild();
	}

	@Override
	public void tick() {
		if ((LoadingHud.busy() != null) != loading) { // the loading line under the pack appears or goes
			loading = !loading;
			rebuild();
		}
		if (options != null && !options.active) options.active = PackManager.optionSession() != null; // on once the pack finished loading
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (!hidden) super.extractBackground(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (!hidden) super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

}
