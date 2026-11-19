package dev.emrion.shaders.converter.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A pack read fully into memory. Packs are a few MB, so eager reading keeps reads trivially thread-safe and makes
 * {@link #contentHash()} cheap.
 */
final class MemoryPackSource implements PackSource {
	/**
	 * Untrusted packs: zip bombs and huge trees fail instead of exhausting the heap. Real packs (2026-11, testpacks/): at most
	 * 784 files, 16.6 MB unpacked, largest file 8.4 MB (Shrimple); Complementary Unbound + Euphoria 634 files, 9.7 MB.
	 */
	static final int MAX_FILES = 20_000;
	static final long MAX_FILE_BYTES = 128L << 20, MAX_TOTAL_BYTES = 256L << 20;

	private final String name;
	private final SortedMap<String, byte[]> files;
	private volatile String hash;

	MemoryPackSource(String name, SortedMap<String, byte[]> files) {
		this.name = name;
		this.files = files;
	}

	static PackSource open(Path zipOrDir) throws IOException {
		String fileName = zipOrDir.getFileName().toString();
		if (Files.isDirectory(zipOrDir)) {
			return new MemoryPackSource(fileName, readDirectory(zipOrDir));
		}
		String name = fileName.toLowerCase().endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
		return new MemoryPackSource(name, readZip(zipOrDir));
	}

	private static SortedMap<String, byte[]> readDirectory(Path dir) throws IOException {
		SortedMap<String, byte[]> files = new TreeMap<>();
		Budget budget = new Budget();
		dir = dir.toRealPath(); // shaderpacks entries are often symlinks; Files.walk does not descend into a linked root
		// links inside the pack are not followed: a link pointing outside could pull any file of the user into the pack
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				budget.count(p.toString());
				Path real = p;
				if (Files.isSymbolicLink(p)) {
					try {
						real = p.toRealPath();
					} catch (IOException dangling) {
						continue;
					}
					if (!real.startsWith(dir)) continue;
				}
				if (Files.isRegularFile(real, java.nio.file.LinkOption.NOFOLLOW_LINKS))
					try (InputStream in = Files.newInputStream(real)) {
						files.put(dir.relativize(p).toString().replace('\\', '/'), budget.read(in, p.toString()));
					}
			}
		}
		return files;
	}

	private static SortedMap<String, byte[]> readZip(Path zip) throws IOException {
		SortedMap<String, byte[]> raw = new TreeMap<>();
		Budget budget = new Budget();
		try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
			for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
				budget.count(e.getName());
				if (!e.isDirectory()) raw.put(e.getName().replace('\\', '/'), budget.read(zin, e.getName()));
				// the next getNextEntry() would inflate the rest unmetered (a zip bomb hidden behind a "dir/" name)
				else if (zin.read() != -1) throw new IOException("directory entry with data: " + e.getName());
			}
		}
		String root = findRoot(raw.keySet());
		SortedMap<String, byte[]> files = new TreeMap<>();
		raw.forEach((path, bytes) -> {
			if (path.startsWith(root)) files.put(path.substring(root.length()), bytes);
		});
		return files;
	}

	/** Counts files and bytes actually read (a zip's declared sizes can lie). */
	private static final class Budget {
		int files;
		long total;

		void count(String path) throws IOException {
			if (++files > MAX_FILES) throw new IOException("pack has more than " + MAX_FILES + " files (at " + path + ")");
		}

		byte[] read(InputStream in, String path) throws IOException {
			long limit = Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - total);
			byte[] b = in.readNBytes((int) limit + 1);
			if (b.length > limit) throw new IOException(b.length > MAX_FILE_BYTES ? path + " is larger than " + (MAX_FILE_BYTES >> 20) + " MB"
					: "pack is larger than " + (MAX_TOTAL_BYTES >> 20) + " MB unpacked (at " + path + ")");
			total += b.length;
			return b;
		}
	}

	/** The pack root is the zip root, or the single folder one level down that contains "shaders/". */
	private static String findRoot(Iterable<String> paths) {
		String nested = null;
		for (String p : paths) {
			if (p.startsWith("shaders/")) return "";
			int slash = p.indexOf('/');
			if (slash > 0 && p.startsWith("shaders/", slash + 1) && nested == null) nested = p.substring(0, slash + 1);
		}
		return nested == null ? "" : nested;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public Optional<byte[]> read(String path) {
		byte[] b = files.get(path);
		return b == null ? Optional.empty() : Optional.of(b.clone());
	}

	@Override
	public Optional<String> readString(String path) {
		byte[] b = files.get(path);
		if (b == null) return Optional.empty();
		try {
			return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString());
		} catch (CharacterCodingException e) {
			return Optional.of(new String(b, StandardCharsets.ISO_8859_1));
		}
	}

	@Override
	public boolean exists(String path) {
		return files.containsKey(path);
	}

	@Override
	public List<String> list() {
		return List.copyOf(files.keySet());
	}

	@Override
	public String contentHash() {
		String h = hash;
		if (h == null) {
			try {
				MessageDigest md = MessageDigest.getInstance("SHA-256");
				ByteBuffer len = ByteBuffer.allocate(8);
				files.forEach((path, bytes) -> {
					md.update(path.getBytes(StandardCharsets.UTF_8));
					md.update((byte) 0);
					md.update(len.clear().putLong(bytes.length).flip());
					md.update(bytes);
				});
				hash = h = HexFormat.of().formatHex(md.digest());
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}
		return h;
	}

	@Override
	public void close() {
	}
}
