package dev.emrion.shaders.converter.nativefmt;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JSON value with the line it starts on, so manifest errors can name {@code emberlight.json:<line>}.
 * value: {@code Map<String, JsonNode>} (key order kept), {@code List<JsonNode>}, String, Double, Boolean or null.
 */
record JsonNode(Object value, int line) {
	private static final Pattern LINE = Pattern.compile(" at line (\\d+)");

	/** Thrown for unreadable JSON: line = where the reader stopped. */
	static final class SyntaxError extends Exception {
		final int line;

		SyntaxError(int line, String message) {
			super(message);
			this.line = line;
		}
	}

	/** JSON with comments (// and /* *}/); duplicate keys are a syntax error. */
	static JsonNode parse(String text) throws SyntaxError {
		JsonReader r = new JsonReader(new StringReader(text));
		r.setStrictness(Strictness.LENIENT);
		try {
			JsonNode n = read(r);
			if (r.peek() != JsonToken.END_DOCUMENT) throw new SyntaxError(line(r), "unexpected content after the top-level value");
			return n;
		} catch (IOException | IllegalStateException | NumberFormatException e) {
			String m = String.valueOf(e.getMessage()).replaceAll("\\s*See https?://\\S+", "").replaceAll(" at line \\d+ column \\d+ path \\S+", "");
			throw new SyntaxError(line(r), "invalid JSON: " + m);
		}
	}

	private static JsonNode read(JsonReader r) throws IOException, SyntaxError {
		JsonToken t = r.peek();
		int line = line(r);
		switch (t) {
			case BEGIN_OBJECT -> {
				r.beginObject();
				Map<String, JsonNode> m = new LinkedHashMap<>();
				while (r.hasNext()) {
					int keyLine = line(r);
					String k = r.nextName();
					if (m.containsKey(k)) throw new SyntaxError(keyLine, "duplicate key \"" + k + "\"");
					m.put(k, read(r));
				}
				r.endObject();
				return new JsonNode(m, line);
			}
			case BEGIN_ARRAY -> {
				r.beginArray();
				List<JsonNode> l = new ArrayList<>();
				while (r.hasNext()) l.add(read(r));
				r.endArray();
				return new JsonNode(l, line);
			}
			case STRING -> { return new JsonNode(r.nextString(), line); }
			case NUMBER -> { return new JsonNode(Double.parseDouble(r.nextString()), line); }
			case BOOLEAN -> { return new JsonNode(r.nextBoolean(), line); }
			case NULL -> { r.nextNull(); return new JsonNode(null, line); }
			default -> throw new SyntaxError(line, "unexpected " + t);
		}
	}

	/** JsonReader has no public position; its toString() carries " at line N column M path P". */
	private static int line(JsonReader r) {
		Matcher m = LINE.matcher(r.toString());
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	@SuppressWarnings("unchecked")
	Map<String, JsonNode> object() { return value instanceof Map<?, ?> m ? (Map<String, JsonNode>) m : null; }

	@SuppressWarnings("unchecked")
	List<JsonNode> array() { return value instanceof List<?> l ? (List<JsonNode>) l : null; }

	String type() {
		return value == null ? "null" : value instanceof Map ? "an object" : value instanceof List ? "an array"
				: value instanceof String ? "a string" : value instanceof Double ? "a number" : "a boolean";
	}
}
