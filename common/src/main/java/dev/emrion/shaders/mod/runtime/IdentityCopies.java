package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.format.Manifest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pairs of consecutive fullscreen passes that copy a colortex main → alt and straight back (Complementary's composite2/3):
 * both are skipped if alt is overwritten before it is read again. The copy is recognised in the fragment SPIR-V.
 */
final class IdentityCopies {
	private IdentityCopies() {}

	/** A recognised copy: set-1 binding read, output location written, alpha taken over (false: constant 1.0). */
	record Copy(int binding, int location, boolean alpha) {}

	/** Indices of the frame commands to skip. */
	static java.util.Set<Integer> skippable(Manifest.Dimension dim, Function<String, byte[]> fragmentSpv, Predicate<String> mayDiscard) {
		java.util.Set<Integer> out = new java.util.TreeSet<>();
		var frame = dim.frame();
		for (int i = 0; i + 1 < frame.size(); i++) {
			String a = copied(dim, frame.get(i), fragmentSpv, mayDiscard), b = copied(dim, frame.get(i + 1), fragmentSpv, mayDiscard);
			if (a == null || !a.equals(b)) continue;
			// main -> alt, then alt -> main
			if (!"alt".equals(frame.get(i).writes().get(0).side()) || !"main".equals(frame.get(i + 1).writes().get(0).side())) continue;
			var after = FlipSwap.altFate(dim, a, mayDiscard, i + 2, frame.size());
			boolean dead = after == FlipSwap.Fate.OVERWRITTEN
					|| after == FlipSwap.Fate.UNTOUCHED && FlipSwap.altFate(dim, a, mayDiscard, 0, i) == FlipSwap.Fate.OVERWRITTEN;
			if (dead) {
				out.add(i);
				out.add(i + 1);
				i++;
			}
		}
		return out;
	}

	/** The colortex a fullscreen command copies between its sides (reads one side, writes the other), or null. */
	private static String copied(Manifest.Dimension dim, Manifest.Command c, Function<String, byte[]> fragmentSpv, Predicate<String> mayDiscard) {
		if (!"fullscreen".equals(c.cmd()) || c.writes() == null || c.writes().size() != 1) return null;
		var p = dim.programs().get(c.program());
		if (p.viewportScale() != 1f || p.viewportOffset() != null || !p.writesStorage().isEmpty()) return null;
		if (mayDiscard.test(c.program())) return null; // a partial copy pair changes the image (e.g. a moved quad)
		var mode = p.blend() == null ? null : p.blend().defaultMode();
		if (mode != null && !"off".equals(mode.mode()) || p.blend() != null && !p.blend().perTarget().isEmpty()) return null;
		Copy copy = match(fragmentSpv.apply(c.program()));
		var w = c.writes().get(0);
		if (copy == null || copy.location() != w.location() || !w.target().startsWith("colortex")) return null; // shadowcomp flips are not tracked here
		var res = dim.resources().stream().filter(r -> r.binding() == copy.binding()).findFirst().orElse(null);
		if (res == null || !"sampled_image".equals(res.kind()) || !w.target().equals(res.target())) return null;
		var t = dim.targets().stream().filter(x -> x.name().equals(w.target())).findFirst().orElse(null);
		if (t == null || !t.hasAlt() || !copy.alpha() && !"R11F_G11F_B10F".equals(t.format())) return null;
		int n = Integer.parseInt(w.target().substring("colortex".length()));
		boolean readsAlt = dim.flipStates().stream().filter(f -> f.id() == c.flipState()).findFirst().orElseThrow().alt().contains(n);
		return readsAlt == "alt".equals(w.side()) ? null : w.target();
	}

	// SPIR-V opcodes
	private static final int ENTRY_POINT = 15, DECORATE = 71, FUNCTION = 54, FUNCTION_END = 56, LABEL = 248, RETURN = 253, LOAD = 61,
			STORE = 62, SHUFFLE = 79, F_TO_S = 110, IMAGE = 100, FETCH = 95, EXTRACT = 81, CONSTRUCT = 80, CONSTANT = 43, VARIABLE = 59;
	private static final int BUILTIN = 11, LOCATION = 30, BINDING = 33, SET = 34, FRAG_COORD = 15;

	/** The copy a fragment module performs, or null if its entry point does anything else. */
	static Copy match(byte[] spv) {
		if (spv == null || spv.length < 20) return null;
		IntBuffer w = ByteBuffer.wrap(spv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		Map<Integer, int[]> defs = new HashMap<>(); // result id -> instruction words (constants, body instructions)
		Map<Integer, Integer> binding = new HashMap<>(), set = new HashMap<>(), location = new HashMap<>();
		java.util.Set<Integer> fragCoord = new java.util.HashSet<>();
		int entry = -1, store = -1, storeTarget = -1;
		boolean inEntry = false;
		for (int i = 5; i < w.limit(); ) {
			int op = w.get(i) & 0xFFFF, len = w.get(i) >>> 16;
			if (len == 0 || i + len > w.limit()) return null;
			int[] ins = new int[len];
			w.get(i, ins);
			i += len;
			switch (op) {
				case ENTRY_POINT -> {
					if (ins[1] != 4 || entry >= 0) return null; // Fragment execution model, one entry point
					entry = ins[2];
				}
				case DECORATE -> {
					if (ins[2] == BUILTIN && ins[3] == FRAG_COORD) fragCoord.add(ins[1]);
					else if (ins[2] == BINDING) binding.put(ins[1], ins[3]);
					else if (ins[2] == SET) set.put(ins[1], ins[3]);
					else if (ins[2] == LOCATION) location.put(ins[1], ins[3]);
				}
				case CONSTANT -> defs.put(ins[2], ins);
				case FUNCTION -> inEntry = ins[2] == entry;
				case FUNCTION_END -> inEntry = false;
				default -> {
					if (!inEntry || op == LABEL || op == RETURN) continue;
					switch (op) {
						case LOAD, SHUFFLE, F_TO_S, IMAGE, FETCH, EXTRACT, CONSTRUCT -> defs.put(ins[2], ins);
						case STORE -> {
							if (store >= 0) return null;
							storeTarget = ins[1];
							store = ins[2];
						}
						default -> { return null; } // anything else: not a plain copy
					}
				}
			}
		}
		if (store < 0 || !location.containsKey(storeTarget)) return null;
		int[] value = defs.get(store);
		int fetch;
		boolean alpha;
		if (value != null && value[0] >>> 16 == 7 && (value[0] & 0xFFFF) == CONSTRUCT) {
			fetch = -1;
			for (int k = 0; k < 3; k++) {
				int f = component(defs, value[3 + k], k);
				if (f < 0 || fetch >= 0 && f != fetch) return null;
				fetch = f;
			}
			int[] a = defs.get(value[6]);
			if (component(defs, value[6], 3) == fetch) alpha = true;
			else if (a != null && (a[0] & 0xFFFF) == CONSTANT && a.length == 4 && Float.intBitsToFloat(a[3]) == 1f) alpha = false;
			else return null;
		} else {
			fetch = store;
			alpha = true;
		}
		int[] f = defs.get(fetch);
		// OpImageFetch type id image coord Lod(0x2) lod
		if (f == null || (f[0] & 0xFFFF) != FETCH || f.length != 7 || f[5] != 2 || !isIntZero(defs.get(f[6]))) return null;
		int[] img = defs.get(f[3]), coord = defs.get(f[4]);
		if (img == null || (img[0] & 0xFFFF) != IMAGE || coord == null || (coord[0] & 0xFFFF) != F_TO_S) return null;
		int[] load = defs.get(img[3]);
		if (load == null || (load[0] & 0xFFFF) != LOAD || set.getOrDefault(load[3], -1) != 1 || !binding.containsKey(load[3])) return null;
		int[] sh = defs.get(coord[3]);
		if (sh == null || (sh[0] & 0xFFFF) != SHUFFLE || sh.length != 7 || sh[3] != sh[4] || sh[5] != 0 || sh[6] != 1) return null;
		int[] fc = defs.get(sh[3]);
		if (fc == null || (fc[0] & 0xFFFF) != LOAD || !fragCoord.contains(fc[3])) return null;
		return new Copy(binding.get(load[3]), location.get(storeTarget), alpha);
	}

	/** The fetch id if {@code id} is OpCompositeExtract of component {@code k} of it, else -1. */
	private static int component(Map<Integer, int[]> defs, int id, int k) {
		int[] e = defs.get(id);
		return e != null && (e[0] & 0xFFFF) == EXTRACT && e.length == 5 && e[4] == k ? e[3] : -1;
	}

	private static boolean isIntZero(int[] c) {
		return c != null && (c[0] & 0xFFFF) == CONSTANT && c.length == 4 && c[3] == 0;
	}
}
