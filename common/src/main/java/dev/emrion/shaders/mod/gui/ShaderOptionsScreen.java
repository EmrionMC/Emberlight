package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pack.PackManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * One screen of the pack's option menu. Like Iris: left click = next, right click = previous, shift-click = default,
 * pending changes in yellow; Apply writes the Iris settings file and reloads.
 */
public final class ShaderOptionsScreen extends EmberScreen {
	private final @Nullable Screen exit;
	private final OptionSession session;
	private final String id;

	/** Root screen; {@code exit} is shown again on Done/Cancel. */
	public ShaderOptionsScreen(Screen exit, OptionSession session) {
		this(exit, exit, session, "");
	}

	/** Opens screen {@code id} ("" = main) directly over the game (autotest). */
	public static ShaderOptionsScreen open(OptionSession session, String id) {
		return new ShaderOptionsScreen(null, null, session, id);
	}

	private ShaderOptionsScreen(@Nullable Screen parent, @Nullable Screen exit, OptionSession session, String id) {
		super(Component.literal(id.isEmpty() ? "Shader Options" : session.lang("screen." + id, id)), parent);
		this.exit = exit;
		this.session = session;
		this.id = id;
	}

	@Override
	protected void build() {
		boolean root = id.isEmpty();
		List<String> elements = session.screen(id);
		List<@Nullable AbstractWidget> cells = new ArrayList<>();
		for (String e : elements) cells.add(cell(e));
		section(root ? (PackManager.selected() != null ? PackManager.selected() : "Shader pack") : getTitle().getString());
		grid(cells, Math.max(1, session.columns(id)));
		footer(button(root ? "Cancel" : "Back", this::onClose), button("Apply", this::apply), primary("Done", () -> {
			apply();
			minecraft.gui.setScreen(exit);
		}));
	}

	@Override
	protected String crumb() {
		return parent instanceof ShaderOptionsScreen p ? p.crumb() + " / " + getTitle().getString() : getTitle().getString();
	}

	@Override
	protected @Nullable Component headerStatus() {
		return PackManager.selected() == null ? null : Component.literal(PackManager.selected());
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(session.modified() ? Component.literal("Unapplied changes").withColor(CHANGED)
				: Component.literal("Right click: previous   Shift+click: default").withColor(FAINT));
	}

	private void apply() {
		if (!session.modified()) return;
		if (PackManager.applyOptions(session.pending) == PackManager.Applied.FAILED) return; // stays modified: Apply can retry
		session.applied.clear();
		session.applied.putAll(session.pending);
	}

