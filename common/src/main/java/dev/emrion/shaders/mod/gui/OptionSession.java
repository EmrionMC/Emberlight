package dev.emrion.shaders.mod.gui;

import dev.emrion.shaders.converter.options.Profiles;
import dev.emrion.shaders.format.Manifest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * State shared by all screens of one options-GUI visit: the applied values (from the manifest), the pending edits and
 * the pack's lang entries (game language, then en_us, then the raw name; as Iris gui/element/widget).
 */
public final class OptionSession {
	public final Manifest.Options options;
	public final Map<String, String> applied;
	public final Map<String, String> pending;
	public final Map<String, Manifest.OptionDecl> decls = new LinkedHashMap<>();
	private final Map<String, String> lang;
	private final Set<String> sliders;

	/** @param lang language code (lowercase, e.g. "de_de") → entries */
	public OptionSession(Manifest.Options options, Map<String, Map<String, String>> lang, String language) {
		this.options = options;
		this.applied = new HashMap<>(options.values());
		this.pending = new HashMap<>(options.values());
		options.declared().forEach(d -> decls.put(d.name(), d));
		Map<String, String> l = new HashMap<>(lang.getOrDefault("en_us", Map.of()));
		l.putAll(lang.getOrDefault(language, Map.of()));
		this.lang = l;
		this.sliders = options.sliders() == null ? Set.of() : new HashSet<>(options.sliders());
	}

	public @Nullable String lang(String key) {
		return lang.get(key);
	}

	public String lang(String key, String fallback) {
		return lang.getOrDefault(key, fallback);
	}

	public boolean slider(String name) {
		return sliders.contains(name);
	}

	/** Elements of a screen ("" = main). "*" is expanded to every option no screen lists (Iris OptionMenuContainer). */
	public List<String> screen(String id) {
		Map<String, List<String>> screens = options.screens() == null ? Map.of() : options.screens();
		List<String> raw = screens.getOrDefault(id, id.isEmpty() ? List.of("*") : List.of());
		if (!raw.contains("*")) return raw;
		Set<String> used = new HashSet<>();
		screens.values().forEach(used::addAll);
		List<String> out = new java.util.ArrayList<>();
		for (String e : raw) {
			if (!e.equals("*")) out.add(e);
			else decls.keySet().stream().filter(n -> !used.contains(n)).forEach(out::add);
		}
		return out;
	}

	public int columns(String id) {
		return options.columns() == null ? 2 : options.columns().getOrDefault(id, 2);
	}

	public boolean hasScreen(String id) {
		return options.screens() != null && options.screens().containsKey(id);
	}

	/** Detected profile of the pending values, null = Custom. */
	public @Nullable String profile() {
		return options.profiles() == null ? null : Profiles.detect(options.profiles(), pending);
	}

	public boolean modified() {
		return !pending.equals(applied);
	}
}
