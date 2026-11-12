package dev.emrion.shaders.converter.properties;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.SequencedMap;

/**
 * java.util.Properties parsing (escapes, ':'/'=' separators, backslash continuations) that keeps key order like Iris's
 * OrderBackedProperties: a repeated key keeps its first position and takes the last value. Values are not trimmed.
 */
public final class OrderedProperties {
	private OrderedProperties() {}

	public static SequencedMap<String, String> load(String text) {
		SequencedMap<String, String> order = new LinkedHashMap<>();
		Properties p = new Properties() {
			@Override
			public synchronized Object put(Object key, Object value) {
				order.put((String) key, (String) value);
				return super.put(key, value);
			}
		};
		try {
			p.load(new StringReader(text));
		} catch (IOException | IllegalArgumentException e) {
			// IllegalArgumentException: malformed \\uXXXX. Iris logs and keeps what was read so far.
		}
		return order;
	}
}
