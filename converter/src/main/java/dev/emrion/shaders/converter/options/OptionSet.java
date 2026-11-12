package dev.emrion.shaders.converter.options;

import java.util.List;
import java.util.Map;

/**
 * Options discovered in a pack (iris-spec §3.3).
 *
 * @param booleans boolean options by name (merged across files), in discovery order
 * @param strings  string (value) options by name
 * @param lines    absolute pack path → 0-based line index → option declared on that line. Contains every parsed option
 *                 line, also ambiguous or unreferenced ones: Iris edits all of them when applying values.
 */
public record OptionSet(Map<String, Option> booleans, Map<String, Option> strings,
                        Map<String, Map<Integer, OptionLine>> lines) {

	/** A merged option: the declaration whose comment wins, and every place it is declared (file, 0-based line). */
	public record Option(OptionLine decl, List<Location> locations) {}

	public record Location(String file, int line) {}

	public boolean isBoolean(String name) {
		return booleans.containsKey(name);
	}
}
