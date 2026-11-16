package dev.emrion.shaders.converter.expr;

import java.util.OptionalInt;

/**
 * Game values for {@link CustomUniformRuntime}, by Iris built-in name. Values must be what Iris computes (e.g. cameraPosition
 * with the 30000-block shift); per-draw built-ins are not asked for, Iris feeds them in as constants.
 */
public interface HostValues {
	float getFloat(String name);

	int getInt(String name);

	boolean getBool(String name);

	/** vec2/vec3/vec4 into out[0..n), mat3/mat4 column-major into out[0..9/16). */
	void getFloats(String name, float[] out);

	/** ivec2/ivec3 into out. */
	void getInts(String name, int[] out);

	/** Numeric id Iris gives the BIOME_* macro {@code constantName}; empty if no such biome. Asked once. */
	OptionalInt biomeId(String constantName);
}
