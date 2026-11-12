package dev.emrion.shaders.converter.glsl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Textual {@code #include} expansion with Iris semantics (iris-spec §3.1, Iris IncludeGraph/IncludeProcessor):
 * any line whose trimmed text starts with {@code #include} is replaced by the target file, even inside comments or
 * inactive {@code #if} blocks, and there is no include-once: a file included twice is inlined twice (packs use
 * {@code #ifndef} guards). Paths are pack paths rooted at {@code shaders/}: {@code "/lib/x.glsl"} is absolute,
 * anything else is relative to the including file's folder.
 */
public final class IncludeExpander {
	private IncludeExpander() {}

	/** Include-expanded text plus, for each 1-based line, the original file (absolute pack path) and line. */
	public record Expanded(String text, List<String> files, int[] fileOfLine, int[] lineOfLine, List<Error> errors) {
		public int lineCount() {
			return fileOfLine.length;
		}
	}

	/** file is the absolute pack path of the including file (null for the start file), line is 1-based. */
	public record Error(String file, int line, String message) {}

	/** @param files absolute pack path ("/lib/x.glsl") → lines, or null if the file does not exist */
	public static Expanded expand(String startPath, Function<String, List<String>> files) {
		State st = new State(files);
		st.inline(normalize(startPath), new ArrayDeque<>());
		int n = st.fileIdx.size();
		int[] f = new int[n], l = new int[n];
		for (int i = 0; i < n; i++) {
			f[i] = st.fileIdx.get(i);
			l[i] = st.lineNo.get(i);
		}
		return new Expanded(st.text.toString(), List.copyOf(st.fileNames), f, l, List.copyOf(st.errors));
	}

	/** Iris AbsolutePackPath.resolve: absolute or relative to {@code dir}, with "." and ".." collapsed. */
	public static String resolve(String dir, String target) {
		return normalize(target.startsWith("/") ? target : dir + "/" + target);
	}

	public static String normalize(String path) {
		Deque<String> parts = new ArrayDeque<>();
		for (String seg : path.split("/")) {
			if (seg.isEmpty() || seg.equals(".")) continue;
			if (seg.equals("..")) parts.pollLast();
			else parts.addLast(seg);
		}
		return "/" + String.join("/", parts);
	}

	/** Returns the include target of a line, or null if the line is not an include. Throws for a bare "#include". */
	public static String includeTarget(String line) {
		String t = line.trim();
		if (!t.startsWith("#include")) return null;
		if (t.length() < "#include ".length()) throw new IllegalArgumentException("#include without a path");
		String target = t.substring("#include ".length()).trim();
		if (target.startsWith("\"")) target = target.substring(1);
		if (target.endsWith("\"")) target = target.substring(0, target.length() - 1);
		return target;
	}

	private static final class State {
		final Function<String, List<String>> files;
		final StringBuilder text = new StringBuilder();
		final List<String> fileNames = new ArrayList<>();
		final Map<String, Integer> fileIds = new HashMap<>();
		final List<Integer> fileIdx = new ArrayList<>();
		final List<Integer> lineNo = new ArrayList<>();
		final List<Error> errors = new ArrayList<>();

		State(Function<String, List<String>> files) {
			this.files = files;
		}

		void inline(String path, Deque<String> stack) {
			List<String> lines = files.apply(path);
			if (lines == null) {
				errors.add(new Error(null, 0, "file not found: " + path));
				return;
			}
			int id = fileIds.computeIfAbsent(path, p -> {
				fileNames.add(p);
				return fileNames.size() - 1;
			});
			stack.push(path);
			String dir = path.substring(0, path.lastIndexOf('/'));
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String target;
				try {
					target = includeTarget(line);
				} catch (IllegalArgumentException e) {
					errors.add(new Error(path, i + 1, e.getMessage()));
					continue;
				}
				if (target == null) {
					text.append(line).append('\n');
					fileIdx.add(id);
					lineNo.add(i + 1);
					continue;
				}
				String resolved = resolve(dir, target);
				if (stack.contains(resolved)) {
					errors.add(new Error(path, i + 1, "#include cycle via " + resolved));
					continue;
				}
				if (files.apply(resolved) == null) {
					errors.add(new Error(path, i + 1, "#include file not found: " + resolved));
					continue;
				}
				inline(resolved, stack);
			}
			stack.pop();
		}
	}
}
