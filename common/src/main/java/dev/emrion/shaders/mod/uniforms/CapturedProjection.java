package dev.emrion.shaders.mod.uniforms;

import org.joml.Matrix4f;

/** Matrices copied out of GameRenderer.renderLevel by GameRendererProjectionMixin (render thread only). MC convention (reverse-Z). */
public final class CapturedProjection {
	private CapturedProjection() {}

	/** Level projection exactly as MC uploads it this frame: incl. bob, hurt tilt and nausea without a pack; with one those are in viewRotationMatrix (Iris). */
	public static final Matrix4f LEVEL = new Matrix4f();
	/** bobHurt · bobView pose (identity when both are off). */
	public static final Matrix4f BOB = new Matrix4f();
	/** False until renderLevel ran once. */
	public static boolean valid;
}
