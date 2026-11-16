package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.pack.PackManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The pack loading panel at the top centre of the HUD (menu style): pack name, phase, progress line; then "Ready" or
 * "Failed: reason" for a moment. Part of the HUD, so F1 (and the autotest's hidden HUD) hides it. Set from any thread.
 */
public final class LoadingHud implements HudElement {
	private enum Kind { BUSY, READY, FAILED }

	private record State(String pack, String text, Kind kind) {}

	private static volatile @Nullable State state;
	private static volatile int shaderErrors;
	/** Render thread: the end state on screen since (ms), -1 = not shown yet. */
	private static long shownSince = -1;
	private static @Nullable State shown;

	/** A load or reload starts. */
	public static void begin(String pack) {
		shaderErrors = 0;
		state = new State(pack, "Reading pack", Kind.BUSY);
	}

	public static void phase(String pack, String text) {
		state = new State(pack, text, Kind.BUSY);
	}

	/** The converter's stages as phases. */
	public static void stage(String pack, String stage, int done, int total) {
		phase(pack, switch (stage) {
			case "load" -> "Reading pack";
			case "analyse" -> "Analysing shaders " + done + "/" + total;
			case "compile" -> "Compiling shaders " + done + "/" + total;
			case "plan" -> "Planning passes " + done + "/" + total;
			case "write" -> "Writing cache";
			default -> stage + " " + done + "/" + total;
		});
	}

	public static void shaderErrors(int n) {
		shaderErrors = n;
	}

	/** The new pipelines render; only ends a load this panel is showing (runtime rebuilds stay silent). */
	public static void ready(String pack) {
		State s = state;
		if (s == null || s.kind != Kind.BUSY) return;
		int n = shaderErrors;
		state = new State(pack, n == 0 ? "Ready" : "Ready, " + n + " shader error" + (n == 1 ? "" : "s") + " (see log)", Kind.READY);
	}

	public static void failed(String pack, String reason) {
		state = new State(pack, "Failed: " + reason, Kind.FAILED);
	}

	/** The phase of a running load, null when none runs. */
	public static @Nullable String busy() {
		State s = state;
		return s != null && s.kind == Kind.BUSY ? s.text : null;
	}

	public static void clear() {
		state = null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		State s = state;
		if (s == null || mc.gui.hud.isHidden()) return;
		long now = System.currentTimeMillis();
		if (s != shown) {
			shown = s;
			shownSince = now;
		}
		float alpha = 1;
		if (s.kind != Kind.BUSY) {
			// ready: 1.5 s, warnings and failures longer; then 0.4 s fade
			long hold = s.kind == Kind.FAILED ? 6000 : shaderErrors > 0 ? 4000 : 1500, t = now - shownSince - hold;
			if (t > 400) {
				if (state == s) state = null;
				return;
			}
			if (t > 0) alpha = 1 - t / 400f;
		}
		Font font = mc.font;
		int w = Math.min(220, g.guiWidth() - 16), x = (g.guiWidth() - w) / 2, y = 4, h = 29;
		g.fill(x, y, x + w, y + h, fade(0xE00E0805, alpha));
		g.outline(x, y, w, h, fade(0xFF4A2E20, alpha));
		float p = PackManager.progress;
		String pct = s.kind == Kind.BUSY && p >= 0 ? (int) (p * 100) + "%" : "";
		int pw = font.width(pct);
		g.text(font, EmberScreen.fit(font, Component.literal(s.pack), w - 14 - (pw > 0 ? pw + 6 : 0)), x + 6, y + 5, fade(EmberScreen.GOLD, alpha));
		if (pw > 0) g.text(font, pct, x + w - 6 - pw, y + 5, fade(EmberScreen.DIM, alpha));
		int color = switch (s.kind) {
			case BUSY -> EmberScreen.TEXT;
			case READY -> shaderErrors > 0 ? EmberScreen.CHANGED : EmberScreen.GOLD;
			case FAILED -> EmberScreen.BAD;
		};
		g.text(font, EmberScreen.fit(font, Component.literal(s.text), w - 12), x + 6, y + 16, fade(color, alpha));
		// progress line along the bottom edge: the converter's fraction, a sliding segment while it has none
		int bx = x + 1, bw = w - 2, by = y + h - 2;
		g.fill(bx, by, bx + bw, by + 1, fade(0xFF2A1A12, alpha));
		if (s.kind == Kind.BUSY && p >= 0) g.fill(bx, by, bx + (int) (bw * Math.min(1, p)), by + 1, fade(EmberScreen.EMBER, alpha));
		else if (s.kind == Kind.BUSY) {
			int seg = bw / 4, off = (int) (now / 4 % (bw + seg)) - seg;
			g.fill(bx + Math.max(0, off), by, bx + Math.min(bw, off + seg), by + 1, EmberScreen.EMBER);
		} else g.fill(bx, by, bx + bw, by + 1, fade(s.kind == Kind.FAILED ? EmberScreen.BAD : EmberScreen.EMBER, alpha));
	}

	private static int fade(int argb, float a) {
		return (Math.round((argb >>> 24) * a) << 24) | (argb & 0xFFFFFF);
	}
}
