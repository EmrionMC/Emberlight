package dev.emrion.shaders.converter.expr;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/** Test host: values by name, 0 when unset. */
final class MapHost implements HostValues {
	final Map<String, Float> floats = new HashMap<>();
	final Map<String, Integer> ints = new HashMap<>();
	final Map<String, Boolean> bools = new HashMap<>();
	final Map<String, float[]> vecs = new HashMap<>();
	final Map<String, int[]> ivecs = new HashMap<>();
	final Map<String, Integer> biomes = new HashMap<>();

	@Override public float getFloat(String name) { return floats.getOrDefault(name, 0f); }

	@Override public int getInt(String name) { return ints.getOrDefault(name, 0); }

	@Override public boolean getBool(String name) { return bools.getOrDefault(name, false); }

	@Override public void getFloats(String name, float[] out) {
		float[] v = vecs.get(name);
		for (int i = 0; i < out.length; i++) out[i] = v == null ? 0 : v[i];
	}

	@Override public void getInts(String name, int[] out) {
		int[] v = ivecs.get(name);
		for (int i = 0; i < out.length; i++) out[i] = v == null ? 0 : v[i];
	}

	@Override public OptionalInt biomeId(String constantName) {
		Integer id = biomes.get(constantName);
		return id == null ? OptionalInt.empty() : OptionalInt.of(id);
	}

	/** Iris SmoothFloat written out independently, to hand-check sequences. */
	static final class RefSmooth {
		float acc;
		boolean init;

		float next(float v, float fadeUp, float fadeDown, float dt) {
			if (!init) { init = true; acc = v; return acc; }
			float fade = v > acc ? fadeUp : fadeDown;
			if (fade == 0f) { acc = v; return acc; }
			float k = (float) (Math.log(2) / (fade * 0.1f)); // half-life = fade·0.1 s
			float alpha = 1f - (float) Math.exp(-k * dt);
			acc = (1 - alpha) * acc + alpha * v;
			return acc;
		}
	}
}
