package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.mod.pack.PackManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * One screen of the pack's option menu (shaders.properties {@code screen}/{@code screen.<NAME>}, iris-spec §4.2): a grid
 * of {@code columns} columns with options (cycling buttons, or sliders for names in {@code sliders}), {@code [SCREEN]}
 * links, {@code <profile>}, {@code <empty>} and {@code *}. Like Iris: left click = next value, right click = previous,
 * shift-click = default; labels of pending changes are yellow. Apply writes the Iris settings file and reloads the pack.
 */
public final class ShaderOptionsScreen extends Screen {
	private static final int ROW = 22, TOP = 34, FOOTER = 34;
	private static final int CHANGED = 0xFFFFC94A, VALUE = 0xFF6688FF;

	private final @Nullable Screen parent;
	private final @Nullable Screen exit;
	private final OptionSession session;
	private final String id;
	private final List<AbstractWidget> cells = new ArrayList<>();
	private final List<Integer> rows = new ArrayList<>();
	private int scroll;

	/** Root screen; {@code exit} is shown again on Done/Cancel. */
	public ShaderOptionsScreen(Screen exit, OptionSession session) {
		this(exit, exit, session, "");
	}

	/** Opens screen {@code id} ("" = main) directly over the game (autotest). */
	public static ShaderOptionsScreen open(OptionSession session, String id) {
		return new ShaderOptionsScreen(null, null, session, id);
	}

	private ShaderOptionsScreen(@Nullable Screen parent, @Nullable Screen exit, OptionSession session, String id) {
		super(Component.literal(id.isEmpty() ? "Shader Options" : session.lang("screen." + id, id)));
		this.parent = parent;
		this.exit = exit;
		this.session = session;
		this.id = id;
	}

