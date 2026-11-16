package dev.emrion.shaders.mod.autotest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Has the picture stopped changing? Compares against a sample {@link #LAG_MS} ms and {@link #LAG_FRAMES} frames older, not
 * the previous frame, so slow drifts (auto exposure, temporal accumulation) also count as change.
 */
final class Convergence {
	static final double THRESHOLD = Double.parseDouble(System.getProperty("emrion.autotest.converge.threshold", "0.4"));
	static final int LAG_MS = 250, LAG_FRAMES = 24, STABLE = 5;

	private record Sample(long frame, long nanos, int[] rgb) {}

	private final List<Sample> samples = new ArrayList<>();
	private int generation, stable;
	private boolean pending;
	double lastDiff = Double.NaN;

	void reset() {
		samples.clear();
		stable = 0;
		lastDiff = Double.NaN;
		generation++; // drops a readback still in flight
	}

	boolean converged() {
		return stable >= STABLE;
	}

	/** Call once per tick on the render thread. */
	boolean poll(Minecraft mc) {
		if (!pending) {
			var target = mc.gameRenderer.mainRenderTarget();
			int d = 8;
			while (d > 1 && (target.width % d != 0 || target.height % d != 0)) d /= 2;
			int gen = generation;
			long frame = FrameTimeLogger.INSTANCE.frames(), nanos = System.nanoTime();
			pending = true;
			Screenshot.takeScreenshot(target, d, img -> {
				pending = false;
				try (img) {
					if (gen != generation) return;
					int w = img.getWidth(), h = img.getHeight();
					int[] rgb = new int[w * h * 3];
					for (int y = 0, i = 0; y < h; y++) for (int x = 0; x < w; x++) {
						int c = img.getPixel(x, y);
						rgb[i++] = c & 0xFF;
						rgb[i++] = c >> 8 & 0xFF;
						rgb[i++] = c >> 16 & 0xFF;
					}
					add(frame, nanos, rgb);
				}
			});
		}
		return converged();
	}

	void add(long frame, long nanos, int[] rgb) {
		Sample s = new Sample(frame, nanos, rgb);
		samples.add(s);
		while (samples.size() > 2 && old(samples.get(1), s)) samples.removeFirst(); // keep the newest sample that is old enough
		if (!old(samples.getFirst(), s)) return;
		lastDiff = diff(samples.getFirst().rgb(), rgb);
		stable = lastDiff < THRESHOLD ? stable + 1 : 0;
	}

	private static boolean old(Sample ref, Sample s) {
		return s.nanos() - ref.nanos() >= LAG_MS * 1_000_000L && s.frame() - ref.frame() >= LAG_FRAMES;
	}

	static double diff(int[] a, int[] b) {
		if (a.length != b.length) return Double.POSITIVE_INFINITY; // resized
		long sum = 0;
		for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
		return (double) sum / a.length;
	}
}
