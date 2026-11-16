package dev.emrion.shaders.converter.options;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.emrion.shaders.format.Manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;

/** The Iris settings file {@code <pack>.txt}, written with Properties.store like Iris so files stay byte-compatible. */
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

	/** The non-default options, as Iris stores them; unknown keys of {@code existing} are kept (Iris drops them). */
	public static Map<String, String> nonDefault(List<Manifest.OptionDecl> declared, Map<String, String> values, Map<String, String> existing) {
		Map<String, String> out = new LinkedHashMap<>();
		java.util.Set<String> known = new java.util.HashSet<>();
		for (Manifest.OptionDecl d : declared) {
			known.add(d.name());
			String v = values.get(d.name());
			if (v == null || v.equals(d.defaultValue())) continue;
			if ("bool".equals(d.type()) && !v.equals("true") && !v.equals("false")) continue;
			out.put(d.name(), v);
		}
		existing.forEach((k, v) -> {
			if (!known.contains(k)) out.put(k, v);
		});
		return out;
	}

	/** Writes like Iris unless the file already holds exactly these pairs, so unchanged files keep their date line. */
	public static boolean update(Path file, Map<String, String> settings) throws IOException {
		if (Files.exists(file) ? read(file).equals(settings) : settings.isEmpty()) return false;
		write(file, settings);
		return true;
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
