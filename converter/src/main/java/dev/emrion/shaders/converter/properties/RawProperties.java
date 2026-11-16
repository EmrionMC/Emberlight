package dev.emrion.shaders.converter.properties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keys Iris reads from the raw, un-preprocessed shaders.properties: feature flags, sliders, profiles,
 * screens and columns. Preprocessor branches are therefore ignored for these; the last definition of a key wins.
 * Screen maps use "" for the main screen.
 */
public record RawProperties(List<String> requiredFeatures, List<String> optionalFeatures, List<String> sliders,
                            Map<String, List<String>> profiles, Map<String, List<String>> screens,
                            Map<String, Integer> columns) {

	public static RawProperties of(Map<String, String> raw) {
		List<String> required = List.of(), optional = List.of(), sliders = List.of();
		Map<String, List<String>> profiles = new LinkedHashMap<>();
		Map<String, List<String>> screens = new LinkedHashMap<>();
		Map<String, Integer> columns = new LinkedHashMap<>();
		for (var e : raw.entrySet()) {
			String key = e.getKey(), value = e.getValue();
			switch (key) {
				case "iris.features.required" -> required = list(value);
				case "iris.features.optional" -> optional = list(value);
				case "sliders" -> sliders = list(value);
				case "screen" -> screens.put("", list(value));
				case "screen.columns" -> {
					Integer c = parseInt(value);
					if (c != null) columns.put("", c);
				}
				default -> {
					if (key.startsWith("profile.")) profiles.put(key.substring(8), list(value));
					if (key.startsWith("screen.") && key.endsWith(".columns") && key.length() > 15) {
						Integer c = parseInt(value);
						if (c != null) columns.put(key.substring(7, key.length() - 8), c);
					} else if (key.startsWith("screen.")) {
						screens.put(key.substring(7), list(value));
					}
				}
			}
		}
		return new RawProperties(required, optional, sliders, profiles, screens, columns);
	}

	/** Iris splits on " +" only (not tabs); Properties already dropped leading whitespace. */
	static List<String> list(String value) {
		return Arrays.asList(value.split(" +"));
	}

	private static Integer parseInt(String v) {
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
