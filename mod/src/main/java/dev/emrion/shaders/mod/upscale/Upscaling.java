package dev.emrion.shaders.mod.upscale;

import org.joml.Matrix4f;

/**
 * Render-thread state shared by the uniforms and the runtime while an upscaler runs: the render
 * size the pack sees and this frame's sub-pixel jitter. Everything here is a no-op while upscaling is off.
 */
public final class Upscaling {
	private Upscaling() {}

	/** Render and display size of the running upscaler; 0 = off. */
	private static int renderW, renderH, displayW, displayH;
	/** This frame's jitter in render pixels (FSR convention, see {@link #jitterX}), and the frame index of the sequence. */
	private static float jx, jy;
	private static int frame;
	/** The level projection of this frame before the jitter (GL convention), for the motion vectors and FSR's camera data. */
	public static final Matrix4f unjittered = new Matrix4f();
	/** The same for the hand projection (object motion of the first-person hand). */
	public static final Matrix4f unjitteredHand = new Matrix4f();
	private static final Matrix4f T = new Matrix4f();
	/** -Demrion.upscale.jitterSign=-1 flips the jitter handed to the upscaler (a debugging aid for the sign convention). */
	private static final float SIGN = Float.parseFloat(System.getProperty("emrion.upscale.jitterSign", "1"));

	private static volatile int generation;

	/** Bumped when the upscaling settings change; a pack runtime built for an older generation is rebuilt (a resize). */
	public static int generation() {
		return generation;
	}

	public static void settingsChanged() {
		Dlss.retryCreate();
		Xess.retryCreate();
		generation++;
	}

	/** The render size for a window of {@code w × h} with the current settings ({w, h} when off). */
	public static int[] renderSize(int w, int h) {
		if (UpscaleConfig.effective() == UpscaleConfig.Mode.OFF) return new int[] {w, h};
		float r = UpscaleConfig.preset.ratio;
		return new int[] {Math.max(1, (int) (w / r)), Math.max(1, (int) (h / r))};
	}

	/** Called when a pack runtime is created: the sizes it renders and displays at, and whether an upscaler runs. */
	public static void setSizes(int rw, int rh, int dw, int dh, boolean on) {
		renderW = on ? rw : 0;
		renderH = on ? rh : 0;
		displayW = dw;
		displayH = dh;
	}

	public static boolean active() {
		return renderW > 0;
	}

	/** viewWidth/viewHeight the pack sees: the render size while upscaling, else MC's main target size. */
	public static int viewWidth(int window) {
		return renderW > 0 ? renderW : window;
	}

	public static int viewHeight(int window) {
		return renderH > 0 ? renderH : window;
	}

	/**
	 * Builtins.capture, once per frame after the projections are known: picks the next Halton(2,3) sample and translates
	 * {@code projection} and {@code hand} by it in NDC. Returns false (matrices untouched) while upscaling is off.
	 */
	public static boolean jitter(Matrix4f projection, Matrix4f hand) {
		unjittered.set(projection); // also read by frame generation with upscaling off
		unjitteredHand.set(hand);
		if (renderW <= 0) return false;
		int phases = Math.max(1, (int) (8.0 * Math.pow((double) displayW / renderW, 2.0)));
		int i = frame++ % phases + 1;
		jx = halton(i, 2) - 0.5f;
		jy = halton(i, 3) - 0.5f;
		// our images keep GL row order, so +y here matches FSR's D3D -y
		T.translation(2f * jx / renderW, 2f * jy / renderH, 0f);
		T.mul(projection, projection);
		T.mul(hand, hand);
		return true;
	}

	/** This frame's jitter as an NDC offset (0 while upscaling is off). */
	public static float ndcJitterX() {
		return renderW > 0 ? 2f * jx / renderW : 0f;
	}

	public static float ndcJitterY() {
		return renderH > 0 ? 2f * jy / renderH : 0f;
	}

	/** Jitter of this frame in render pixels, as the upscaler expects it. */
	public static float jitterX() {
		return SIGN * jx;
	}

	public static float jitterY() {
		return SIGN * jy;
	}

	static float halton(int index, int base) {
		float f = 1f, r = 0f;
		for (int i = index; i > 0; i /= base) {
			f /= base;
			r += f * (i % base);
		}
		return r;
	}
}
