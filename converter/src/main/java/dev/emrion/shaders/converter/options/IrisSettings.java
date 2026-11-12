package dev.emrion.shaders.converter.options;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;

/**
 * The Iris settings file {@code shaderpacks/<pack>.txt} (iris-spec §7): java.util.Properties, ISO-8859-1. Iris writes it
 * with {@code Properties.store(out, null)}, which on current JDKs emits a date comment and the keys in sorted order;
 * using the same call keeps our files byte-compatible with Iris (apart from the date).
 */
public final class IrisSettings {
	private IrisSettings() {}

	/** Reads key/value pairs in file order. A missing file yields an empty map. */
	public static SequencedMap<String, String> read(Path file) throws IOException {
		if (file == null || !Files.exists(file)) return new LinkedHashMap<>();
		try (InputStream in = Files.newInputStream(file)) {
			OrderedLoader p = new OrderedLoader();
			p.load(in);
			return p.order;
		}
	}

	public static SequencedMap<String, String> parse(String text) {
		OrderedLoader p = new OrderedLoader();
		try {
			p.load(new StringReader(text));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return p.order;
	}

	/** Writes like Iris; an empty map deletes the file (Iris keeps no file when nothing differs from the defaults). */
	public static void write(Path file, Map<String, String> values) throws IOException {
		if (values.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		try (OutputStream out = Files.newOutputStream(file)) {
			toProperties(values).store(out, null);
		}
	}

	public static String format(Map<String, String> values) {
		StringWriter w = new StringWriter();
		try {
			toProperties(values).store(w, null);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return w.toString();
	}

	private static Properties toProperties(Map<String, String> values) {
		Properties p = new Properties();
		values.forEach(p::setProperty);
		return p;
	}

	/** Records keys in load order; a repeated key keeps its first position and the last value. */
	private static final class OrderedLoader extends Properties {
		final SequencedMap<String, String> order = new LinkedHashMap<>();

		@Override
		public synchronized Object put(Object key, Object value) {
			order.put((String) key, (String) value);
			return super.put(key, value);
		}
	}
}
