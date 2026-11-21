package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.framegen.FrameGenConfig;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.rt.RtCapability;
import dev.emrion.shaders.mod.rt.RtConfig;
import dev.emrion.shaders.mod.runtime.ReceiverGrid;
import dev.emrion.shaders.mod.upscale.UpscaleConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The look of every Emberlight page: a dark bar with a breadcrumb and live state, a centred column of sections made of
 * flat tiles (label left, live value right, optional status line) that scrolls between the header and the footer buttons.
 * Flat fills and 1 px lines only, so it stays pixel-crisp at every GUI scale.
 */
public abstract class EmberScreen extends Screen {
	public static final int GOLD = 0xFFFFC447, EMBER = 0xFFEF7D1F, TEXT = 0xFFF2E6D8, DIM = 0xFFA8988A, FAINT = 0xFF928479,
			BAD = 0xFFFF7A5C, CHANGED = 0xFFFFC94A;
	private static final int BAR = 0xF00E0805, SHADE = 0xB8120A06, LINE = 0xFF4A2E20, TILE = 0x90000000, TILE_HOT = 0xC0301608,
			PRIMARY = 0xFF8A3A10, PRIMARY_HOT = 0xFFB4501A;
	protected static final int HEADER = 28, GAP = 4, COL = 200, PAD = 12, ROW = 20, ROW_HINT = 30;

	protected final @Nullable Screen parent;
	private final List<AbstractWidget> body = new ArrayList<>();
	private final List<Integer> bodyY = new ArrayList<>();
	private final List<Deco> decos = new ArrayList<>();
	private final List<Tile> footer = new ArrayList<>();
	/** Content column: x and width. */
	protected int left, inner;
	private int cursor, contentHeight, scroll;
	private int restoreFocus = -1;

