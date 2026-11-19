package dev.emrion.shaders.mod.runtime;

import dev.emrion.shaders.format.Manifest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-of-frame {@code copy colortexN alt → main} commands (Iris un-flips every buffer) that can be replaced by swapping the
 * images: safe when the first access to the alt side each frame is a full overwrite. Targets with mips or storage-image
 * bindings are never swapped.
 */
final class FlipSwap {
	private static final Pattern COLORTEX = Pattern.compile("colortex(\\d+)");

	private FlipSwap() {}

	/**
	 * @param mayDiscard program name → it may leave pixels unwritten: its fragment stage can discard (or demote) a fragment, or
	 *                   its vertex stage is not proven to cover the viewport ({@link FullscreenCoverage})
	 */
	static Set<String> swappable(Manifest.Dimension dim, Predicate<String> mayDiscard, String exclude) {
		Set<String> out = new LinkedHashSet<>();
		List<Manifest.Command> frame = dim.frame();
		for (int i = 0; i < frame.size(); i++) {
			var c = frame.get(i);
			if (!c.cmd().equals("copy") || !"alt".equals(c.from()) || !"main".equals(c.to()) || c.target().equals(exclude)) continue;
			// only trailing copies: nothing but other copies may follow
			if (frame.subList(i, frame.size()).stream().anyMatch(f -> !f.cmd().equals("copy"))) continue;
			if (safe(dim, c.target(), mayDiscard)) out.add(c.target());
		}
		return out;
	}

	private static boolean safe(Manifest.Dimension dim, String target, Predicate<String> mayDiscard) {
		var t = dim.targets().stream().filter(x -> x.name().equals(target)).findFirst().orElse(null);
		if (t == null || !t.hasAlt() || t.mipLevels() != 1) return false;
		return altFate(dim, target, mayDiscard, 0, dim.frame().size()) == Fate.OVERWRITTEN;
	}

	enum Fate { OVERWRITTEN, READ, UNTOUCHED }

	/** What happens first to the alt side of {@code target} in frame commands [from, to): full overwrite, read, or nothing. */
	static Fate altFate(Manifest.Dimension dim, String target, Predicate<String> mayDiscard, int from, int to) {
		Matcher m = COLORTEX.matcher(target);
		if (!m.matches()) return Fate.READ;
		int n = Integer.parseInt(m.group(1));
		Set<Integer> bindings = new java.util.HashSet<>();
		for (var r : dim.resources()) {
			if (!target.equals(r.target())) continue;
			if (!"sampled_image".equals(r.kind())) return Fate.READ;
			bindings.add(r.binding());
		}
		for (var c : dim.frame().subList(from, to)) {
			boolean altRead = c.flipState() != null && flip(dim, c.flipState()).alt().contains(n);
			switch (c.cmd()) {
				case "clear" -> {
					for (var op : c.targets()) if (op.name().equals(target) && !"main".equals(op.side())) return Fate.OVERWRITTEN;
				}
				case "geometry" -> {
					if (altRead) return Fate.READ;
				}
				case "mipmaps" -> {
					if (target.equals(c.target()) && "alt".equals(c.side())) return Fate.READ;
				}
				case "copy" -> {
					if (target.equals(c.target())) return Fate.READ;
				}
				case "copy_depth" -> {}
				case "compute", "fullscreen", "final" -> {
					if (c.program() == null) { // final without a program blits colortex0's read side
						if (altRead && n == 0) return Fate.READ;
						continue;
					}
					var p = dim.programs().get(c.program());
					if (altRead && p.set1().stream().anyMatch(bindings::contains)) return Fate.READ;
					if (c.writes() != null) for (var w : c.writes()) {
						if (!w.target().equals(target) || !"alt".equals(w.side())) continue;
						var mode = p.blend() == null ? null : p.blend().perTarget().getOrDefault(target, p.blend().defaultMode());
						boolean blends = mode != null && !"off".equals(mode.mode());
						// shortcut: assumes the shader writes the output on every path; check stores if a pack breaks this
						return c.cmd().equals("fullscreen") && !blends && p.viewportScale() == 1f && p.viewportOffset() == null
								&& !mayDiscard.test(c.program()) && sameSize(dim, target, c.writes()) ? Fate.OVERWRITTEN : Fate.READ;
					}
				}
				default -> { return Fate.READ; }
			}
		}
		return Fate.UNTOUCHED;
	}

	/** The render area is the attachments' intersection: a smaller one leaves the border of {@code target} unwritten. */
	private static boolean sameSize(Manifest.Dimension dim, String target, List<Manifest.Attachment> writes) {
		var t = target(dim, target);
		for (var w : writes) {
			var o = target(dim, w.target());
			if (o == null || !java.util.Arrays.equals(o.relativeSize(), t.relativeSize()) || !java.util.Arrays.equals(o.absoluteSize(), t.absoluteSize())) return false;
		}
		return true;
	}

	private static Manifest.Target target(Manifest.Dimension dim, String name) {
		return dim.targets().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
	}

	private static Manifest.FlipState flip(Manifest.Dimension dim, int id) {
		for (var f : dim.flipStates()) if (f.id() == id) return f;
		throw new IllegalArgumentException("unknown flip state " + id);
	}

	/** SPIR-V words of a fragment stage contain OpKill, OpTerminateInvocation or OpDemoteToHelperInvocation. */
	static boolean discards(byte[] spv) {
		var b = java.nio.ByteBuffer.wrap(spv).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		for (int i = 5; i < b.limit(); ) {
			int w = b.get(i), op = w & 0xFFFF, len = w >>> 16;
			if (op == 252 || op == 4416 || op == 5380) return true;
			if (len == 0) return true; // malformed: assume the worst
			i += len;
		}
		return false;
	}
}
