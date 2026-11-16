package dev.emrion.shaders.mod.pack;

import dev.emrion.shaders.converter.cache.CacheDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/** Installs a dropped pack (zip or folder) into shaderpacks/, off the render thread. */
final class PackInstaller {
	private PackInstaller() {}

	/** Staging dirs in shaderpacks/ (hidden from the pack list); a pack appears only once complete. */
	static final String TMP_PREFIX = ".emberlight-install-";

	/**
	 * Copies {@code from} to {@code packs/<name>}. Links inside a dropped folder are skipped, never followed: a link to a file
	 * outside would otherwise be copied in as a regular file. Fails if the pack exists; throws CancellationException.
	 *
	 * @param copied called with the number of files copied so far
	 */
	static void install(Path from, Path packs, BooleanSupplier cancelled, IntConsumer copied) throws IOException {
		from = from.toRealPath(); // the dropped item itself may be a link: the user chose it
		Path dst = packs.resolve(from.getFileName().toString());
		if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(dst.toString());
		Files.createDirectories(packs);
		Path tmp = Files.createTempDirectory(packs, TMP_PREFIX);
		try {
			Path staged = tmp.resolve(dst.getFileName().toString());
			int n = 0;
			try (Stream<Path> tree = Files.walk(from)) {
				for (Path p : (Iterable<Path>) tree::iterator) {
					if (cancelled.getAsBoolean()) throw new CancellationException();
					Path t = staged.resolve(from.relativize(p).toString());
					if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(t);
					else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
						Files.copy(p, t, LinkOption.NOFOLLOW_LINKS);
						copied.accept(++n);
					}
				}
			}
			Files.move(staged, dst); // no REPLACE_EXISTING: a pack installed meanwhile is kept
		} finally {
			CacheDir.deleteRecursively(tmp);
		}
	}
}
