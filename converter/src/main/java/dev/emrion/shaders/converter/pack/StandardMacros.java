package dev.emrion.shaders.converter.pack;

import java.util.Map;

/**
 * Iris standard macros (iris-spec §3.5) for a given environment. Owner: agent (a).
 * Values must match what Iris defines on the user's machine (NVIDIA, Linux, MC 26.3) so the pack takes the same branches.
 */
public final class StandardMacros {
	private StandardMacros() {}

	public record Environment(String mcVersion, String glVendor, String glRenderer, String os) {
		public static final Environment DEFAULT = new Environment("26.3", "NVIDIA Corporation", "NVIDIA GeForce RTX 4070 Ti", "linux");
	}

	public static Map<String, String> build(Environment env) {
		throw new UnsupportedOperationException("implemented by agent (a)");
	}
}
