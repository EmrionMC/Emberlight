package dev.emrion.shaders.mod.pipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rewrites set-0 {@code Binding} decorations in SPIR-V from placeholder indices (into {@code placeholders}, normally
 * {@code VertexLayouts.SET0_CANONICAL}) to the index of that name in the replaced vanilla pipeline's {@code uniforms()}
 * list, which is the binding MC pushes it at (FORMAT.md §7.1). Parses OpDecorate itself; no spvc needed.
 */
public final class Set0Patcher {
	private static final int MAGIC = 0x07230203;
	private static final int OP_DECORATE = 71;
	private static final int DECORATION_BINDING = 33;
	private static final int DECORATION_DESCRIPTOR_SET = 34;

	private Set0Patcher() {}

	/** Patches {@code spv} in place. Throws if the shader uses a set-0 resource the vanilla pipeline does not push. */
	public static void patch(byte[] spv, List<String> placeholders, List<String> uniformOrder) {
		IntBuffer words = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		if (words.get(0) != MAGIC) throw new IllegalArgumentException("not little-endian SPIR-V");
		Set<Integer> set0 = new HashSet<>();
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 5; i < words.limit(); ) {
				int op = words.get(i) & 0xFFFF, count = words.get(i) >>> 16;
				if (count == 0) throw new IllegalArgumentException("malformed SPIR-V at word " + i);
				if (op == OP_DECORATE && count >= 4) {
					int target = words.get(i + 1), decoration = words.get(i + 2), value = words.get(i + 3);
					if (pass == 0 && decoration == DECORATION_DESCRIPTOR_SET && value == 0) set0.add(target);
					if (pass == 1 && decoration == DECORATION_BINDING && set0.contains(target)) {
						if (value < 0 || value >= placeholders.size()) throw new IllegalArgumentException("set-0 placeholder binding " + value + " out of range");
						String name = placeholders.get(value);
						int index = uniformOrder.indexOf(name);
						if (index < 0) throw new IllegalArgumentException("vanilla pipeline does not provide " + name + " (has " + uniformOrder + ")");
						words.put(i + 3, index);
					}
				}
				i += count;
			}
		}
	}
}
