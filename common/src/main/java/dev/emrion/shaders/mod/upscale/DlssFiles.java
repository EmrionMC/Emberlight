package dev.emrion.shaders.mod.upscale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * The DLSS files on disk: the shim bundled in the jar (LICENSE-DLSS.txt) is extracted, NVIDIA's models are downloaded
 * from NVIDIA's GitHub only after the user agreed to NVIDIA's licence, and checked against pinned hashes.
 */
public final class DlssFiles {
	private DlssFiles() {}

	public static final String TAG = "v310.9.16";
	/** Not Git LFS: raw.githubusercontent.com serves the files themselves. */
	public static final String BASE_URL = "https://raw.githubusercontent.com/NVIDIA/DLSS/" + TAG + "/";
	public static final String LICENCE_URL = "https://github.com/NVIDIA/DLSS/blob/" + TAG + "/LICENSE.txt";
	public static final String LICENCE_FILE = "NVIDIA-RTX-SDK-LICENSE.txt";

	/** A file of NVIDIA's repository at {@link #TAG}, saved as {@code name}. */
	public record Remote(String path, String name, long size, String sha256) {}

	private static final Remote LICENCE = new Remote("LICENSE.txt", LICENCE_FILE, 26620,
			"d4216e39ebef5f9b50a6712ebb37beeb5379862a67733a9999c651f21592aaf0");

	/** The DLSS, DLSS FG and DLSS Ray Reconstruction models plus NVIDIA's licence for this OS. */
	public static List<Remote> downloads(boolean windows) {
		return windows ? List.of(
				new Remote("lib/Windows_x86_64/rel/nvngx_dlss.dll", "nvngx_dlss.dll", 58956912,
						"d00b5b0ab29c3ca101b70c3a5ecb3a01b7ef33766a3383a28a18623083e6123e"),
				new Remote("lib/Windows_x86_64/rel/nvngx_dlssg.dll", "nvngx_dlssg.dll", 7506544,
						"8d59d49b36410dd6b62fef2503a5871d347415bd85d943fa0848563a95dd50c7"),
					new Remote("lib/Windows_x86_64/rel/nvngx_dlssd.dll", "nvngx_dlssd.dll", 48343664,
						"b49435ae346f227feafe7bbe937d88277ea0ee7323975ec06488f377fdb46cee"), LICENCE)
				: List.of(
				new Remote("lib/Linux_x86_64/rel/libnvidia-ngx-dlss.so.310.9.16", "libnvidia-ngx-dlss.so.310.9.16", 59481944,
						"30982c937afed85c96b8d313323de9a650a2eec6de1bb0f714b21750f4ce98f6"),
				new Remote("lib/Linux_x86_64/rel/libnvidia-ngx-dlssg.so.310.9.16", "libnvidia-ngx-dlssg.so.310.9.16", 7391232,
						"574839cae01c835de4d5845c36ca9cf75361f8160bd52397142c36a24e29e6f7"),
					new Remote("lib/Linux_x86_64/rel/libnvidia-ngx-dlssd.so.310.9.16", "libnvidia-ngx-dlssd.so.310.9.16", 48238232,
						"a5d59de18841f15779039dda567a15dd723ba737915f489271fdb5f109fbfb96"), LICENCE);
	}

	public static long totalSize(List<Remote> files) {
		return files.stream().mapToLong(Remote::size).sum();
	}

	/** The jar resource of the shim for this OS. */
	static String shimResource(boolean windows) {
		return windows ? "/natives/windows-x86_64/emrion_ngx.dll" : "/natives/linux-x86_64/libemrion_ngx.so";
	}

	/**
	 * Copies the bundled shim to {@code target} when it is missing or differs. Returns false when this jar has no shim
	 * for the OS (a user-built one is then left alone).
	 */
	static boolean extractShim(String resource, Path target) throws IOException {
		byte[] bundled;
		try (InputStream in = DlssFiles.class.getResourceAsStream(resource)) {
			if (in == null) return false;
			bundled = in.readAllBytes();
		}
		if (Files.isRegularFile(target) && sha256(Files.readAllBytes(target)).equals(sha256(bundled))) return true;
		Files.createDirectories(target.getParent());
		Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
		try {
			Files.write(tmp, bundled);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
		return true;
	}

	/** Downloads each file into {@code dir} (temp file, hash check, atomic move); {@code progress} gets the bytes so far. */
	public static void download(String baseUrl, List<Remote> files, Path dir, LongConsumer progress) throws IOException {
		Files.createDirectories(dir);
		long done = 0;
		for (Remote f : files) {
			Path target = dir.resolve(f.name());
			if (Files.isRegularFile(target) && Files.size(target) == f.size() && sha256(target).equals(f.sha256())) {
				progress.accept(done += f.size());
				continue;
			}
			Path tmp = Files.createTempFile(dir, f.name(), ".part");
			try {
				MessageDigest md = digest();
				var conn = URI.create(baseUrl + f.path()).toURL().openConnection();
				conn.setConnectTimeout(15_000);
				conn.setReadTimeout(30_000);
				try (InputStream in = new DigestInputStream(conn.getInputStream(), md);
						OutputStream out = Files.newOutputStream(tmp)) {
					byte[] buf = new byte[1 << 16];
					long n = 0;
					for (int r; (r = in.read(buf)) > 0; ) {
						if ((n += r) > f.size()) throw new IOException(f.name() + " is larger than expected");
						out.write(buf, 0, r);
						progress.accept(done + n);
					}
				}
				String got = HexFormat.of().formatHex(md.digest());
				if (!got.equals(f.sha256())) throw new IOException(f.name() + ": SHA-256 mismatch (got " + got + ")");
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} finally {
				Files.deleteIfExists(tmp);
			}
			done += f.size();
		}
	}

	/**
	 * Checks every file in {@code dir} whose name starts with {@code prefix} (NGX's library names) against {@code pinned};
	 * adds the matching names to {@code ok}. Returns null, or why NGX must not load from {@code dir}.
	 */
	static String verify(Path dir, List<Remote> pinned, String prefix, java.util.Set<String> ok) {
		try (var s = Files.list(dir)) {
			for (Path p : (Iterable<Path>) s::iterator) {
				String name = p.getFileName().toString();
				if (!name.startsWith(prefix)) continue;
				Remote r = pinned.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
				if (r == null) return name + " in " + dir + " is not one of NVIDIA's DLSS " + TAG + " files: delete it";
				if (Files.size(p) != r.size() || !sha256(p).equals(r.sha256()))
					return name + " is not NVIDIA's original file (damaged or modified): download the DLSS files again";
				ok.add(name);
			}
			return null;
		} catch (java.nio.file.NoSuchFileException e) {
			return null; // nothing installed yet
		} catch (IOException e) {
			return "cannot check the DLSS files in " + dir + ": " + e;
		}
	}

	static String sha256(Path p) throws IOException {
		MessageDigest md = digest();
		try (InputStream in = new DigestInputStream(Files.newInputStream(p), md)) {
			in.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(md.digest());
	}

	static String sha256(byte[] b) {
		return HexFormat.of().formatHex(digest().digest(b));
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
