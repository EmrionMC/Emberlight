package dev.emrion.shaders.mod.gui;

import static dev.emrion.shaders.mod.EmrionShadersClient.LOG;

import dev.emrion.shaders.mod.upscale.Dlss;
import dev.emrion.shaders.mod.upscale.DlssFiles;
import java.net.URI;
import java.util.List;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Asks before fetching NVIDIA's DLSS models (not bundled) from NVIDIA's GitHub, then downloads them with progress. */
public final class DlssDownloadScreen extends EmberScreen {
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	private static final java.util.List<DlssFiles.Remote> FILES = DlssFiles.downloads(WINDOWS);
	private static final long TOTAL = DlssFiles.totalSize(FILES);
	/** The download size for the hints on the Upscaling and Frame Generation pages. */
	public static final long TOTAL_MB = Math.round(TOTAL / 1048576.0);

	private volatile long done = -1; // -1: not started
	private volatile @Nullable String result;
	private volatile boolean finished;

	public DlssDownloadScreen(@Nullable Screen parent) {
		super(Component.literal("NVIDIA DLSS"), parent);
	}

	@Override
	protected void build() {
		section("NVIDIA DLSS");
		note(this::text, TEXT);
		space(4);
		bar(() -> done < 0 || finished ? -1.0 : (double) done / Math.max(1, TOTAL));
		if (done < 0 || (finished && result != null)) {
			footer(primary(done < 0 ? "Download" : "Retry", this::start), button("Show licence",
					() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(DlssFiles.LICENCE_URL))), button("Cancel", this::onClose));
		} else if (finished) {
			footer(primary("Done", this::onClose));
		}
	}

	private Component text() {
		if (done < 0) return Component.literal("NVIDIA DLSS needs NVIDIA's model files (Super Resolution, Frame Generation, Ray Reconstruction; ~" + mb(TOTAL) + " MB) from github.com/NVIDIA/DLSS. By downloading you "
				+ "accept NVIDIA's RTX SDK licence. Minecraft needs one restart afterwards.");
		if (!finished) return Component.literal("Downloading from github.com/NVIDIA/DLSS: " + mb(done) + " / " + mb(TOTAL) + " MB");
		if (result == null) return Component.literal("DLSS is installed. Restart Minecraft to use it.").withColor(GOLD);
		return Component.literal("Download failed: " + result).withColor(0xFFFF8080);
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(Component.literal(Dlss.TRADEMARKS));
	}

	private void start() {
		done = 0;
		finished = false;
		result = null;
		rebuild();
		Thread t = new Thread(() -> {
			try {
				DlssFiles.download(DlssFiles.BASE_URL, FILES, Dlss.directory(), n -> done = n);
				Dlss.downloaded();
			} catch (Exception e) {
				LOG.error("DLSS download failed", e);
				result = e.getMessage() != null ? e.getMessage() : e.toString();
			}
			finished = true;
			minecraft.execute(this::rebuildWidgets);
		}, "Emberlight DLSS download");
		t.setDaemon(true);
		t.start();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return done < 0 || finished;
	}

	private static long mb(long bytes) {
		return Math.round(bytes / 1e6);
	}
}
