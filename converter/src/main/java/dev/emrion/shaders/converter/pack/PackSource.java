package dev.emrion.shaders.converter.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Read-only view of a shaderpack (zip or folder). Paths are relative to the pack root and use '/',
 * e.g. "shaders/world0/composite.fsh". Implementations must be thread-safe for reads.
 */
public interface PackSource extends AutoCloseable {
	/** Pack display name (zip/folder name without extension). */
	String name();

	Optional<byte[]> read(String path) throws IOException;

	/** Reads text: tries UTF-8, falls back to ISO-8859-1 (Iris reads properties as ISO-8859-1). */
	Optional<String> readString(String path) throws IOException;

	boolean exists(String path);

	/** All file paths in the pack, sorted. */
	List<String> list();

	/** SHA-256 over (path, bytes) of every file, sorted by path — part of the cache key. */
	String contentHash() throws IOException;

	/** Opens a zip (pack root may sit one folder deep) or a pack folder. The whole pack is read into memory. */
	static PackSource open(Path zipOrDir) throws IOException {
		return MemoryPackSource.open(zipOrDir);
	}

	/** An in-memory pack (generated packs, e.g. a lowered native pack); paths as in {@link #read}. */
	static PackSource of(String name, java.util.SortedMap<String, byte[]> files) {
		return new MemoryPackSource(name, new java.util.TreeMap<>(files));
	}

	@Override
	void close() throws IOException;
}
