package dev.emrion.shaders.mod.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PackInstallerTest {
	@TempDir Path tmp;

	@Test
	void droppedFolderLinksAreNotFollowed() throws Exception {
		Path secret = Files.writeString(tmp.resolve("secret.txt"), "private");
		Path pack = Files.createDirectories(tmp.resolve("drop/Pack/shaders"));
		Files.writeString(pack.resolve("final.fsh"), "x");
		try {
			Files.createSymbolicLink(pack.resolve("leak.glsl"), secret);
		} catch (java.io.IOException | UnsupportedOperationException e) { // Windows without Developer Mode or admin
			org.junit.jupiter.api.Assumptions.assumeTrue(false, "cannot create symlinks: " + e);
		}
		Files.createSymbolicLink(pack.resolve("outdir"), tmp);
		Path packs = tmp.resolve("shaderpacks");
		int[] copied = {0};
		PackInstaller.install(pack.getParent(), packs, () -> false, n -> copied[0] = n);
		assertEquals("x", Files.readString(packs.resolve("Pack/shaders/final.fsh")));
		assertFalse(Files.exists(packs.resolve("Pack/shaders/leak.glsl"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertFalse(Files.exists(packs.resolve("Pack/shaders/outdir"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertEquals(1, copied[0]);
		try (Stream<Path> s = Files.list(packs)) {
			assertEquals(1, s.count(), "no staging dir left");
		}
		assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> PackInstaller.install(pack.getParent(), packs, () -> false, n -> {}));
	}

	@Test
	void cancelledInstallLeavesNothing() throws Exception {
		Path zip = Files.writeString(tmp.resolve("p.zip"), "zip");
		Path packs = tmp.resolve("shaderpacks");
		assertThrows(CancellationException.class, () -> PackInstaller.install(zip, packs, () -> true, n -> {}));
		try (Stream<Path> s = Files.list(packs)) {
			assertEquals(0, s.count());
		}
		PackInstaller.install(zip, packs, () -> false, n -> {});
		assertEquals("zip", Files.readString(packs.resolve("p.zip")));
	}
}
