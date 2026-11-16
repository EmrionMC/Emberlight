package dev.emrion.shaders.mod.gui;

import static dev.emrion.shaders.mod.EmrionShadersClient.LOG;

import dev.emrion.shaders.mod.upscale.Xess;
import java.net.URI;
import java.util.List;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Asks before fetching Intel's XeSS runtime (Windows) from Intel's GitHub release, then downloads it with progress. Same flow as {@link DlssDownloadScreen}. */
public final class XessDownloadScreen extends EmberScreen {
	private static final long TOTAL = Xess.ZIP.size();

	private volatile long done = -1; // -1: not started
	private volatile @Nullable String result;
	private volatile boolean finished;

	public XessDownloadScreen(@Nullable Screen parent) {
		super(Component.literal("Intel XeSS"), parent);
	}

	@Override
	protected void build() {
		section("Intel XeSS");
		note(this::text, TEXT);
		space(4);
		bar(() -> done < 0 || finished ? -1.0 : (double) done / Math.max(1, TOTAL));
		if (done < 0 || (finished && result != null)) {
			footer(primary(done < 0 ? "Download" : "Retry", this::start), button("Show licence",
					() -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(Xess.LICENCE_URL))), button("Cancel", this::onClose));
		} else if (finished) {
			footer(primary("Done", this::onClose));
		}
	}

	private Component text() {
		if (done < 0) return Component.literal("Intel XeSS needs Intel's runtime (libxess.dll). Emberlight downloads Intel's XeSS SDK " + Xess.TAG + " (~" + mb(TOTAL)
				+ " MB) from github.com/intel/xess and keeps only the runtime and its licence (Intel Simplified Software License). Minecraft needs one restart afterwards.");
		if (!finished) return Component.literal("Downloading from github.com/intel/xess: " + mb(done) + " / " + mb(TOTAL) + " MB");
		if (result == null) return Component.literal("XeSS is installed. Restart Minecraft to use it.").withColor(GOLD);
		return Component.literal("Download failed: " + result).withColor(0xFFFF8080);
	}

	@Override
	protected void footerLines(List<Component> out) {
		out.add(Component.literal(Xess.NOTICE));
	}

	private void start() {
		done = 0;
		finished = false;
		result = null;
		rebuild();
		Thread t = new Thread(() -> {
			try {
				Xess.download(n -> done = n);
			} catch (Exception e) {
				LOG.error("XeSS download failed", e);
				result = e.getMessage() != null ? e.getMessage() : e.toString();
			}
			finished = true;
			minecraft.execute(this::rebuildWidgets);
		}, "Emberlight XeSS download");
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
