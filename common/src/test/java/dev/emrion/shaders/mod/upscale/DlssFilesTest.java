package dev.emrion.shaders.mod.upscale;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DlssFilesTest {
	private static final String SHIM = DlssFiles.shimResource(false);

	@Test
	void extractsMissingOrChangedShimOnly(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("dlss/libemrion_ngx.so");
		assertTrue(DlssFiles.extractShim(SHIM, target));
		byte[] bundled;
		try (var in = DlssFilesTest.class.getResourceAsStream(SHIM)) {
			bundled = in.readAllBytes();
		}
		assertArrayEquals(bundled, Files.readAllBytes(target));

		FileTime old = FileTime.fromMillis(1000);
		Files.setLastModifiedTime(target, old);
		assertTrue(DlssFiles.extractShim(SHIM, target));
		assertEquals(old, Files.getLastModifiedTime(target), "an identical shim is not rewritten");

		Files.writeString(target, "user-built");
		assertTrue(DlssFiles.extractShim(SHIM, target));
		assertArrayEquals(bundled, Files.readAllBytes(target));
		try (var s = Files.list(target.getParent())) {
			assertEquals(1, s.count(), "no temp file left");
		}
	}

	@Test
	void missingBundleLeavesUserShimAlone(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("emrion_ngx.dll");
		Files.writeString(target, "user-built");
		assertFalse(DlssFiles.extractShim("/natives/none/emrion_ngx.dll", target));
		assertEquals("user-built", Files.readString(target));
	}

	@Test
	void downloadChecksHashAndSize(@TempDir Path dir) throws IOException {
		Path remote = Files.createDirectories(dir.resolve("remote/lib"));
		byte[] data = "model".getBytes(StandardCharsets.UTF_8);
		Files.write(remote.resolve("m.so"), data);
		String base = dir.resolve("remote").toUri().toString();
		Path out = dir.resolve("out");

		var good = new DlssFiles.Remote("lib/m.so", "m.so.1", data.length, DlssFiles.sha256(data));
		long[] seen = {0};
		DlssFiles.download(base, List.of(good), out, n -> seen[0] = n);
		assertArrayEquals(data, Files.readAllBytes(out.resolve("m.so.1")));
		assertEquals(data.length, seen[0]);

		var badHash = new DlssFiles.Remote("lib/m.so", "bad.so", data.length, "00".repeat(32));
		assertThrows(IOException.class, () -> DlssFiles.download(base, List.of(badHash), out, n -> {}));
		var tooBig = new DlssFiles.Remote("lib/m.so", "big.so", 2, DlssFiles.sha256(data));
		assertThrows(IOException.class, () -> DlssFiles.download(base, List.of(tooBig), out, n -> {}));
		try (var s = Files.list(out)) {
			assertEquals(List.of("m.so.1"), s.map(p -> p.getFileName().toString()).toList(), "failed downloads leave nothing behind");
		}
	}

	@Test
	void verifyRefusesModifiedOrUnknownModels(@TempDir Path dir) throws IOException {
		byte[] data = "model".getBytes(StandardCharsets.UTF_8);
		var pinned = List.of(new DlssFiles.Remote("lib/m", "libnvidia-ngx-dlss.so.1", data.length, DlssFiles.sha256(data)));
		var ok = new java.util.HashSet<String>();
		assertNull(DlssFiles.verify(dir.resolve("missing"), pinned, "libnvidia-ngx-", ok));
		Files.write(dir.resolve("libnvidia-ngx-dlss.so.1"), data);
		Files.writeString(dir.resolve("libemrion_ngx.so"), "shim, not checked here");
		assertNull(DlssFiles.verify(dir, pinned, "libnvidia-ngx-", ok));
		assertEquals(java.util.Set.of("libnvidia-ngx-dlss.so.1"), ok);

		Files.write(dir.resolve("libnvidia-ngx-dlss.so.1"), "modeL".getBytes(StandardCharsets.UTF_8)); // same size, one byte changed
		assertTrue(DlssFiles.verify(dir, pinned, "libnvidia-ngx-", new java.util.HashSet<>()).contains("not NVIDIA's original"));
		Files.write(dir.resolve("libnvidia-ngx-dlss.so.1"), data);
		Files.write(dir.resolve("libnvidia-ngx-dlss.so.2"), data);
		assertTrue(DlssFiles.verify(dir, pinned, "libnvidia-ngx-", new java.util.HashSet<>()).contains("delete it"));
	}

	@Test
	void pinnedFilesComeFromNvidiasRepo() {
		for (boolean windows : new boolean[] {false, true}) {
			var files = DlssFiles.downloads(windows);
			assertEquals(4, files.size()); // DLSS, DLSS FG, DLSS RR, licence
			assertTrue(files.stream().anyMatch(f -> f.name().contains("dlssd")));
			files.forEach(f -> assertTrue(f.sha256().matches("[0-9a-f]{64}"), f.name()));
			assertTrue(DlssFiles.totalSize(files) > 60_000_000L);
		}
		assertTrue(DlssFiles.BASE_URL.startsWith("https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.16/"));
	}
}
