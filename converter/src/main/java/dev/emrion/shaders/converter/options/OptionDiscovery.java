package dev.emrion.shaders.converter.options;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Finds options in all pack sources that Iris loads (every file reachable by #include from a program file), with Iris's
 * rules (iris-spec §3.3, Iris ShaderPackOptions / OptionSet):
 * <ul>
 *   <li>a boolean option ({@code #define} or {@code const bool}) only counts if its name appears in a bare {@code #ifdef NAME}/{@code #ifndef NAME}
 *       line anywhere in the pack (Iris's include graph is one component, so references are pack-wide);</li>
 *   <li>the same name declared again merges if the default is identical; a different default drops the option
 *       ("ambiguous"), and a later declaration starts it again;</li>
 *   <li>when merging, the first declaration with a comment provides the comment.</li>
 * </ul>
 * Files are visited in sorted path order (Iris uses hash order; only the tie-breaking of conflicts depends on it).
 */
public final class OptionDiscovery {
	private OptionDiscovery() {}

	/** @param files absolute pack path → lines of every source file Iris reads */
	public static OptionSet discover(Map<String, List<String>> files, Consumer<String> warnings) {
		Map<String, Map<Integer, OptionLine>> lines = new TreeMap<>();
		Set<String> references = new HashSet<>();
		for (var e : new TreeMap<>(files).entrySet()) {
			Map<Integer, OptionLine> perFile = new TreeMap<>();
			List<String> src = e.getValue();
			for (int i = 0; i < src.size(); i++) {
				OptionLineParser.Parsed p = OptionLineParser.parse(src.get(i));
				if (p.reference() != null) references.add(p.reference());
				if (p.option() != null) perFile.put(i, p.option());
			}
			if (!perFile.isEmpty()) lines.put(e.getKey(), perFile);
		}
		Map<String, OptionSet.Option> booleans = new LinkedHashMap<>();
		Map<String, OptionSet.Option> strings = new LinkedHashMap<>();
		lines.forEach((file, perFile) -> perFile.forEach((index, opt) -> {
			// Iris quirk: this also applies to const bool options, they need an #ifdef reference as well.
			if (opt.isBoolean() && !references.contains(opt.name())) return;
			merge(opt.isBoolean() ? booleans : strings, opt, new OptionSet.Location(file, index), warnings);
		}));
		return new OptionSet(booleans, strings, lines);
	}

	private static void merge(Map<String, OptionSet.Option> into, OptionLine opt, OptionSet.Location loc, Consumer<String> warnings) {
		OptionSet.Option existing = into.get(opt.name());
		if (existing == null) {
			into.put(opt.name(), new OptionSet.Option(opt, List.of(loc)));
			return;
		}
		if (!existing.decl().defaultValue().equals(opt.defaultValue())) {
			warnings.accept("Ignoring ambiguous option " + opt.name() + " (defaults " + existing.decl().defaultValue()
					+ " and " + opt.defaultValue() + ", " + loc.file() + ":" + (loc.line() + 1) + ")");
			into.remove(opt.name());
			return;
		}
		List<OptionSet.Location> locs = new ArrayList<>(existing.locations());
		locs.add(loc);
		OptionLine decl = existing.decl().comment() != null ? existing.decl() : opt;
		into.put(opt.name(), new OptionSet.Option(decl, List.copyOf(locs)));
	}

	/** Lines per file as Iris splits them (any line terminator; trailing empty lines dropped like String.split). */
	public static List<String> splitLines(String source) {
		return List.of(source.split("\\R"));
	}
}