	@Override
	protected void init() {
		cells.clear();
		rows.clear();
		List<String> elements = session.screen(id);
		int cols = Math.max(1, session.columns(id));
		int colW = Math.min(200, (width - 16) / cols - 4);
		int x0 = width / 2 - (cols * (colW + 4) - 4) / 2;
		for (int i = 0; i < elements.size(); i++) {
			AbstractWidget w = cell(elements.get(i), colW);
			if (w == null) continue;
			w.setX(x0 + i % cols * (colW + 4));
			rows.add(i / cols);
			cells.add(addRenderableWidget(w));
		}
		int bw = 98, y = height - 26;
		boolean root = id.isEmpty();
		addRenderableWidget(Button.builder(Component.literal(root ? "Cancel" : "Back"), b -> onClose()).bounds(width / 2 - 151, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).bounds(width / 2 - 49, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			apply();
			minecraft.gui.setScreen(exit);
		}).bounds(width / 2 + 53, y, bw, 20).build());
		scroll = Mth.clamp(scroll, 0, maxScroll());
		layout();
	}

	private int maxScroll() {
		int contentRows = rows.isEmpty() ? 0 : rows.getLast() + 1;
		return Math.max(0, contentRows * ROW - (height - FOOTER - TOP));
	}

	private void layout() {
		for (int i = 0; i < cells.size(); i++) {
			AbstractWidget w = cells.get(i);
			int y = TOP + rows.get(i) * ROW - scroll;
			w.setY(y);
			w.visible = y >= TOP && y + 20 <= height - FOOTER;
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int s = Mth.clamp(scroll - (int) (scrollY * ROW), 0, maxScroll());
		if (s == scroll) return super.mouseScrolled(x, y, scrollX, scrollY);
		scroll = s;
		layout();
		return true;
	}

	private void apply() {
		if (!session.modified()) return;
		PackManager.applyOptions(session.pending);
		session.applied.clear();
		session.applied.putAll(session.pending);
		refreshAll();
	}

	/** Back to the parent screen; on the root this is Cancel (the pending edits die with the session). */
	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
		if (session.modified()) g.text(font, Component.literal("unapplied changes").withColor(CHANGED), 6, height - 20, 0xFFFFFFFF);
		if (PackManager.progress >= 0) g.centeredText(font, PackManager.status, width / 2, 21, 0xFFE0E0E0);
		if (maxScroll() > 0) {
			int track = height - FOOTER - TOP, bar = Math.max(10, track * track / (track + maxScroll()));
			int by = TOP + (track - bar) * scroll / maxScroll();
			g.fill(width - 6, TOP, width - 2, TOP + track, 0x40FFFFFF);
			g.fill(width - 6, by, width - 2, by + bar, 0xC0FFFFFF);
		}
	}

	private void refreshAll() {
		cells.forEach(w -> {
			if (w instanceof Cell c) c.refresh();
			else if (w instanceof Slider s) s.refresh();
		});
	}

	// ---- elements ------------------------------------------------------------------------------------------------------

	private @Nullable AbstractWidget cell(String e, int w) {
		if (e.equals("<empty>")) return null;
		if (e.equals("<profile>")) return profileCell(w);
		if (e.startsWith("[") && e.endsWith("]")) {
			String target = e.substring(1, e.length() - 1);
			Cell c = new Cell(w, () -> Component.literal(session.lang("screen." + target, target) + "..."), (dir, shift) ->
					minecraft.gui.setScreen(new ShaderOptionsScreen(this, exit, session, target)));
			c.active = session.hasScreen(target);
			tooltip(c, session.lang("screen." + target + ".comment"));
			return c;
		}
		Manifest.OptionDecl d = session.decls.get(e);
		if (d == null) return null; // Iris skips names that are no option
		List<String> allowed = "bool".equals(d.type()) ? List.of("false", "true") : d.allowed();
		AbstractWidget widget;
		if (session.slider(e) && allowed.size() > 1) {
			widget = new Slider(w, d, allowed);
		} else {
			Cell c = new Cell(w, () -> optionMessage(d), (dir, shift) -> {
				String v = session.pending.get(d.name());
				if (shift) v = d.defaultValue();
				else if (!allowed.isEmpty()) v = allowed.get(Math.floorMod(Math.max(allowed.indexOf(v), dir < 0 ? 0 : -1) + dir, allowed.size()));
				session.pending.put(d.name(), v);
			});
			c.active = !allowed.isEmpty();
			widget = c;
		}
		String comment = session.lang("option." + e + ".comment");
		tooltip(widget, comment != null ? comment : d.comment());
		return widget;
	}

	private Cell profileCell(int w) {
		List<String> names = session.options.profiles() == null ? List.of() : List.copyOf(session.options.profiles().keySet());
		Cell c = new Cell(w, () -> {
			String p = session.profile();
			Component v = p == null ? Component.literal("Custom").withStyle(ChatFormatting.YELLOW) : Component.literal(session.lang("profile." + p, p));
			return Component.literal("Profile: ").append(v);
		}, (dir, shift) -> {
			if (names.isEmpty()) return;
			int i = names.indexOf(session.profile());
			String next = names.get(i < 0 ? (dir > 0 ? 0 : names.size() - 1) : Math.floorMod(i + dir, names.size()));
			session.pending.putAll(session.options.profiles().get(next));
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
		if (!session.pending.get(d.name()).equals(session.applied.get(d.name()))) l = Component.literal("").append(l).withColor(CHANGED);
		return l;
	}

	private Component value(Manifest.OptionDecl d, String v) {
		if ("bool".equals(d.type())) {
			boolean on = v.equals("true");
			MutableComponent c = Component.literal(on ? "ON" : "OFF");
			// like Iris: plain at the default, green/red otherwise
			return v.equals(d.defaultValue()) ? c : c.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		}
		String n = d.name();
		String text = session.lang("prefix." + n, "") + session.lang("value." + n + "." + v, v + session.lang("suffix." + n, ""));
		return Component.literal(text).withColor(VALUE);
	}

	private Component optionMessage(Manifest.OptionDecl d) {
		return label(d).append(Component.literal(": ").withStyle(ChatFormatting.WHITE)).append(value(d, session.pending.get(d.name())));
	}

	private interface Action {
		void run(int direction, boolean shift);
	}

	/** Button that also takes right clicks (previous value) and passes shift (reset). */
	private final class Cell extends Button {
		private final Supplier<Component> text;
		private final Action action;

		Cell(int w, Supplier<Component> text, Action action) {
			super(0, 0, w, 20, text.get(), b -> {}, DEFAULT_NARRATION);
			this.text = text;
			this.action = action;
		}

		void refresh() {
			setMessage(text.get());
		}

		@Override
		protected boolean isValidClickButton(MouseButtonInfo b) {
			return b.button() == 1 || b.button() == 3;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.run(input instanceof MouseButtonEvent m && m.button() == 3 ? -1 : 1, input.hasShiftDown());
			refreshAll();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			extractDefaultSprite(g);
			extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
		}
	}

	/** Slider over the allowed values; shift-click resets to the default. */
	private final class Slider extends AbstractSliderButton {
		private final Manifest.OptionDecl d;
		private final List<String> allowed;

		Slider(int w, Manifest.OptionDecl d, List<String> allowed) {
			super(0, 0, w, 20, Component.empty(), 0);
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
				refreshAll();
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
			refreshAll();
		}
	}
}
