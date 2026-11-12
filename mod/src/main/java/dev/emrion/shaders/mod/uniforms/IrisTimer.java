package dev.emrion.shaders.mod.uniforms;

/** frameCounter / frameTime / frameTimeCounter exactly like Iris SystemTimeUniforms (iris-spec §5.1). Pure, unit-tested. */
public final class IrisTimer {
	private int frameCounter;
	private float frameTime;
	private float frameTimeCounter;
	private long lastStartNanos;
	private boolean started;

	/** Once per frame, before anything reads the values. */
	public void beginFrame(long nanos) {
		frameCounter = (frameCounter + 1) % 720720;
		long diffMs = (started ? nanos - lastStartNanos : 0) / 1000 / 1000;
		frameTime = diffMs / 1000.0f; // millisecond resolution, as Iris
		frameTimeCounter += frameTime;
		if (frameTimeCounter >= 3600.0f) frameTimeCounter = 0.0f; // Iris resets (not wraps) every hour
		lastStartNanos = nanos;
		started = true;
	}

	public int frameCounter() { return frameCounter; }

	/** Seconds the previous frame took. */
	public float frameTime() { return frameTime; }

	public float frameTimeCounter() { return frameTimeCounter; }
}
