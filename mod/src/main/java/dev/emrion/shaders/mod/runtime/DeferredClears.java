package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.format.Manifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Moves the frame-start clears of raw images (voxel volumes and other custom images) behind the image's last use in the
 * frame: from the second frame on they are cleared right after that use in the previous frame. Nothing touches the image in
 * between, so every access sees the same zeroed contents, but the clear (≈0.4 ms for the 160 MB of voxel images of the
 * target pack) no longer serialises the frame start: it runs inside the composite command buffer, where precise barriers
 * let it overlap the following passes. It goes after the first shader pass behind any transfers (mipmaps, copies) that
 * follow the last use, because that pass's barrier waits for the transfer stage and so would wait for the clear too. An image no command ever touches needs no clear after the first frame.
 * Only images a clear command zeroes before any access qualify, and only when no other clear in the frame touches them.
 */
record DeferredClears(Map<Manifest.Command, List<Manifest.ClearOp>> start, List<Manifest.ClearOp>[] before) {
	private static final Set<String> TRANSFERS = Set.of("mipmaps", "copy", "copy_depth", "clear");

	/** {@code deferrable}: target name → true for a single-sided raw image. */
	DeferredClears(Manifest.Dimension dim, Predicate<String> deferrable) {
		this(new IdentityHashMap<>(), newArray(dim.frame().size() + 1));
		var frame = dim.frame();
		Set<String> rawPrograms = new HashSet<>();
		for (var c : frame) if (c.program() != null) rawPrograms.add(c.program());
		for (int c = 0; c < frame.size() && !frame.get(c).cmd().equals("geometry"); c++) {
			if (!frame.get(c).cmd().equals("clear")) continue;
			List<Manifest.ClearOp> kept = new ArrayList<>();
			for (var op : frame.get(c).targets()) {
				int last = deferrable.test(op.name()) ? lastAccess(dim, op.name(), c, rawPrograms) : -2;
				if (last == -2) {
					kept.add(op);
					continue;
				}
				if (last == -1) continue; // never accessed: the once clear (or this clear in the first frame) is enough
				int at = last + 1;
				while (at < frame.size() && frame.get(at).cmd().equals("geometry")) at++; // after MC's pass: next raw command buffer
				// a shader pass after transfers (mipmaps, copies) waits for every earlier transfer: clear after that pass instead
				if (at < frame.size() && TRANSFERS.contains(frame.get(at).cmd())) {
					while (at < frame.size() && TRANSFERS.contains(frame.get(at).cmd())) at++;
					if (at < frame.size() && !frame.get(at).cmd().equals("geometry")) at++;
				}
				if (before[at] == null) before[at] = new ArrayList<>();
				before[at].add(op);
			}
			start.put(frame.get(c), kept);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Manifest.ClearOp>[] newArray(int n) {
		return new List[n];
	}

	/** Index of the last frame command touching {@code target} after the clear at {@code clear}; -1 none; -2 not deferrable. */
	private static int lastAccess(Manifest.Dimension dim, String target, int clear, Set<String> rawPrograms) {
		int last = -1;
		for (int i = 0; i < dim.frame().size(); i++) {
			if (i == clear || !accesses(dim, dim.frame().get(i), target, rawPrograms)) continue;
			if (i < clear || dim.frame().get(i).cmd().equals("clear")) return -2;
			last = i;
		}
		return last;
	}

	private static boolean accesses(Manifest.Dimension dim, Manifest.Command c, String target, Set<String> rawPrograms) {
		return switch (c.cmd()) {
			case "clear" -> c.targets().stream().anyMatch(op -> op.name().equals(target));
			case "mipmaps", "copy" -> target.equals(c.target());
			case "copy_depth" -> target.equals(c.from()) || target.equals(c.to());
			case "fullscreen", "compute", "final" -> c.program() != null && binds(dim, dim.programs().get(c.program()), target);
			// MC draws with any gbuffers/shadow program: conservatively every program no raw command runs
			case "geometry" -> dim.programs().entrySet().stream().anyMatch(e -> !rawPrograms.contains(e.getKey()) && binds(dim, e.getValue(), target));
			default -> true;
		};
	}

	private static boolean binds(Manifest.Dimension dim, Manifest.Program p, String target) {
		for (int b : p.set1()) for (var r : dim.resources()) if (r.binding() == b && target.equals(r.target())) return true;
		return false;
	}
}
