package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Cache entries: immutable generation dirs {@code <root>/<key>.g<random>/}, written as a private {@code <key>.tmp<random>/},
 * plus the pointer file {@code <root>/<key>.current} naming the committed generation, replaced atomically. A reader keeps
 * the generation it resolved; a newer commit never changes or removes its files.
 */
public final class CacheDir {
	private CacheDir() {}

	public static final String MANIFEST = "manifest.json";
	public static final String REPORT = "report.json";
	private static final String POINTER = ".current";
	/** A cache dir of the layout before generations: the bare key (CacheKey: 24 hex chars); new entries always contain a dot. */
	private static final java.util.regex.Pattern LEGACY = java.util.regex.Pattern.compile("[0-9a-f]{24}");

	/** The generation dir {@code <key>.current} names, or null when none was committed. */
	public static Path current(Path root, String key) {
		try {
			String name = Files.readString(root.resolve(key + POINTER)).strip();
			return name.startsWith(key + ".g") && name.indexOf('/') < 0 && name.indexOf('\\') < 0 ? root.resolve(name) : null;
		} catch (IOException e) {
			return null;
		}
	}

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
	 * instances, the CLI) never share or delete each other's files. Leftovers of crashed conversions and generations replaced
	 * more than a day ago go (a reader still holding one that long is not expected), and so do cache dirs of any key in the
	 * old {@code <root>/<key>/} layout untouched for a day (old code still using one converts once more).
	 */
	public static Path begin(Path root, String key) throws IOException {
		Files.createDirectories(root);
		long dayAgo = System.currentTimeMillis() - 86_400_000L;
		Path live = current(root, key);
		try (Stream<Path> old = Files.list(root)) {
			for (Path p : old.filter(p -> p.getFileName().toString().startsWith(key + ".") && !p.equals(live) && !p.endsWith(key + POINTER)
					|| LEGACY.matcher(p.getFileName().toString()).matches() && Files.isDirectory(p)).toList()) {
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
	 * Renames the finished tmp dir to a new generation and points {@code <key>.current} at it. The generation it replaces
	 * stays intact for readers that resolved it (its age restarts, so {@link #begin} removes it a day later). The last
	 * commit of a key wins; concurrent ones of the same key have the same output.
	 */
	public static Path commit(Path tmp, Path root, String key) throws IOException {
		String suffix = tmp.getFileName().toString().substring((key + ".tmp").length());
		Path gen = root.resolve(key + ".g" + suffix), ptr = root.resolve(key + ".g" + suffix + ".ptr");
		Files.setLastModifiedTime(tmp, FileTime.fromMillis(System.currentTimeMillis())); // a fresh age: begin() elsewhere leaves it alone until published
		Files.move(tmp, gen, StandardCopyOption.ATOMIC_MOVE);
		Path previous = current(root, key);
		if (previous != null) {
			try {
				Files.setLastModifiedTime(previous, FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException ignored) {
				// already cleaned up
			}
		}
		try {
			Files.writeString(ptr, gen.getFileName().toString());
			Files.move(ptr, root.resolve(key + POINTER), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// e.g. Windows: a reader has the pointer open. The output is complete, only not cached: this caller uses gen,
			// begin() removes it and a leftover ptr file a day later
		}
		return gen;
	}

	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
