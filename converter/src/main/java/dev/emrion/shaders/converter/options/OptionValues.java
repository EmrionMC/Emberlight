package dev.emrion.shaders.converter.options;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Option values that differ from the defaults (Iris MutableOptionValues). Booleans accept exactly "true"/"false",
 * anything else means default. String values are not checked against the allowed list (OptiFine parity).
 */
public final class OptionValues {
	private final OptionSet options;
	private final Map<String, Boolean> booleans = new LinkedHashMap<>();
	private final Map<String, String> strings = new LinkedHashMap<>();

	public OptionValues(OptionSet options, Map<String, String> settings) {
		this.options = options;
		options.booleans().forEach((name, opt) -> {
			String v = settings.get(name);
			if (v == null || !(v.equals("true") || v.equals("false"))) return;
			boolean b = v.equals("true");
			if (b != Boolean.parseBoolean(opt.decl().defaultValue())) booleans.put(name, b);
		});
		options.strings().forEach((name, opt) -> {
			String v = settings.get(name);
			if (v != null && !v.equals(opt.decl().defaultValue())) strings.put(name, v);
		});
	}

	public OptionSet options() {
		return options;
	}

	/** Changed boolean value, or null for default. */
	public Boolean changedBoolean(String name) {
		return booleans.get(name);
	}

	/** Changed string value, or null for default. */
	public String changedString(String name) {
		return strings.get(name);
	}

	/**
	 * Iris getBooleanValueOrDefault: "0"/"1" are literals; an unknown name is true (Iris warns and defaults to true).
	 */
	public boolean booleanOrDefault(String name) {
		if (name.equals("0")) return false;
		if (name.equals("1")) return true;
		Boolean b = booleans.get(name);
		if (b != null) return b;
		OptionSet.Option opt = options.booleans().get(name);
		return opt == null || Boolean.parseBoolean(opt.decl().defaultValue());
	}

	public String stringOrDefault(String name) {
		String s = strings.get(name);
		if (s != null) return s;
		OptionSet.Option opt = options.strings().get(name);
		return opt == null ? null : opt.decl().defaultValue();
	}

	/** Every option with its effective value (booleans "true"/"false"), booleans first, discovery order. */
	public Map<String, String> resolved() {
		Map<String, String> m = new LinkedHashMap<>();
		options.booleans().keySet().forEach(n -> m.put(n, Boolean.toString(booleanOrDefault(n))));
		options.strings().keySet().forEach(n -> m.put(n, stringOrDefault(n)));
		return m;
	}

	/** Only the changed values, as Iris writes them to the settings file. */
	public Map<String, String> changed() {
		Map<String, String> m = new LinkedHashMap<>();
		booleans.forEach((k, v) -> m.put(k, Boolean.toString(v)));
		m.putAll(strings);
		return m;
	}

	public int changedCount() {
		return booleans.size() + strings.size();
	}

	/** Keys of {@code settings} that are neither a boolean nor a string option. */
	public static List<String> unknownKeys(OptionSet options, Map<String, String> settings) {
		List<String> out = new ArrayList<>();
		for (String k : settings.keySet()) if (!options.booleans().containsKey(k) && !options.strings().containsKey(k)) out.add(k);
		return out;
	}
}