	/** Tab (Iris): apply, then back to the pack list; Shift+Tab still moves the focus. */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (e.input() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && !e.hasShiftDown() && !e.hasControlDown() && !e.hasAltDown() && exit != null) {
			apply();
			minecraft.gui.setScreen(exit);
			return true;
		}
		return super.keyPressed(e);
	}

	/** Back to the parent screen; on the root this is Cancel (the pending edits die with the session). */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ---- elements ------------------------------------------------------------------------------------------------------

	private @Nullable AbstractWidget cell(String e) {
		if (e.equals("<empty>")) return null;
		if (e.equals("<profile>")) return profileCell();
		if (e.startsWith("[") && e.endsWith("]")) {
			String target = e.substring(1, e.length() - 1);
			Tile c = tile(() -> Component.literal(session.lang("screen." + target, target)), (dir, shift) ->
					minecraft.gui.setScreen(new ShaderOptionsScreen(this, exit, session, target))).value("›");
			c.active = session.hasScreen(target);
			tooltip(c, session.lang("screen." + target + ".comment"));
			return c;
		}
		Manifest.OptionDecl d = session.decls.get(e);
		if (d == null) return null; // Iris skips names that are no option
		List<String> allowed = "bool".equals(d.type()) ? List.of("false", "true") : d.allowed();
		AbstractWidget widget;
		if (session.slider(e) && allowed.size() > 1) {
			widget = new Slider(d, allowed);
		} else {
			Tile c = tile(() -> label(d), (dir, shift) -> {
				String v = session.pending.get(d.name());
				if (shift) v = d.defaultValue();
				else if (!allowed.isEmpty()) v = allowed.get(Math.floorMod(Math.max(allowed.indexOf(v), dir < 0 ? 0 : -1) + dir, allowed.size()));
				session.pending.put(d.name(), v);
			});
			// bool: the same switch as our own pages; the stripe marks a non-default value
			if ("bool".equals(d.type())) c.toggle(() -> "true".equals(session.pending.get(d.name())));
			else c.value(() -> value(d, session.pending.get(d.name())));
			c.lit(() -> !session.pending.get(d.name()).equals(d.defaultValue()));
			c.active = !allowed.isEmpty();
			widget = c;
		}
		String comment = session.lang("option." + e + ".comment");
		tooltip(widget, comment != null ? comment : d.comment());
		return widget;
	}

	private Tile profileCell() {
		List<String> names = session.options.profiles() == null ? List.of() : List.copyOf(session.options.profiles().keySet());
		Tile c = tile("Profile", (dir, shift) -> {
			if (names.isEmpty()) return;
			int i = names.indexOf(session.profile());
			String next = names.get(i < 0 ? (dir > 0 ? 0 : names.size() - 1) : Math.floorMod(i + dir, names.size()));
			session.pending.putAll(session.options.profiles().get(next));
		}).value(() -> {
			String p = session.profile();
			return p == null ? Component.literal("Custom").withStyle(ChatFormatting.YELLOW) : Component.literal(session.lang("profile." + p, p));
		});
		c.active = !names.isEmpty();
		tooltip(c, session.lang("profile.comment"));
		return c;
	}

	private static void tooltip(AbstractWidget w, @Nullable String text) {
		if (text != null && !text.isBlank()) w.setTooltip(Tooltip.create(Component.literal(text)));
	}

	private MutableComponent label(Manifest.OptionDecl d) {
		MutableComponent l = Component.literal(session.lang("option." + d.name(), d.name()));
		if (!session.pending.get(d.name()).equals(session.applied.get(d.name()))) l = Component.empty().append(l).withColor(CHANGED);
		return l;
	}

	private Component value(Manifest.OptionDecl d, String v) {
		if ("bool".equals(d.type())) {
			boolean on = v.equals("true");
			MutableComponent c = Component.literal(on ? "On" : "Off"); // as our switches (slider-style bools keep text)
			// like Iris: plain at the default, green/red otherwise
			return v.equals(d.defaultValue()) ? c : c.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		}
		String n = d.name();
		String text = session.lang("prefix." + n, "") + session.lang("value." + n + "." + v, v + session.lang("suffix." + n, ""));
		return Component.literal(text);
	}

	private Component optionMessage(Manifest.OptionDecl d) {
		return label(d).append(Component.literal(": ").withStyle(ChatFormatting.WHITE)).append(value(d, session.pending.get(d.name())));
	}

	/** Slider over the allowed values; shift-click resets to the default. */
	private final class Slider extends AbstractSliderButton {
		private final Manifest.OptionDecl d;
		private final List<String> allowed;

		Slider(Manifest.OptionDecl d, List<String> allowed) {
			super(0, 0, 98, ROW, Component.empty(), 0);
			this.d = d;
			this.allowed = allowed;
			refresh();
		}

		void refresh() {
			int i = allowed.indexOf(session.pending.get(d.name()));
			value = i < 0 ? 0 : (double) i / (allowed.size() - 1);
			updateMessage();
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (event.hasShiftDown()) {
				session.pending.put(d.name(), d.defaultValue());
				refresh();
			} else {
				super.onClick(event, doubleClick);
			}
		}

		@Override
		protected void updateMessage() {
			setMessage(optionMessage(d));
		}

		@Override
		protected void applyValue() {
			String v = allowed.get((int) Math.round(value * (allowed.size() - 1)));
			if (v.equals(session.pending.get(d.name()))) return;
			session.pending.put(d.name(), v);
		}

		@Override
		public boolean isMouseOver(double x, double y) {
			return super.isMouseOver(x, y) && inView(y);
		}

		/** A tile with a thin value track along its bottom edge instead of the stone slider. */
		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			refresh(); // a profile or shift-click elsewhere may have moved the value
			tileFrame(g, this, !session.pending.get(d.name()).equals(d.defaultValue()));
			tileText(g, font, this, label(d), value(d, session.pending.get(d.name())), null, null, TEXT);
			int x0 = getX() + 7, x1 = getRight() - 7, y = getBottom() - 4, px = x0 + (int) Math.round(value * (x1 - x0));
			g.fill(x0, y, x1, y + 1, 0xFF4A2E20);
			if (active) g.fill(x0, y, px, y + 1, EMBER);
			g.fill(px - 1, y - 1, px + 2, y + 2, active ? GOLD : FAINT);
		}
	}
}
