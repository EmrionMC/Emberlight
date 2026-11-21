package dev.emrion.shaders.mod.uniforms;

/** frameCounter / frameTime / frameTimeCounter exactly like Iris SystemTimeUniforms. Pure. */
public final class IrisTimer {
	/** Scene comparisons pin frameTimeCounter (also in the Iris run, IrisTimerMixin) so animations match; -1 = off. */
	public static final float FIXED = Float.parseFloat(System.getProperty("emrion.autotest.frameTimeCounter", "-1"));
	/** Scene comparisons can pin frameTime too (seconds), so time-smoothed state advances the same per frame at any FPS; -1 = off. */
	public static final float FIXED_FRAME_TIME = Float.parseFloat(System.getProperty("emrion.autotest.frameTime", "-1"));
	/** Autotest {@code turnshot}: the next frame starts frameTimeCounter (and the frame counter's noise) here; -1 = off. */
	public static volatile float rewindTo = -1;
	/** Photo mode: frameTimeCounter (and the End portal's clock) stand still, frameCounter (the noise seed) runs on. */
	private static volatile boolean frozen;
	private static float frozenWall;
	private int frameCounter;
	private float frameTime;
	private float frameTimeCounter;
	private long lastStartNanos;
	private boolean started;

	/** Once per frame, before anything reads the values. */
	public void beginFrame(long nanos) {
		frameCounter = (frameCounter + 1) % 720720;
		long diffMs = (started ? nanos - lastStartNanos : 0) / 1000 / 1000;
		frameTime = FIXED_FRAME_TIME >= 0 ? FIXED_FRAME_TIME : diffMs / 1000.0f; // millisecond resolution, as Iris
		if (!frozen) frameTimeCounter += frameTime;
		if (rewindTo >= 0) {
			frameTimeCounter = rewindTo;
			frameCounter = 0;
			rewindTo = -1;
		}
		if (frameTimeCounter >= 3600.0f) frameTimeCounter = 0.0f; // Iris resets (not wraps) every hour
		lastStartNanos = nanos;
		started = true;
	}

	public static void freeze(boolean on) {
		if (on && !frozen) frozenWall = wallSeconds();
		frozen = on;
	}

	/** Seconds of the wall clock (wrapping hourly), held while frozen; for animations outside the pack uniforms. */
	public static float wallSeconds() {
		return frozen ? frozenWall : System.nanoTime() % 3_600_000_000_000L / 1e9f;
	}

	public int frameCounter() { return frameCounter; }

	/** Seconds the previous frame took. */
	public float frameTime() { return frameTime; }

	public float frameTimeCounter() { return FIXED >= 0 ? FIXED : frameTimeCounter; }
}
