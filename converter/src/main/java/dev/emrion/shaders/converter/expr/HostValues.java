package dev.emrion.shaders.converter.expr;

import java.util.OptionalInt;

/**
 * What {@link CustomUniformRuntime} needs from the game, implemented by the mod. Names are Iris built-in names
 * ({@link UniformCatalog#expressionInputs()}); the runtime asks for each referenced built-in once per update, with
 * the getter matching its catalogue type. Values must be what Iris computes for that uniform (e.g. cameraPosition
 * with the 30000-block shift, eyeBrightness as (block·16, sky·16)). bool uniforms such as is_sneaking use getBool.
 * Not asked for: pi and the per-draw entityColor/blockEntityId/currentRenderedItemId, which Iris feeds into
 * expressions as the constants π, vec4(0), -1, -1.
 */
public interface HostValues {
	float getFloat(String name);

	int getInt(String name);

	boolean getBool(String name);

	/** vec2/vec3/vec4 into out[0..n), mat3/mat4 column-major into out[0..9/16). */
	void getFloats(String name, float[] out);

	/** ivec2/ivec3 into out. */
	void getInts(String name, int[] out);

	/**
	 * Numeric id Iris would give the BIOME_* macro {@code constantName} (e.g. "BIOME_PALE_GARDEN"), i.e. the value the
	 * {@code biome} uniform takes in that biome; empty if no such biome is registered. Asked once, at construction.
	 */
	OptionalInt biomeId(String constantName);
}
