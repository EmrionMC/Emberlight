package dev.emrion.shaders.mod.autotest;

import dev.emrion.shaders.mod.EmrionShadersClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * CPU frame times (start of one frame to the next) in a fixed ring buffer; no allocation per frame. {@link #frame()} is
 * called from the frame hook; {@link #stopAndDump} writes a CSV and logs avg FPS and 1% lows.
 */
public final class FrameTimeLogger {
	public static final FrameTimeLogger INSTANCE = new FrameTimeLogger(1 << 16);

	private final long[] nanos;
	private int count;
	private long last;
	private boolean recording;
	private long frames;

	public FrameTimeLogger(int capacity) {
		this.nanos = new long[capacity];
	}

	public void start() {
		count = 0;
		last = 0;
		recording = true;
	}

	public boolean recording() {
		return recording;
	}

	/** Call once per frame, at the same point every frame. */
	public void frame() {
		frames++;
		if (!recording) return;
		long now = System.nanoTime();
		if (last != 0) nanos[count++ % nanos.length] = now - last;
		last = now;
	}

	/** Frames rendered since start-up (counted even when not recording). */
	public long frames() {
		return frames;
	}

	public record Stats(int frames, double avgFps, double low1Fps, double avgMs, double maxMs) {}

	public Stats stats() {
		int n = Math.min(count, nanos.length);
		if (n == 0) return new Stats(0, 0, 0, 0, 0);
		long[] sorted = Arrays.copyOf(nanos, n);
		Arrays.sort(sorted);
		double total = 0;
		for (long t : sorted) total += t;
		// 1% low: average FPS over the slowest 1% of frames.
		int worst = Math.max(1, n / 100);
		double worstTotal = 0;
		for (int i = n - worst; i < n; i++) worstTotal += sorted[i];
		return new Stats(n, n / (total / 1e9), worst / (worstTotal / 1e9), total / n / 1e6, sorted[n - 1] / 1e6);
	}

	public Stats stopAndDump(Path csv) {
		recording = false;
		Stats s = stats();
		int n = Math.min(count, nanos.length);
		int start = count > nanos.length ? count % nanos.length : 0;
		StringBuilder sb = new StringBuilder("frame,ms\n");
		for (int i = 0; i < n; i++) sb.append(i).append(',').append(nanos[(start + i) % nanos.length] / 1e6).append('\n');
		try {
			Files.writeString(csv, sb);
		} catch (IOException e) {
			EmrionShadersClient.LOG.warn("Could not write {}", csv, e);
		}
		EmrionShadersClient.LOG.info("Benchmark: {} frames, avg {} FPS, 1% low {} FPS, avg {} ms, max {} ms -> {}", s.frames(),
				String.format("%.1f", s.avgFps()), String.format("%.1f", s.low1Fps()), String.format("%.2f", s.avgMs()), String.format("%.2f", s.maxMs()), csv);
		double[] w = windowFps(start, n, 0.5);
		if (w.length > 1) {
			double mean = Arrays.stream(w).average().orElse(0), sd = Math.sqrt(Arrays.stream(w).map(f -> (f - mean) * (f - mean)).sum() / (w.length - 1));
			EmrionShadersClient.LOG.info("Benchmark spread: {} windows of 0.5 s, FPS min {} max {} sd {} (avg +- {} at 95%)", w.length,
					String.format("%.1f", Arrays.stream(w).min().orElse(0)), String.format("%.1f", Arrays.stream(w).max().orElse(0)),
					String.format("%.1f", sd), String.format("%.1f", 1.96 * sd / Math.sqrt(w.length)));
		}
		return s;
	}

	/** FPS of consecutive windows of the given length (the incomplete last one dropped). */
	private double[] windowFps(int start, int n, double seconds) {
		java.util.List<Double> out = new java.util.ArrayList<>();
		long acc = 0, limit = (long) (seconds * 1e9);
		int frames = 0;
		for (int i = 0; i < n; i++) {
			acc += nanos[(start + i) % nanos.length];
			frames++;
			if (acc >= limit) {
				out.add(frames / (acc / 1e9));
				acc = 0;
				frames = 0;
			}
		}
		return out.stream().mapToDouble(Double::doubleValue).toArray();
	}
}
