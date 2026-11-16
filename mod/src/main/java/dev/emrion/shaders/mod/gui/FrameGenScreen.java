package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.framegen.FrameGen;
import dev.emrion.shaders.mod.framegen.FrameGenConfig;
import dev.emrion.shaders.mod.framegen.FrameGenConfig.Mode;
import dev.emrion.shaders.mod.framegen.Reflex;
import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.upscale.Dlss;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** "Frame Generation" page: Off / AMD FSR 3 FG / NVIDIA DLSS FG; Done applies at the next frame. */
public final class FrameGenScreen extends EmberScreen {
	private Mode mode = FrameGenConfig.mode;
	private boolean autoPause = FrameGenConfig.autoPause;
	private Reflex.Mode reflex = Reflex.mode;

	public FrameGenScreen(@Nullable Screen parent) {
		super(Component.literal("Frame generation"), parent);
	}

	@Override
	protected void build() {
		String dlss = FrameGenConfig.dlssUnavailable();
		section("Frame generation");
		row(tile("Mode", (d, s) -> {
			mode = Mode.values()[Math.floorMod(mode.ordinal() + d, Mode.values().length)];
			rebuild();
		}).value(shortLabel(mode)).lit(() -> mode != Mode.OFF).hint(() -> mode == Mode.DLSS && Dlss.needsDownload() ? Component.literal("Needs a one-time download, see below")
				: mode == Mode.DLSS && dlss != null ? Component.literal("Unavailable: " + dlss).withColor(BAD)
				: FrameGen.failure() != null ? Component.literal("Unavailable: " + FrameGen.failure()).withColor(BAD)
				: mode == Mode.OFF ? null : Component.literal(mode == Mode.FSR ? "Works on any GPU" : "Needs an NVIDIA RTX 40 GPU or newer")).tooltip(
				"Shows an interpolated frame between every two rendered frames: about twice the frames on screen, "
						+ "about half a frame more latency. Best above 60 rendered FPS.\nOff: exactly the normal path.\n"
						+ "AMD FSR 3 Frame Generation: any GPU.\nNVIDIA DLSS Frame Generation: RTX 40 or newer, downloads NVIDIA's files once"
						+ (dlss != null ? " (unavailable: " + dlss + ")" : "") + "."));
		if (mode == Mode.DLSS && Dlss.needsDownload())
			row(tile("Download NVIDIA DLSS files", (d, s) -> minecraft.gui.setScreen(new DlssDownloadScreen(this))).value("›")
					.tooltip("About " + DlssDownloadScreen.TOTAL_MB + " MB from NVIDIA's GitHub, once. Until then FSR is used."));
		row(tile("Pause at refresh rate", (d, s) -> {
			autoPause = !autoPause;
			rebuild();
		}).toggle(() -> autoPause).active(mode != Mode.OFF).tooltip(
				"On: while the real FPS reach your monitor's refresh rate, frame generation pauses, since extra frames "
						+ "can't be shown and only cost GPU time. It resumes below 90 % of the refresh rate."));
		if (Reflex.supported) {
			// frame generation needs Reflex: Off then shows as On, the choice left is On or On + Boost
			boolean forced = mode != Mode.OFF;
			section("Latency");
			row(tile("NVIDIA Reflex", (d, s) -> {
				int n = Reflex.Mode.values().length;
				do reflex = Reflex.Mode.values()[Math.floorMod(reflex.ordinal() + d, n)]; while (forced && reflex == Reflex.Mode.OFF);
				rebuild();
			}).value((forced && reflex == Reflex.Mode.OFF ? Reflex.Mode.ON : reflex).label).lit(() -> forced || reflex != Reflex.Mode.OFF)
					.hint(forced ? Component.literal("Required by frame generation") : null).tooltip(
					"Lowers input latency: the game waits until just before the GPU is free before it reads your input and "
							+ "builds a frame, so no frames queue up in front of the GPU. Biggest effect when the GPU is the limit.\n"
							+ "On + Boost: also keeps the GPU clocks up (more power).\n"
							+ "With frame generation Reflex is always on.\nYour FPS limit keeps working (Reflex then paces the frames)."));
		}
		footer(primary("Done", this::onClose));
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(Component.literal("Applied when you leave this page.").withColor(FAINT));
		out.add(Component.literal(Dlss.TRADEMARKS));
	}

	@Override
	protected @Nullable Component headerStatus() {
		return Component.literal(shortLabel(FrameGenConfig.mode));
	}

	private static String shortLabel(Mode m) {
		return switch (m) {
			case OFF -> "Off";
			case FSR -> "AMD FSR 3";
			case DLSS -> "NVIDIA DLSS";
		};
	}

	@Override
	public void onClose() {
		if (mode != FrameGenConfig.mode || autoPause != FrameGenConfig.autoPause || reflex != Reflex.mode) {
			FrameGenConfig.mode = mode;
			FrameGenConfig.autoPause = autoPause;
			Reflex.mode = reflex;
			PackManager.applyFrameGen();
		}
		minecraft.gui.setScreen(parent);
	}

}
