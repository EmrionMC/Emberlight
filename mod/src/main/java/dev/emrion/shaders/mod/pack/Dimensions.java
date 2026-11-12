package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.format.Manifest;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class Dimensions {
	private Dimensions() {}

	/**
	 * Pack folder for a dimension id: an exact {@code dimensionMap} match first, then the first glob ({@code *} = any run) in
	 * order. Exact first because Complementary's map starts with {@code *:*} → world0, which would otherwise catch the Nether.
	 * Null = the pack has no plan there (vanilla rendering).
	 */
	static @Nullable String folder(Manifest m, String dimension) {
		for (int pass = 0; pass < 2; pass++) {
			for (var d : m.dimensionMap()) {
				boolean hit = pass == 0 ? d.match().equals(dimension) : dimension.matches(Pattern.quote(d.match()).replace("*", "\\E.*\\Q"));
				if (hit && m.dimensions().containsKey(d.folder())) return d.folder();
			}
		}
		return null;
	}
}
