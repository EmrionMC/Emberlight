package dev.emrion.shaders.converter.properties;

import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;

/**
 * block/item/entity.properties (iris-spec §6.2, FORMAT.md §8) from their already-preprocessed text.
 *
 * <p>Iris precedence is encoded in list order so that "first matching entry wins" reproduces it:
 * all plain block entries (in key order) come before all {@code %tag} entries, because Iris assigns block states from
 * every block entry before it looks at any tag. Tags become {@code #ns:name[:k=v…]}. For items and entities Iris keeps
 * the last id per name, so a name appears only under its final id. Layers: last assignment wins, tags are rejected.
 */
public final class IdMapParser {
	private IdMapParser() {}

	/** Iris IdMap post-processing of the preprocessed text before Properties.load. */
	public static String fixup(String processed) {
		return processed.replaceAll("\\\\\\n\\s*\\n", " ").replaceAll("\\S *block\\.", "\nblock.");
	}

	public static Manifest.IdMaps parse(String blockText, String itemText, String entityText, Consumer<String> warnings) {
		List<Manifest.IdEntry> blocks = new ArrayList<>();
		Map<String, List<String>> layers = new LinkedHashMap<>();
		if (blockText != null) {
			SequencedMap<String, String> props = OrderedProperties.load(fixup(blockText));
			Map<Integer, List<String>> plain = new LinkedHashMap<>(), tags = new LinkedHashMap<>();
			props.forEach((key, value) -> {
				if (!key.startsWith("block.")) return;
				Integer id = parseId(key, "block.", warnings);
				if (id == null) return;
				List<String> p = new ArrayList<>(), t = new ArrayList<>();
				for (String part : value.trim().split("\\s+")) {
					if (part.isEmpty()) continue;
					if (part.startsWith("%")) t.add(tag(part));
					else p.add(block(part));
				}
				plain.put(id, p);
				tags.put(id, t);
			});
			plain.forEach((id, m) -> { if (!m.isEmpty()) blocks.add(new Manifest.IdEntry(id, List.copyOf(m))); });
			tags.forEach((id, m) -> { if (!m.isEmpty()) blocks.add(new Manifest.IdEntry(id, List.copyOf(m))); });
			Map<String, String> layerOf = new LinkedHashMap<>();
			props.forEach((key, value) -> {
				if (!key.startsWith("layer.")) return;
				String layer = key.substring(6);
				if (!List.of("solid", "cutout", "cutout_mipped", "translucent").contains(layer)) {
					warnings.accept("block.properties: invalid render layer " + key);
					return;
				}
				for (String part : value.trim().split("\\s+")) {
					if (part.isEmpty()) continue;
					if (part.startsWith("%")) warnings.accept("block.properties: tags are not allowed in " + key);
					else layerOf.put(namespaced(part), layer);
				}
			});
			layerOf.forEach((name, layer) -> layers.computeIfAbsent(layer, l -> new ArrayList<>()).add(name));
		}
		return new Manifest.IdMaps(blocks, simple(itemText, "item.", warnings), simple(entityText, "entity.", warnings), layers);
	}

	private static List<Manifest.IdEntry> simple(String text, String prefix, Consumer<String> warnings) {
		if (text == null) return List.of();
		Map<String, Integer> idOf = new LinkedHashMap<>();
		OrderedProperties.load(fixup(text)).forEach((key, value) -> {
			if (!key.startsWith(prefix)) return;
			Integer id = parseId(key, prefix, warnings);
			if (id == null) return;
			for (String part : value.trim().split("\\s+")) {
				if (part.isEmpty()) continue;
				if (part.contains("=")) warnings.accept(prefix + "properties: state properties are not supported: " + part);
				else {
					String n = namespaced(part);
					idOf.remove(n);
					idOf.put(n, id);
				}
			}
		});
		Map<Integer, List<String>> byId = new LinkedHashMap<>();
		idOf.forEach((name, id) -> byId.computeIfAbsent(id, k -> new ArrayList<>()).add(name));
		List<Manifest.IdEntry> out = new ArrayList<>();
		byId.forEach((id, names) -> out.add(new Manifest.IdEntry(id, List.copyOf(names))));
		return out;
	}

	private static Integer parseId(String key, String prefix, Consumer<String> warnings) {
		try {
			return Integer.parseInt(key.substring(prefix.length()));
		} catch (NumberFormatException e) {
			warnings.accept("invalid id map key " + key);
			return null;
		}
	}

	/** Iris NamespacedId: no colon means "minecraft". */
	static String namespaced(String id) {
		return id.indexOf(':') < 0 ? "minecraft:" + id : id;
	}

	/** Iris BlockEntry.parse: name | ns:name | name:k=v… | ns:name:k=v… → canonical ns:name[:k=v…]. */
	static String block(String entry) {
		String[] p = entry.split(":");
		if (p.length == 1) return "minecraft:" + entry;
		if (p.length == 2 && !p[1].contains("=")) return p[0] + ":" + p[1];
		int states = p[1].contains("=") ? 1 : 2;
		StringBuilder b = new StringBuilder(states == 1 ? "minecraft:" + p[0] : p[0] + ":" + p[1]);
		for (int i = states; i < p.length; i++) {
			if (p[i].split("=").length == 2) b.append(':').append(p[i]);
		}
		return b.toString();
	}

	/** "%tag[:k=v]" → "#ns:tag[:k=v]" (Iris strips every '%'). */
	static String tag(String entry) {
		return "#" + block(entry.replace("%", ""));
	}
}
