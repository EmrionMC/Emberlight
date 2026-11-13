package dev.emrion.shaders.mod.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Rewrites the {@code Location} of fragment outputs in SPIR-V. All gbuffers programs drawn in one render pass share the
 * pass's attachment list (the union of every gbuffers output target), but a program's output {@code location=k} means
 * "its k-th draw buffer"; Vulkan writes location k to attachment k, so each output is moved to its target's attachment index.
 */
public final class OutputPatcher {
	private static final int MAGIC = 0x07230203;
	private static final int OP_VARIABLE = 59;
	private static final int OP_DECORATE = 71;
	private static final int DECORATION_LOCATION = 30;
	private static final int STORAGE_INPUT = 1, STORAGE_OUTPUT = 3;

	private OutputPatcher() {}

	/** Locations of the module's {@code Input} variables (vertex attributes for a vertex shader). */
	public static Set<Integer> inputLocations(byte[] spv) {
		IntBuffer words = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		Set<Integer> inputs = new HashSet<>(), locations = new HashSet<>();
		for (int i = 5; i < words.limit(); i += Math.max(1, words.get(i) >>> 16)) {
			int op = words.get(i) & 0xFFFF;
			if (op == OP_VARIABLE && (words.get(i) >>> 16) >= 4 && words.get(i + 3) == STORAGE_INPUT) inputs.add(words.get(i + 2));
		}
		for (int i = 5; i < words.limit(); i += Math.max(1, words.get(i) >>> 16)) {
			int op = words.get(i) & 0xFFFF;
			if (op == OP_DECORATE && words.get(i + 2) == DECORATION_LOCATION && inputs.contains(words.get(i + 1))) locations.add(words.get(i + 3));
		}
		return locations;
	}

	/** SPIR-V execution modes of a geometry shader's input primitive. */
	public static final int INPUT_POINTS = 19, INPUT_LINES = 20, INPUT_TRIANGLES = 22;

	/** The input-primitive execution mode of a geometry shader (InputPoints … InputTrianglesAdjacency), or -1. */
	public static int geometryInput(byte[] spv) {
		IntBuffer words = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		for (int i = 5; i < words.limit(); i += Math.max(1, words.get(i) >>> 16)) {
			if ((words.get(i) & 0xFFFF) == 16 && (words.get(i) >>> 16) >= 3) { // OpExecutionMode
				int mode = words.get(i + 2);
				if (mode >= 19 && mode <= 23) return mode;
			}
		}
		return -1;
	}

	/** Patches {@code spv} in place: output location {@code l} becomes {@code remap[l]}. */
	public static void patch(byte[] spv, int[] remap) {
		IntBuffer words = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		if (words.get(0) != MAGIC) throw new IllegalArgumentException("not little-endian SPIR-V");
		Set<Integer> outputs = new HashSet<>();
		for (int i = 5; i < words.limit(); ) {
			int op = words.get(i) & 0xFFFF, count = words.get(i) >>> 16;
			if (count == 0) throw new IllegalArgumentException("malformed SPIR-V at word " + i);
			if (op == OP_VARIABLE && count >= 4 && words.get(i + 3) == STORAGE_OUTPUT) outputs.add(words.get(i + 2));
			i += count;
		}
		for (int i = 5; i < words.limit(); ) {
			int op = words.get(i) & 0xFFFF, count = words.get(i) >>> 16;
			if (op == OP_DECORATE && count >= 4 && words.get(i + 2) == DECORATION_LOCATION && outputs.contains(words.get(i + 1))) {
				int loc = words.get(i + 3);
				if (loc < 0 || loc >= remap.length) throw new IllegalArgumentException("fragment output location " + loc + " has no target");
				words.put(i + 3, remap[loc]);
			}
			i += count;
		}
	}
}
