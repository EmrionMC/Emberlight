package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.mod.pack.PackManager;
import dev.emrion.shaders.mod.upscale.Dlss;
import dev.emrion.shaders.mod.upscale.UpscaleConfig;
import dev.emrion.shaders.mod.upscale.Xess;
import dev.emrion.shaders.mod.upscale.UpscaleConfig.Mode;
import dev.emrion.shaders.mod.upscale.UpscaleConfig.Preset;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * "Upscaling" page: mode, preset, sharpness. Done applies: Off ↔ on reconverts (the pack's TAA is
 * patched out), everything else only rebuilds the runtime at the next frame.
 */
public final class UpscaleScreen extends EmberScreen {
	private Mode mode = UpscaleConfig.mode;
	private Preset preset = UpscaleConfig.preset;
	private int sharpness = Math.round(UpscaleConfig.sharpness * 10);
	private boolean rr = UpscaleConfig.rayReconstruction;
	private boolean hdr = dev.emrion.shaders.mod.hdr.Hdr.enabled;
	private int peak = dev.emrion.shaders.mod.hdr.Hdr.peakNits, paperWhite = dev.emrion.shaders.mod.hdr.Hdr.paperWhiteNits;
	private static final int[] PEAKS = {400, 600, 800, 1000, 1500, 2000, 4000}, PAPER_WHITES = {80, 100, 150, 200, 250, 300, 400};

	public UpscaleScreen(@Nullable Screen parent) {
		super(Component.literal("Upscaling"), parent);
	}

	@Override
	protected void build() {
		String dlss = Dlss.unavailable(), fallback = upscaleFallback(mode);
		boolean needsDownload = mode == Mode.DLSS && Dlss.needsDownload() || mode == Mode.XESS && Xess.needsDownload();
		section("Upscaler");
		row(tile("Upscaler", (d, s) -> {
			do mode = Mode.values()[Math.floorMod(mode.ordinal() + d, Mode.values().length)];
			while (mode == Mode.XESS && !Xess.platformSupported()); // Intel ships no Linux runtime
			rebuild();
		}).value(mode.label).lit(() -> mode != Mode.OFF)
				.hint(needsDownload && fallback != null ? warning(fallback + " (download below)")
				: needsDownload ? Component.literal("Needs a one-time download, see below") // DLSS runs, only the FG/RR models are missing
				: mode == Mode.DLSS && dlss != null ? Component.literal("Unavailable, using FSR: " + dlss).withColor(BAD)
				: mode == Mode.XESS && Xess.unavailable() != null ? Component.literal("Unavailable, using FSR: " + Xess.unavailable()).withColor(BAD)
				: mode != Mode.OFF && dev.emrion.shaders.mod.upscale.VanillaUpscale.failure() != null
						? Component.literal("Unavailable: " + dev.emrion.shaders.mod.upscale.VanillaUpscale.failure()).withColor(BAD)
				: mode == Mode.OFF ? null : Component.literal(mode == Mode.DLSS ? "Needs an NVIDIA RTX GPU" : "Works on any GPU")).tooltip(
				"Renders the world at a lower resolution and upscales it: with a shader pack before its final pass, "
						+ "without one before the hand and the HUD, which stay at full resolution.\n"
						+ "Off: exactly the normal path.\nAMD FSR 3.1: any GPU.\nNVIDIA DLSS: RTX GPUs, downloads NVIDIA's files once"
						+ (dlss != null ? " (unavailable: " + dlss + ")" : "") + "."
						+ (Xess.platformSupported() ? "\nIntel XeSS: any GPU, downloads Intel's runtime once" + (Xess.unavailable() != null
								? " (unavailable: " + Xess.unavailable() + ")" : "") + "." : "")));
		if (mode == Mode.XESS && Xess.needsDownload())
			row(tile("Download Intel XeSS runtime", (d, s) -> minecraft.gui.setScreen(new XessDownloadScreen(this))).value("›")
					.tooltip("About " + Math.round(Xess.ZIP.size() / 1e6) + " MB from Intel's GitHub, once. Until then FSR is used."));
		if (mode == Mode.DLSS && Dlss.needsDownload())
			row(tile("Download NVIDIA DLSS files", (d, s) -> minecraft.gui.setScreen(new DlssDownloadScreen(this))).value("›")
					.tooltip("About " + DlssDownloadScreen.TOTAL_MB + " MB from NVIDIA's GitHub, once. Until then FSR is used."));
		// selectable with DLSS, RT on and a supported GPU/driver/model (the reason shows otherwise)
		var support = dev.emrion.shaders.mod.pack.PackManager.rtSupport();
		var rtState = dev.emrion.shaders.mod.pack.PackManager.rtState();
		boolean nativePack = support != null && support.nativePack();
		String rrWhy = mode != Mode.DLSS ? "needs NVIDIA DLSS as the upscaler"
				: !dev.emrion.shaders.mod.rt.RtCapability.enabled || !nativePack && !dev.emrion.shaders.mod.rt.RtConfig.enabled ? "needs ray tracing on"
				: Dlss.rrUnavailable() != null ? Dlss.rrUnavailable()
				: nativePack && rtState != null && rtState.unavailable().containsKey("rr") ? "this pack provides no Ray Reconstruction guides" : null;
		row(tile("Ray Reconstruction", (d, s) -> {
			rr = !rr;
			rebuild();
		}).toggle(() -> rr && rrWhy == null).active(rrWhy == null)
				.hint(rrWhy != null ? Component.literal("Unavailable: " + rrWhy).withColor(rr ? BAD : FAINT)
				: rr ? Component.literal(nativePack ? "Replaces the pack's RT denoisers" : "Replaces Emberlight's RT denoisers") : null).tooltip(
				"NVIDIA DLSS Ray Reconstruction: one AI model denoises the ray-traced lighting and upscales, instead of DLSS Super "
						+ "Resolution plus Emberlight's own GI, block-light and AO denoisers (they are switched off while it runs).\n"
						+ "Native Emberlight packs that support it skip their own denoisers instead.\n"
						+ "Needs DLSS, ray tracing on (built into native packs) and an RTX GPU; turning it on or off reconverts the pack."));
		section("Quality");
		row(tile("Preset", (d, s) -> {
			preset = Preset.values()[Math.floorMod(preset.ordinal() + d, Preset.values().length)];
			rebuild();
		}).value(preset.label + " · " + percent() + "%").active(mode != Mode.OFF).tooltip("Render resolution per axis (shown in %). Native AA: full resolution, "
				+ "only the anti-aliasing of the upscaler."), tile("Sharpness", (d, s) -> {
			sharpness = Math.floorMod(sharpness + d, 11);
			rebuild();
		}).value(sharpness * 10 + "%").active(mode != Mode.OFF).hint((mode == Mode.DLSS || mode == Mode.XESS) && fallback == null ? Component.literal("Not used by " + mode.shortLabel) : null)
				.tooltip("FSR's RCAS sharpening (0 = off). DLSS and XeSS ignore it."));
		String hdrWhy = dev.emrion.shaders.mod.hdr.Hdr.unsupported();
		section("HDR");
		row(tile("HDR output", (d, s) -> {
			hdr = !hdr;
			rebuild();
		}).toggle(() -> hdr && hdrWhy == null).active(hdrWhy == null)
				.hint(hdrWhy != null ? Component.literal("Unavailable: " + hdrWhy).withColor(hdr ? BAD : FAINT) : null).tooltip(
				"Sends the picture to an HDR display (HDR10): bright highlights (sun, sky, lights) go above normal white, "
						+ "the HUD and menus stay at paper white. The shader pack still renders SDR; Emberlight expands it.\n"
						+ "Needs an HDR display with HDR on in the system (KDE Plasma 6 on Wayland, gamescope with --hdr-enabled).\nOff: exactly the normal path."));
		row(tile("Peak brightness", (d, s) -> {
			peak = step(PEAKS, peak, d);
			rebuild();
		}).value(peak + " nits").active(hdr && hdrWhy == null).tooltip("Brightest highlight: set it to your display's peak brightness."),
				tile("Paper white", (d, s) -> {
			paperWhite = step(PAPER_WHITES, paperWhite, d);
			rebuild();
		}).value(paperWhite + " nits").active(hdr && hdrWhy == null).tooltip("Brightness of white paper, the HUD and menus (normal SDR white). "
				+ "Match your desktop's SDR brightness; 200 nits is typical."));
		footer(primary("Done", this::onClose));
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(Component.literal(PackManager.selected() == null ? "Applied when you leave. Works without a shader pack too."
				: "Applied when you leave; turning it on or off reconverts the pack.").withColor(FAINT));
		out.add(Component.literal(Dlss.TRADEMARKS));
	}

