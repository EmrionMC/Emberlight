package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
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
	static final String STAGES = "stages";
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
		pruneUnusedKeys(root, key);
		return Files.createTempDirectory(root, key + ".tmp");
	}

	/** Marks {@code key} as used (a cache hit): {@link #begin} keeps entries used within a week. */
	public static void touch(Path root, String key) {
		try {
			Files.setLastModifiedTime(root.resolve(key + POINTER), FileTime.fromMillis(System.currentTimeMillis()));
		} catch (IOException ignored) {
			// not committed (or just pruned): nothing to keep
		}
	}

	/**
	 * Removes whole entries (pointer, generations, leftovers) of other keys whose pointer and current generation are a week
	 * old: neither committed nor hit since (a hit {@link #touch}es the pointer), so every option change no longer leaves
	 * a full conversion behind forever. The pointer goes first, so a new reader finds no entry and converts.
	 * shortcut: a game that resolved an entry and runs more than a week without a reload could still lose its files; upgrade
	 * by touching the active entry periodically if that ever matters.
	 */
	static void pruneUnusedKeys(Path root, String keep) throws IOException {
		long weekAgo = System.currentTimeMillis() - 7 * 86_400_000L;
		List<Path> pointers;
		try (Stream<Path> all = Files.list(root)) {
			pointers = all.filter(p -> p.getFileName().toString().endsWith(POINTER) && !p.getFileName().toString().equals(keep + POINTER)).toList();
		}
		for (Path ptr : pointers) {
			String key = ptr.getFileName().toString();
			key = key.substring(0, key.length() - POINTER.length());
			if (!LEGACY.matcher(key).matches()) continue; // only key-shaped names are touched
			try {
				Path live = current(root, key);
				if (Files.getLastModifiedTime(ptr).toMillis() >= weekAgo || live != null && Files.exists(live) && Files.getLastModifiedTime(live).toMillis() >= weekAgo)
					continue;
				Files.delete(ptr);
				String prefix = key + ".";
				try (Stream<Path> entry = Files.list(root)) {
					for (Path p : entry.filter(p -> p.getFileName().toString().startsWith(prefix)).toList()) deleteRecursively(p);
				}
			} catch (IOException ignored) {
				// another process is cleaning up too
			}
		}
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

	/**
	 * The per-stage SPIR-V cache {@code <root>/stages/<CacheKey.stage>.spv}: after an option change only the stages whose
	 * source changed compile again. A hit renews the file's age; {@link #pruneStages} drops what went unused for a day.
	 * Null when absent or not a SPIR-V module.
	 */
	public static byte[] readStage(Path root, String hash) {
		Path file = root.resolve(STAGES).resolve(hash + ".spv");
		try {
			byte[] spv = Files.readAllBytes(file);
			if (spv.length < 20 || spv.length % 4 != 0 || spv[0] != 0x03 || spv[1] != 0x02 || spv[2] != 0x23 || spv[3] != 0x07) return null;
			try {
				Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
			} catch (IOException ignored) {
				// pruned meanwhile: the bytes are read
			}
			return spv;
		} catch (IOException e) {
			return null;
		}
	}

	/** Stores a clean compile; written to a private file and renamed, so a concurrent reader never sees half of it. */
	public static void writeStage(Path root, String hash, byte[] spv) {
		Path dir = root.resolve(STAGES), tmp = null;
		try {
			Files.createDirectories(dir);
			tmp = Files.createTempFile(dir, hash, ".tmp");
			Files.write(tmp, spv);
			Files.move(tmp, dir.resolve(hash + ".spv"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try {
				if (tmp != null) Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// the day-old rule removes it
			}
		}
	}

	/** Removes per-stage entries (and leftover tmp files) untouched for a day. */
	public static void pruneStages(Path root) {
		long dayAgo = System.currentTimeMillis() - 86_400_000L;
		try (Stream<Path> files = Files.list(root.resolve(STAGES))) {
			for (Path p : files.toList()) {
				try {
					if (Files.getLastModifiedTime(p).toMillis() < dayAgo) Files.delete(p);
				} catch (IOException ignored) {
					// another process is cleaning up too
				}
			}
		} catch (IOException ignored) {
			// no stage cache yet
		}
	}

	public static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
