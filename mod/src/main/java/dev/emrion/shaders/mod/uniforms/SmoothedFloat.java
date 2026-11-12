package dev.emrion.shaders.mod.uniforms;

/**
 * Iris uniforms/transforms/SmoothedFloat: exponential smoothing with separate up/down half lives in deciseconds
 * (wetness, eyeBrightnessSmooth). The first sample is taken as is. Pure, unit-tested.
 */
public final class SmoothedFloat {
	private static final double LN_OF_2 = Math.log(2.0);
	private final float decayUp;
	private final float decayDown;
	private float accumulator;
	private boolean initialized;

	public SmoothedFloat(float halfLifeUp, float halfLifeDown) {
		decayUp = decay(halfLifeUp * 0.1f);
		decayDown = decay(halfLifeDown * 0.1f);
	}

	private static float decay(float halfLife) {
		return (float) (1.0f / (halfLife / LN_OF_2));
	}

	/** Feeds one sample; frameTime is the previous frame's duration in seconds (Iris frameTime). */
	public float update(float value, float frameTime) {
		if (!initialized) {
			initialized = true;
			return accumulator = value;
		}
		float k = value > accumulator ? decayUp : decayDown;
		float alpha = 1.0f - (float) Math.exp(-k * frameTime);
		return accumulator = (1 - alpha) * accumulator + alpha * value;
	}

	public float get() { return accumulator; }
}