	protected EmberScreen(Component title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Adds the sections, tiles and footer buttons ({@link #section}, {@link #row}, {@link #footer}). */
	protected abstract void build();

	/** Right side of the header (live state), e.g. the selected pack. */
	protected @Nullable Component headerStatus() {
		return null;
	}

	/** Lines above the footer buttons (centred, wrapped), e.g. the NVIDIA trademark attribution. */
	protected void footerLines(List<Component> out) {}

	/** Breadcrumb after "Emberlight"; null = only the title. */
	protected String crumb() {
		return getTitle().getString();
	}

	@Override
	protected final void init() {
		body.clear();
		bodyY.clear();
		decos.clear();
		footer.clear();
		inner = Math.min(width - 2 * PAD, 2 * COL + GAP);
		left = (width - inner) / 2;
		cursor = 8;
		build();
		contentHeight = cursor + 4;
		for (AbstractWidget w : body) addWidget(w); // tab order: the content first, then the footer buttons
		int n = footer.size(), bw = Math.min(98, (inner - (n - 1) * GAP) / Math.max(1, n));
		int fx = width / 2 - (n * bw + (n - 1) * GAP) / 2;
		for (Tile t : footer) {
			t.setRectangle(bw, 20, fx, height - 26);
			fx += bw + GAP;
			addRenderableWidget(t);
		}
		scroll = Mth.clamp(scroll, 0, maxScroll());
		layout();
		if (restoreFocus >= 0 && restoreFocus < body.size()) setFocused(body.get(restoreFocus));
		restoreFocus = -1;
	}

	/** rebuildWidgets() that keeps the keyboard focus on the same tile (values and states are rebuilt from scratch). */
	protected void rebuild() {
		if (minecraft.getLastInputType().isKeyboard() && getFocused() instanceof AbstractWidget w) restoreFocus = body.indexOf(w);
		rebuildWidgets();
	}

	// ---- building ----------------------------------------------------------------------------------------------------

	/** Section heading: a small ember square, the name in gold and a rule to the right edge. */
	protected void section(String name) {
		if (cursor > 8) cursor += 6;
		decos.add(new Deco(cursor, (g, x, y, w) -> {
			g.fill(x, y + 2, x + 4, y + 6, EMBER);
			g.text(font, name, x + 8, y, GOLD);
			int tx = x + 8 + font.width(name) + 6;
			if (tx < x + w) g.fill(tx, y + 3, x + w, y + 4, LINE);
		}));
		cursor += 14;
	}

	/** Tiles side by side, sharing the column width; on a narrow window (or more than {@code max} per row) they wrap. */
	protected void row(AbstractWidget... tiles) {
		grid(List.of(tiles), tiles.length);
	}

	/** Tiles in {@code cols} columns (fewer when the column is too narrow for 110 px tiles); null leaves a cell empty. */
	protected void grid(List<? extends @Nullable AbstractWidget> tiles, int cols) {
		cols = Math.max(1, Math.min(cols, (inner + GAP) / (110 + GAP)));
		int w = (inner - (cols - 1) * GAP) / cols;
		for (int i = 0; i < tiles.size(); i += cols) {
			int h = 0;
			for (int c = 0; c < cols && i + c < tiles.size(); c++) {
				AbstractWidget t = tiles.get(i + c);
				if (t == null) continue;
				t.setWidth(c == cols - 1 ? inner - c * (w + GAP) : w); // the last column takes the rounding rest
				t.setX(left + c * (w + GAP));
				body.add(t);
				bodyY.add(cursor);
				h = Math.max(h, t.getHeight());
			}
			cursor += h + GAP;
		}
	}

	/** Wrapped text in the column; the text may change, the space is reserved for its length now. */
	protected void note(Supplier<Component> text, int color) {
		int lines = Math.max(1, font.split(text.get(), inner).size());
		decos.add(new Deco(cursor, (g, x, y, w) -> {
			int ly = y;
			for (FormattedCharSequence l : font.split(text.get(), w)) {
				g.text(font, l, x, ly, color);
				ly += 10;
			}
		}));
		cursor += lines * 10 + 2;
	}

	/** A progress bar (0..1, below 0 hidden). */
	protected void bar(Supplier<Double> progress) {
		decos.add(new Deco(cursor, (g, x, y, w) -> {
			double p = progress.get();
			if (p < 0) return;
			g.fill(x, y, x + w, y + 6, 0xFF000000);
			g.outline(x, y, w, 6, LINE);
			g.fill(x + 1, y + 1, x + 1 + (int) ((w - 2) * Math.min(1, p)), y + 5, EMBER);
		}));
		cursor += 10;
	}

	protected void space(int px) {
		cursor += px;
	}

	protected void footer(Tile... buttons) {
		footer.addAll(List.of(buttons));
	}

	// ---- layout, scrolling, input ------------------------------------------------------------------------------------

	private int footerHeight() {
		return 32 + footerText().size() * 10;
	}

	private List<FormattedCharSequence> footerText() {
		List<Component> lines = new ArrayList<>();
		footerLines(lines);
		List<FormattedCharSequence> out = new ArrayList<>();
		for (Component c : lines) out.addAll(font.split(c, Math.max(50, width - 2 * PAD)));
		return out;
	}

	private int viewTop() {
		return HEADER + 1;
	}

	private int viewBottom() {
		return height - footerHeight();
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (viewBottom() - viewTop()));
	}

	boolean inView(double y) {
		return y >= viewTop() && y < viewBottom();
	}

	private void layout() {
		for (int i = 0; i < body.size(); i++) body.get(i).setY(viewTop() + bodyY.get(i) - scroll);
	}

	private void scrollTo(int s) {
		scroll = Mth.clamp(s, 0, maxScroll());
		layout();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int s = Mth.clamp(scroll - (int) (scrollY * 24), 0, maxScroll());
		if (s == scroll) return super.mouseScrolled(x, y, scrollX, scrollY);
		scrollTo(s);
		return true;
	}

	/** Keyboard/controller focus scrolls the focused tile into view. */
	@Override
	public void setFocused(@Nullable GuiEventListener focused) {
		super.setFocused(focused);
		int i = focused instanceof AbstractWidget w ? body.indexOf(w) : -1;
		if (i < 0) return;
		int top = bodyY.get(i), bottom = top + body.get(i).getHeight(), view = viewBottom() - viewTop();
		if (top - 6 < scroll) scrollTo(top - (i == 0 ? 8 : 20)); // with the section heading above
		else if (bottom + 6 > scroll + view) scrollTo(bottom + 6 - view);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// ---- drawing -----------------------------------------------------------------------------------------------------

	/** Flat warm shade instead of the blur, so a shader pack's look stays visible behind the page. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (minecraft.level == null) super.extractBackground(g, mouseX, mouseY, partialTick);
		g.fill(0, 0, width, height, SHADE);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		layout(); // the footer height (and with it the view) can change from frame to frame
		scroll = Mth.clamp(scroll, 0, maxScroll());
		// header
		g.fill(0, 0, width, HEADER, BAR);
		g.fill(0, HEADER, width, HEADER + 1, LINE);
		float p = PackManager.progress;
		if (p >= 0) g.fill(0, HEADER - 1, (int) (width * Math.min(1, p)), HEADER + 1, EMBER); // conversion progress
		int tx = left;
		g.text(font, "Emberlight", tx, 10, GOLD);
		tx += font.width("Emberlight");
		String crumb = crumb();
		if (crumb != null && !crumb.isEmpty()) {
			g.text(font, " / ", tx, 10, FAINT);
			tx += font.width(" / ");
			String c = font.plainSubstrByWidth(crumb, Math.max(0, left + inner - tx));
			g.text(font, c, tx, 10, TEXT);
			tx += font.width(c);
		}
		float pr = PackManager.progress;
		Component status = pr >= 0 ? Component.literal(PackManager.status + " " + (int) (pr * 100) + "%") : headerStatus();
		if (status != null && left + inner - tx > 40) {
			FormattedCharSequence s = fit(font, status, left + inner - tx - 12);
			g.text(font, s, left + inner - font.width(s), 10, DIM);
		}
		// body
		int top = viewTop(), bottom = viewBottom();
		g.enableScissor(0, top, width, bottom);
		for (Deco d : decos) {
			int y = top + d.y - scroll;
			if (y < bottom && y + 40 > top) d.drawer.draw(g, left, y, inner);
		}
		for (AbstractWidget w : body)
			if (w.getBottom() > top && w.getY() < bottom) w.extractRenderState(g, mouseX, mouseY, partialTick);
		g.disableScissor();
		if (maxScroll() > 0) {
			int track = bottom - top, thumb = Math.max(12, track * track / (track + maxScroll()));
			int ty = top + (track - thumb) * scroll / maxScroll(), sx = Math.min(width - 3, left + inner + 4);
			g.fill(sx, top, sx + 2, bottom, 0x40000000);
			g.fill(sx, ty, sx + 2, ty + thumb, EMBER);
			if (scroll > 0) g.fill(0, top, width, top + 1, 0x60000000); // content runs on above
		}
		// footer
		g.fill(0, bottom, width, height, BAR);
		g.fill(0, bottom, width, bottom + 1, LINE);
		int ly = bottom + 5;
		for (FormattedCharSequence l : footerText()) {
			g.centeredText(font, l, width / 2, ly, DIM);
			ly += 10;
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick); // footer buttons
	}

	/** {@code c} cut to {@code w} px with an ellipsis. */
	static FormattedCharSequence fit(Font font, Component c, int w) {
		if (font.width(c) <= w) return c.getVisualOrderText();
		FormattedText cut = font.substrByWidth(c, Math.max(0, w - font.width("…")));
		return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
	}

	private record Deco(int y, Drawer drawer) {}

	private interface Drawer {
		void draw(GuiGraphicsExtractor g, int x, int y, int w);
	}

	/** Tile background: flat, 1 px border, ember border on hover, gold on keyboard focus, ember stripe when lit. */
	static void tileFrame(GuiGraphicsExtractor g, AbstractWidget t, boolean lit) {
		boolean hot = t.active && t.isHovered(), focus = t.active && t.isFocused();
		int x = t.getX(), y = t.getY(), w = t.getWidth(), h = t.getHeight();
		g.fill(x, y, x + w, y + h, hot || focus ? TILE_HOT : TILE);
		g.outline(x, y, w, h, focus ? GOLD : hot ? EMBER : LINE);
		if (lit) g.fill(x + 1, y + 1, x + 3, y + h - 1, t.active ? EMBER : FAINT);
	}

	/** Label at the left, value (or switch) at the right, status line below; everything cut to the tile. */
	static void tileText(GuiGraphicsExtractor g, Font font, AbstractWidget t, Component label, @Nullable Component value, @Nullable Boolean toggle,
			@Nullable Component hint, int labelColor) {
		int x = t.getX() + 7, y = t.getY(), w = t.getWidth() - 14, h = t.getHeight();
		int ty = hint == null ? y + (h - 8) / 2 : y + 6;
		int right = x + w;
		if (toggle != null) {
			int sx = right - 15, sy = ty;
			g.fill(sx, sy, sx + 15, sy + 8, toggle && t.active ? 0xFF8A3A10 : 0xFF000000);
			g.outline(sx, sy, 15, 8, toggle && t.active ? EMBER : LINE);
			int kx = toggle ? sx + 9 : sx + 2;
			g.fill(kx, sy + 2, kx + 4, sy + 6, !t.active ? FAINT : toggle ? GOLD : DIM);
			right = sx - 6;
		} else if (value != null) {
			FormattedCharSequence v = fit(font, value, Math.max(30, w * 3 / 5));
			int vw = font.width(v);
			g.text(font, v, right - vw, ty, t.active ? GOLD : FAINT);
			right -= vw + 8;
		}
		g.text(font, fit(font, label, Math.max(10, right - x)), x, ty, t.active ? labelColor : FAINT);
		if (hint != null) g.text(font, fit(font, hint, w), x, y + 18, t.active ? DIM : FAINT);
	}

	// ---- tiles -------------------------------------------------------------------------------------------------------

	public interface Action {
		/** @param direction 1 = next (left click, Enter), -1 = previous (right click) */
		void run(int direction, boolean shift);
	}

	/**
	 * Option tile and footer button in one: label, live value or ON/OFF switch, optional status line (then the tile is
	 * taller). Left click/Enter: next, right click: previous, shift: as passed to the action.
	 */
	public static class Tile extends Button {
		private final EmberScreen owner;
		private final Supplier<Component> label;
		private Supplier<@Nullable Component> value = () -> null;
		private @Nullable BooleanSupplier toggle;
		private Supplier<@Nullable Component> hint = () -> null;
		private BooleanSupplier lit = () -> false;
		private Supplier<Integer> labelColor = () -> TEXT;
		private final Action action;
		private boolean button, primary;

		Tile(EmberScreen owner, Supplier<Component> label, Action action) {
			super(0, 0, 98, ROW, label.get(), b -> {}, DEFAULT_NARRATION);
			this.owner = owner;
			this.label = label;
			this.action = action;
		}

		/** Value shown at the right, in gold. */
		public Tile value(Supplier<@Nullable Component> v) {
			value = v;
			return refresh();
		}

		public Tile value(String v) {
			return value(() -> Component.literal(v));
		}

		/** ON/OFF switch at the right; the tile is lit while on. */
		public Tile toggle(BooleanSupplier on) {
			toggle = on;
			lit = on;
			return refresh();
		}

		/** Status line under the label (makes the tile taller when present now). */
		public Tile hint(Supplier<@Nullable Component> h) {
			hint = h;
			if (h.get() != null) height = ROW_HINT;
			return this;
		}

		public Tile hint(@Nullable Component h) {
			return hint(() -> h);
		}

		public Tile lit(BooleanSupplier l) {
			lit = l;
			return this;
		}

		public Tile labelColor(Supplier<Integer> c) {
			labelColor = c;
			return this;
		}

		public Tile tooltip(@Nullable String text) {
			tip = text != null && !text.isBlank() ? text : null;
			shownHint = null;
			setTooltip(tip == null ? null : Tooltip.create(Component.literal(tip)));
			return this;
		}

		private @Nullable String tip, shownHint;

		/** A status line cut to the tile also leads the tooltip, so long reasons stay readable. */
		private void hintTooltip(Font font, @Nullable Component h) {
			String cut = h != null && font.width(h) > width - 14 ? h.getString() : null;
			if (java.util.Objects.equals(cut, shownHint)) return;
			shownHint = cut;
			String text = cut == null ? tip : tip == null ? cut : cut + "\n\n" + tip;
			setTooltip(text == null ? null : Tooltip.create(Component.literal(text)));
		}

		public Tile active(boolean a) {
			active = a;
			return this;
		}

		/** Footer style: centred label; primary = filled ember. */
		public Tile button(boolean primary) {
			this.button = true;
			this.primary = primary;
			return this;
		}

		/** The narrated message follows the shown value. */
		public Tile refresh() {
			Component v = toggle != null ? Component.literal(toggle.getAsBoolean() ? "ON" : "OFF") : value.get();
			setMessage(v == null ? label.get() : Component.empty().append(label.get()).append(": ").append(v));
			return this;
		}

		@Override
		public boolean isMouseOver(double x, double y) {
			return super.isMouseOver(x, y) && (button || owner.inView(y));
		}

		@Override
		protected boolean isValidClickButton(MouseButtonInfo b) {
			return b.button() == 1 || b.button() == 3;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			action.run(input instanceof MouseButtonEvent m && m.button() == 3 ? -1 : 1, input.hasShiftDown());
			refresh();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			Font font = owner.font;
			if (button) {
				boolean hot = active && isHoveredOrFocused();
				int x = getX(), y = getY();
				if (primary) {
					g.fill(x, y, x + width, y + height, !active ? 0xFF3A2418 : hot ? PRIMARY_HOT : PRIMARY);
					g.outline(x, y, width, height, hot ? GOLD : EMBER);
					if (active && isFocused()) g.outline(x + 1, y + 1, width - 2, height - 2, GOLD); // keyboard focus: double gold rim
				} else tileFrame(g, this, false);
				g.centeredText(font, fit(font, label.get(), width - 6), x + width / 2, y + (height - 8) / 2, active ? TEXT : FAINT);
				return;
			}
			tileFrame(g, this, lit.getAsBoolean());
			Component h = hint.get();
			hintTooltip(font, h);
			tileText(g, font, this, label.get(), value.get(), toggle == null ? null : toggle.getAsBoolean(), h, labelColor.get());
		}
	}

	protected Tile tile(String label, Action action) {
		return new Tile(this, () -> Component.literal(label), action);
	}

	protected Tile tile(Supplier<Component> label, Action action) {
		return new Tile(this, label, action);
	}

	/** Footer button. */
	protected Tile button(String label, Runnable press) {
		return tile(label, (d, s) -> press.run()).button(false);
	}

	protected Tile primary(String label, Runnable press) {
		return tile(label, (d, s) -> press.run()).button(true);
	}

	public static Component on(boolean on) {
		return Component.literal(on ? "On" : "Off");
	}


	// ---- the Emberlight pages, as tiles with their live state (pack screen only: one entry point) ------------------

	/** A native pack's ray tracing is set in its own options; other packs get Emberlight's RT page (which explains a missing table). */
	private void openRt() {
		PackManager.RtSupport rt = PackManager.rtSupport();
		OptionSession session = rt != null && rt.nativePack() ? PackManager.optionSession() : null;
		minecraft.gui.setScreen(session != null ? new ShaderOptionsScreen(this, session) : new RtOptionsScreen(this));
	}

	private static @Nullable String rtHint() {
		PackManager.RtSupport rt = PackManager.rtSupport();
		return rt == null ? null : rt.nativePack() ? "This pack does its own ray tracing. Opens the pack's options, where its ray tracing settings are."
				: rt.effects().isEmpty() ? "Emberlight can add ray tracing only to packs with an RT patch table (Complementary, or packs that ship one)." : null;
	}

	protected List<Tile> pageTiles() {
		return List.of(
				tile("Ray tracing", (d, s) -> openRt()).value(() -> {
					PackManager.RtSupport rt = PackManager.rtSupport();
					return Component.literal(rt != null && rt.nativePack() ? "Built into the pack" : rt != null && rt.effects().isEmpty() ? "Not for this pack"
							: !RtCapability.enabled ? "Not supported" : !RtConfig.enabled ? "Off" : RtConfig.style.equals("rtx") ? "On · RTX style" : "On · Faithful style");
				}).lit(() -> {
					PackManager.RtSupport rt = PackManager.rtSupport();
					return rt == null ? RtCapability.enabled && RtConfig.enabled : rt.nativePack() || !rt.effects().isEmpty() && RtCapability.enabled && RtConfig.enabled;
				}).tooltip(rtHint()),
				tile("Upscaling", (d, s) -> minecraft.gui.setScreen(new UpscaleScreen(this))).value(() -> Component.literal(
						UpscaleConfig.mode == UpscaleConfig.Mode.OFF ? "Off" : UpscaleConfig.mode.shortLabel + " · " + UpscaleConfig.preset.label))
						.lit(() -> UpscaleConfig.mode != UpscaleConfig.Mode.OFF),
				tile("Frame generation", (d, s) -> minecraft.gui.setScreen(new FrameGenScreen(this))).value(() -> Component.literal(switch (FrameGenConfig.mode) {
					case OFF -> "Off";
					case FSR -> "On · FSR 3";
					case DLSS -> "On · DLSS";
				})).lit(() -> FrameGenConfig.mode != FrameGenConfig.Mode.OFF),
				tile("Performance", (d, s) -> minecraft.gui.setScreen(new PerformanceScreen(this))).value(() -> Component.literal(ReceiverGrid.mode.equals("off") ? "No tweaks" : "Shadow culling on"))
						.lit(() -> !ReceiverGrid.mode.equals("off")));
	}
}
