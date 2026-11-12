package dev.emrion.shaders.format;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Gson I/O for EPF JSON files (FORMAT.md). Record field name = JSON key, enums as lower_snake_case strings, nulls omitted,
 * map keys sorted. */
public final class ManifestJson {
	private ManifestJson() {}

	public static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.registerTypeAdapterFactory(new LowerSnakeEnums())
			.registerTypeAdapterFactory(new SortedMaps())
			.create();

	public static String toJson(Object value) { return GSON.toJson(value) + "\n"; }

	public static void write(Path file, Object value) throws IOException {
		Files.writeString(file, toJson(value), StandardCharsets.UTF_8);
	}

	public static <T> T read(Path file, Class<T> type) throws IOException {
		return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
	}

	public static Manifest readManifest(Path file) throws IOException { return read(file, Manifest.class); }

	/** Writes map keys sorted: Map.of/HashMap order varies between JVM runs, and the cache output must be byte-identical. */
	private static final class SortedMaps implements TypeAdapterFactory {
		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			if (!Map.class.isAssignableFrom(type.getRawType())) return null;
			TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
			return new TypeAdapter<>() {
				@Override
				public void write(JsonWriter out, T value) throws IOException {
					delegate.write(out, value == null ? null : (T) new TreeMap<>((Map) value));
				}

				@Override
				public T read(JsonReader in) throws IOException { return delegate.read(in); }
			};
		}
	}

	private static final class LowerSnakeEnums implements TypeAdapterFactory {
		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			Class<? super T> raw = type.getRawType();
			if (!raw.isEnum()) return null;
			Class<Enum> enumType = (Class<Enum>) raw;
			return (TypeAdapter<T>) new TypeAdapter<Enum>() {
				@Override
				public void write(JsonWriter out, Enum value) throws IOException {
					if (value == null) out.nullValue();
					else out.value(value.name().toLowerCase(Locale.ROOT));
				}

				@Override
				public Enum read(JsonReader in) throws IOException {
					if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
					return Enum.valueOf(enumType, in.nextString().toUpperCase(Locale.ROOT));
				}
			};
		}
	}
}
