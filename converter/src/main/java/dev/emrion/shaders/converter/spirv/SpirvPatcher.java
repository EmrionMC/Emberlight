package dev.emrion.shaders.converter.spirv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Dependency-free SPIR-V word scanner for the set-0 placeholder bindings (FORMAT.md §7.1). The mod reuses it at
 * pipeline creation to rewrite each set-0 {@code Binding} word, so it must not depend on lwjgl or the converter.
 */
public final class SpirvPatcher {
	private SpirvPatcher() {}

	private static final int MAGIC = 0x07230203;
	private static final int OP_DECORATE = 71;
	private static final int OP_FUNCTION = 54;
	private static final int DECORATION_BINDING = 33;
	private static final int DECORATION_DESCRIPTOR_SET = 34;

	/** bindingWordIndex: index of the Binding literal in the module's word stream (patch target). */
	public record Set0Binding(int id, int binding, int bindingWordIndex) {}

	/** Little-endian view of a SPIR-V byte array, as shaderc emits it. */
	public static IntBuffer words(byte[] spv) {
		return ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
	}

	/** Every variable decorated with DescriptorSet 0 and a Binding, in Binding-decoration order. */
	public static List<Set0Binding> set0Bindings(IntBuffer words) {
		if (words.limit() < 5 || words.get(0) != MAGIC) throw new IllegalArgumentException("not a little-endian SPIR-V module");
		Map<Integer, Integer> sets = new HashMap<>();
		List<int[]> bindings = new ArrayList<>(); // {id, binding, wordIndex}
		int i = 5;
		while (i < words.limit()) {
			int word = words.get(i);
			int count = word >>> 16, op = word & 0xFFFF;
			if (count == 0 || i + count > words.limit()) throw new IllegalArgumentException("malformed instruction at word " + i);
			if (op == OP_FUNCTION) break; // annotations precede all function definitions
			if (op == OP_DECORATE && count >= 4) {
				int id = words.get(i + 1), decoration = words.get(i + 2);
				if (decoration == DECORATION_DESCRIPTOR_SET) sets.put(id, words.get(i + 3));
				else if (decoration == DECORATION_BINDING) bindings.add(new int[]{id, words.get(i + 3), i + 3});
			}
			i += count;
		}
		List<Set0Binding> out = new ArrayList<>();
		for (int[] b : bindings)
			if (sets.getOrDefault(b[0], -1) == 0) out.add(new Set0Binding(b[0], b[1], b[2]));
		return out;
	}

	/** Rewrites every set-0 Binding in place: new binding = remap(placeholder binding). */
	public static void patchSet0(IntBuffer words, IntUnaryOperator remap) {
		for (Set0Binding b : set0Bindings(words)) words.put(b.bindingWordIndex(), remap.applyAsInt(b.binding()));
	}
}
