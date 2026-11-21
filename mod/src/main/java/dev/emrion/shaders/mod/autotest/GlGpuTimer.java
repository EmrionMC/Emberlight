package dev.emrion.shaders.mod.autotest;

import org.lwjgl.opengl.GL33;

/**
 * GPU ms per frame on the OpenGL backend (the Iris reference), for benchmarks: a GL_TIMESTAMP at the start and end of
 * GameRenderer.render, the same span the Vulkan profiler's frame total covers. Results are read a few frames late, never
 * blocking. Only used while a benchmark records and our pipeline is not active.
 */
public final class GlGpuTimer {
	private static final int RING = 8;
	private static int[] queries; // [2 * slot] start, [2 * slot + 1] end
	private static long frame, issued;
	private static boolean started;
	private static double totalNs;
	private static int samples;

	private GlGpuTimer() {}

	public static void reset() {
		totalNs = 0;
		samples = 0;
		issued = frame; // ignore queries from before the reset
	}

	public static void frameStart() {
		if (queries == null) {
			queries = new int[2 * RING];
			GL33.glGenQueries(queries);
		}
		int slot = (int) (frame % RING);
		if (frame - issued >= RING) { // the slot holds a frame from RING frames ago: collect it if ready
			if (GL33.glGetQueryObjecti(queries[2 * slot + 1], GL33.GL_QUERY_RESULT_AVAILABLE) != 0) {
				totalNs += GL33.glGetQueryObjecti64(queries[2 * slot + 1], GL33.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(queries[2 * slot], GL33.GL_QUERY_RESULT);
				samples++;
			}
		}
		GL33.glQueryCounter(queries[2 * slot], GL33.GL_TIMESTAMP);
		started = true;
	}

	public static void frameEnd() {
		if (!started) return; // recording began between the frame's start and end
		started = false;
		GL33.glQueryCounter(queries[2 * (int) (frame % RING) + 1], GL33.GL_TIMESTAMP);
		frame++;
	}

	/** Mean GPU ms per frame since {@link #reset()}, or NaN without samples. */
	public static double meanMs() {
		return samples == 0 ? Double.NaN : totalNs / samples / 1e6;
	}

	/** Logs the mean since {@link #reset()} next to the frame-time line. */
	public static void log() {
		dev.emrion.shaders.mod.EmrionShadersClient.LOG.info("Benchmark profile (OpenGL): GPU {} ms per frame over {} frames", String.format(java.util.Locale.ROOT, "%.2f", meanMs()), samples);
	}
}
