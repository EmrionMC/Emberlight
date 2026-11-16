package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** {@code <root>/<key>/} cache directories, written through a private {@code <key>.tmp<random>/} and an atomic rename. */
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
				for (var v : new Manifest.VertexStage[] {s.vertex(), s.tessControl(), s.tessEval(), s.geometry()}) if (v != null) v.variants().values().forEach(files::add);
				for (var ref : new Manifest.SpvRef[] {s.fragment(), s.compute()}) if (ref != null) files.add(ref.spv());
			}
		}
		return files.build().allMatch(f -> f == null || Files.isRegularFile(dir.resolve(f)));
	}

	/**
	 * A fresh, empty {@code <root>/<key>.tmp<random>/}: own per conversion, so concurrent conversions of one key (two game
	 * instances, the CLI) never share or delete each other's files. Leftovers of crashed conversions older than a day go.
	 */
	public static Path begin(Path root, String key) throws IOException {
		Files.createDirectories(root);
		long dayAgo = System.currentTimeMillis() - 86_400_000L;
		try (Stream<Path> old = Files.list(root)) {
			for (Path p : old.filter(p -> p.getFileName().toString().startsWith(key + ".tmp")).toList()) {
				try {
					if (Files.getLastModifiedTime(p).toMillis() < dayAgo) deleteRecursively(p);
				} catch (IOException ignored) {
					// another process is cleaning up too
				}
			}
		}
		return Files.createTempDirectory(root, key + ".tmp");
	}

	/**
	 * Puts the finished tmp dir in place as {@code <root>/<key>/}. The previous entry is moved aside first and deleted only
	 * once the new one is in place (restored if that fails). If another conversion of the same key committed meanwhile,
	 * its result (same key, same output) is kept and tmp is dropped.
	 */
	public static Path commit(Path tmp, Path root, String key) throws IOException {
		Path dir = root.resolve(key), old = tmp.resolveSibling(tmp.getFileName() + ".old");
		try {
			Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
		} catch (NoSuchFileException none) {
			old = null;
		}
		try {
			Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			if (readValid(dir, key) == null) {
				if (old != null) Files.move(old, dir, StandardCopyOption.ATOMIC_MOVE);
				throw e;
			}
			deleteRecursively(tmp);
		}
		if (old != null) deleteRecursively(old);
		return dir;
	}

	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
