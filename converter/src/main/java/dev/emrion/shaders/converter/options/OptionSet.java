package dev.emrion.shaders.converter.options;

import java.util.List;
import java.util.Map;

/** Options discovered in a pack. {@code lines} holds every parsed option line, even ambiguous ones: Iris edits all of them. */
public record OptionSet(Map<String, Option> booleans, Map<String, Option> strings,
                        Map<String, Map<Integer, OptionLine>> lines) {

	/** A merged option: the declaration whose comment wins, and every place it is declared (file, 0-based line). */
	public record Option(OptionLine decl, List<Location> locations) {}

	public record Location(String file, int line) {}

	public boolean isBoolean(String name) {
		return booleans.containsKey(name);
	}
}
