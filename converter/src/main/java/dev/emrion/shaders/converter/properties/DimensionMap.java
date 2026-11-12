package dev.emrion.shaders.converter.properties;

import dev.emrion.shaders.format.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * dimension.properties (iris-spec §1.5). Iris matches dimension ids exactly: "*" becomes the wildcard "*:*" (and also
 * "minecraft:*", as Iris adds both), every other token is a namespaced id ("the_nether" → "minecraft:the_nether");
 * patterns like "*nether*" or quoted names are kept verbatim as ids and never match in Iris. A repeated id keeps
 * the last folder.
 */
public final class DimensionMap {
	private DimensionMap() {}

	/** Folder names in key order (dimension.&lt;folder&gt;). */
	public static List<String> folders(SequencedMap<String, String> props) {
		List<String> out = new ArrayList<>();
		props.keySet().forEach(k -> { if (k.startsWith("dimension.")) out.add(k.substring(10)); });
		return out;
	}

	public static List<Manifest.DimensionMapping> parse(SequencedMap<String, String> props) {
		Map<String, String> m = new LinkedHashMap<>();
		props.forEach((key, value) -> {
			if (!key.startsWith("dimension.")) return;
			String folder = key.substring(10);
			for (String part : value.split("\\s+")) {
				if (part.equals("*")) m.put("*:*", folder);
				m.put(IdMapParser.namespaced(part), folder);
			}
		});
		List<Manifest.DimensionMapping> out = new ArrayList<>();
		m.forEach((id, folder) -> out.add(new Manifest.DimensionMapping(id, folder)));
		return out;
	}
}
