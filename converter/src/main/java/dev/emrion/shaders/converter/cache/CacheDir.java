package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** {@code <root>/<key>/} cache directories, written through {@code <key>.tmp/} and an atomic rename (FORMAT.md §1). */
public final class CacheDir {
	private CacheDir() {}

	public static final String MANIFEST = "manifest.json";
	public static final String REPORT = "report.json";

	/** The cached manifest if {@code dir} holds a complete conversion for {@code key}, else null. */
	public static Manifest readValid(Path dir, String key) {
		Path file = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(file)) return null;
		try {
			Manifest m = ManifestJson.readManifest(file);
			return m != null && key.equals(m.cacheKey()) && m.formatVersion() == Manifest.FORMAT_VERSION && filesPresent(dir, m) ? m : null;
		} catch (IOException | RuntimeException corrupt) {
			return null;
		}
	}

	/** Every SPIR-V module and texture the manifest names exists (a cache dir damaged by hand or a crash is converted again). */
	static boolean filesPresent(Path dir, Manifest m) {
		Stream.Builder<String> files = Stream.builder();
		if (m.textures() != null) m.textures().forEach(t -> files.add(t.file()));
		if (m.dimensions() != null) for (var d : m.dimensions().values()) {
			for (var p : d.programs().values()) {
				var s = p.stages();
				if (s == null) continue;
				if (s.vertex() != null) s.vertex().variants().values().forEach(files::add);
				for (var ref : new Manifest.SpvRef[] {s.geometry(), s.fragment(), s.compute()}) if (ref != null) files.add(ref.spv());
			}
		}
		return files.build().allMatch(f -> f == null || Files.isRegularFile(dir.resolve(f)));
	}

	/** Fresh, empty {@code <root>/<key>.tmp/}. */
	public static Path begin(Path root, String key) throws IOException {
		Path tmp = root.resolve(key + ".tmp");
		deleteRecursively(tmp);
		return Files.createDirectories(tmp);
	}

	/** Replaces {@code <root>/<key>/} with the finished tmp dir. */
	public static Path commit(Path tmp, Path root, String key) throws IOException {
		Path dir = root.resolve(key);
		deleteRecursively(dir);
		return Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
