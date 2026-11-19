package dev.emrion.shaders.converter.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** {@code profile.NAME} entries of shaders.properties. Profiles are never saved; the active one is detected from the values. */
public record Profiles(Map<String, Map<String, String>> profiles, Map<String, List<String>> disabled) {

	/** @param tree raw "profile.NAME" lists (from the un-preprocessed shaders.properties), in file order */
	public static Profiles parse(Map<String, List<String>> tree, OptionSet options, Consumer<String> warnings) {
		Map<String, Map<String, String>> values = new LinkedHashMap<>();
		Map<String, List<String>> disabled = new LinkedHashMap<>();
		for (String name : tree.keySet()) {
			Map<String, String> v = new LinkedHashMap<>();
			List<String> d = new ArrayList<>();
			try {
				resolve(name, new ArrayList<>(), new java.util.HashSet<>(), tree, options, v, d, warnings);
			} catch (IllegalArgumentException e) {
				// Iris fails to load the pack here; we report and keep what was resolved.
				warnings.accept(e.getMessage());
			}
			values.put(name, v);
			disabled.put(name, d);
		}
		return new Profiles(values, disabled);
	}

	private static void resolve(String name, List<String> parents, java.util.Set<String> done, Map<String, List<String>> tree, OptionSet options,
	                            Map<String, String> values, List<String> disabled, Consumer<String> warnings) {
		List<String> entries = tree.get(name);
		if (entries == null) throw new IllegalArgumentException("Profile \"" + name + "\" does not exist");
		for (String e : entries) {
			if (e.startsWith("!program.")) {
				disabled.add(e.substring("!program.".length()));
			} else if (e.startsWith("profile.")) {
				String dep = e.substring("profile.".length());
				if (parents.contains(dep)) throw new IllegalArgumentException("Profile \"" + name + "\" includes itself via " + dep);
				if (done.contains(dep)) continue; // a shared base (diamond) applies once: not a cycle, and no exponential re-expansion
				List<String> path = new ArrayList<>(parents); // cycles are per branch
				path.add(dep);
				resolve(dep, path, done, tree, options, values, disabled, warnings);
				done.add(dep);
			} else if (e.startsWith("!")) {
				values.put(e.substring(1), "false");
			} else if (e.contains("=")) {
				values.put(e.substring(0, e.indexOf('=')), e.substring(e.indexOf('=') + 1));
			} else if (e.contains(":")) {
				values.put(e.substring(0, e.indexOf(':')), e.substring(e.indexOf(':') + 1));
			} else if (options.isBoolean(e)) {
				values.put(e, "true");
			} else {
				warnings.accept("Invalid option in profile " + name + ": " + e);
			}
		}
	}

	/** The first profile (most constraints first) whose every known option equals the current value; null = "Custom". */
	public String detect(OptionValues values) {
		return detect(profiles, values.resolved());
	}

	/** {@link #detect(OptionValues)} on resolved values; constraints on unknown names are ignored. */
	public static String detect(Map<String, Map<String, String>> profiles, Map<String, String> resolved) {
		List<String> sorted = new ArrayList<>(profiles.keySet());
		sorted.sort((a, b) -> Integer.compare(profiles.get(b).size(), profiles.get(a).size()));
		for (String name : sorted) {
			boolean match = true;
			for (var e : profiles.get(name).entrySet()) {
				String v = resolved.get(e.getKey());
				if (v != null && !v.equals(e.getValue())) match = false;
			}
			if (match) return name;
		}
		return null;
	}
}