	@Override
	protected @Nullable Component headerStatus() {
		String fallback = upscaleFallback(UpscaleConfig.mode);
		return fallback != null ? warning(fallback) : Component.literal(UpscaleConfig.mode == Mode.OFF ? "Off"
				: (UpscaleConfig.rrActive() ? "DLSS RR" : UpscaleConfig.mode.shortLabel) + " · " + UpscaleConfig.preset.label);
	}

	private int percent() {
		return Math.round(100f / preset.ratio);
	}

	private static int step(int[] values, int current, int d) {
		int i = 0;
		while (i < values.length - 1 && values[i] < current) i++;
		return values[Math.floorMod(i + d, values.length)];
	}

	@Override
	public void onClose() {
		if (hdr != dev.emrion.shaders.mod.hdr.Hdr.enabled || peak != dev.emrion.shaders.mod.hdr.Hdr.peakNits || paperWhite != dev.emrion.shaders.mod.hdr.Hdr.paperWhiteNits) {
			dev.emrion.shaders.mod.hdr.Hdr.enabled = hdr;
			dev.emrion.shaders.mod.hdr.Hdr.peakNits = peak;
			dev.emrion.shaders.mod.hdr.Hdr.paperWhiteNits = paperWhite;
			PackManager.applyHdr();
		}
		if (mode != UpscaleConfig.mode || preset != UpscaleConfig.preset || sharpness != Math.round(UpscaleConfig.sharpness * 10)
				|| rr != UpscaleConfig.rayReconstruction) {
			UpscaleConfig.rayReconstruction = rr;
			UpscaleConfig.mode = mode;
			UpscaleConfig.preset = preset;
			UpscaleConfig.sharpness = sharpness / 10f;
			PackManager.applyUpscale();
		}
		minecraft.gui.setScreen(parent);
	}

}
