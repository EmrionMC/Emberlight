package dev.emrion.shaders.converter.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackSourceTest {
	@TempDir
	Path tmp;

	private Path zip(String name, String... entries) throws Exception {
		Path z = tmp.resolve(name);
		try (OutputStream o = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(o)) {
			for (int i = 0; i < entries.length; i += 2) {
				zo.putNextEntry(new ZipEntry(entries[i]));
				zo.write(entries[i + 1].getBytes(StandardCharsets.ISO_8859_1));
			}
		}
		return z;
	}

	@Test
	void zipWithNestedRoot() throws Exception {
		try (PackSource s = PackSource.open(zip("Pack.zip", "Pack v1/shaders/final.fsh", "x", "Pack v1/readme.txt", "r"))) {
			assertEquals("Pack", s.name());
			assertEquals(List.of("readme.txt", "shaders/final.fsh"), s.list());
			assertTrue(s.exists("shaders/final.fsh"));
			assertEquals("x", s.readString("shaders/final.fsh").orElseThrow());
			assertTrue(s.read("missing").isEmpty());
		}
	}

	@Test
	void zipWithTopLevelShaders() throws Exception {
		try (PackSource s = PackSource.open(zip("p.zip", "shaders/a.vsh", "a"))) {
			assertEquals(List.of("shaders/a.vsh"), s.list());
		}
	}

	@Test
	void folderAndContentHash() throws Exception {
		Path dir = tmp.resolve("folderpack");
		Files.createDirectories(dir.resolve("shaders/lib"));
		Files.writeString(dir.resolve("shaders/lib/a.glsl"), "a");
		Files.write(dir.resolve("shaders/latin.properties"), new byte[]{'k', '=', (byte) 0xE4});
		try (PackSource s = PackSource.open(dir); PackSource z = PackSource.open(zip("z.zip", "shaders/lib/a.glsl", "a", "shaders/latin.properties", "k=ä"))) {
			assertEquals("folderpack", s.name());
			assertEquals("k=ä", s.readString("shaders/latin.properties").orElseThrow(), "falls back to ISO-8859-1");
			assertEquals(64, s.contentHash().length());
			assertEquals(s.contentHash(), z.contentHash(), "same files, same hash");
		}
		Files.writeString(dir.resolve("shaders/lib/a.glsl"), "b");
		try (PackSource s2 = PackSource.open(dir); PackSource z = PackSource.open(tmp.resolve("z.zip"))) {
			assertNotEquals(z.contentHash(), s2.contentHash());
		}
	}

	@Test
	void folderPackIgnoresLinksPointingOutside(@TempDir Path tmp) throws Exception {
		Path secret = Files.writeString(tmp.resolve("secret.txt"), "private");
		Path pack = Files.createDirectories(tmp.resolve("pack/shaders"));
		Files.writeString(pack.resolve("final.fsh"), "x");
		Files.createSymbolicLink(pack.resolve("leak.glsl"), secret);
		Files.createSymbolicLink(pack.resolve("same.fsh"), pack.resolve("final.fsh"));
		Files.createSymbolicLink(pack.resolve("dangling.glsl"), tmp.resolve("nope"));
		Files.createSymbolicLink(pack.resolve("outdir"), tmp);
		try (PackSource s = PackSource.open(tmp.resolve("pack"))) {
			assertFalse(s.exists("shaders/leak.glsl"), "link to a file outside the pack is ignored");
			assertTrue(s.list().stream().noneMatch(f -> f.startsWith("shaders/outdir")), "link to a folder outside is not followed");
			assertEquals("x", s.readString("shaders/same.fsh").orElseThrow(), "link inside the pack still works");
			assertFalse(s.exists("shaders/dangling.glsl"));
		}
	}

	/** A zip whose entries each inflate to {@code mb} MB of zeros (a few hundred KB on disk). */
	private Path bomb(String name, int entries, int mb) throws Exception {
		Path z = tmp.resolve(name);
		byte[] zeros = new byte[1 << 20];
		try (OutputStream o = Files.newOutputStream(z); ZipOutputStream zo = new ZipOutputStream(o)) {
			for (int e = 0; e < entries; e++) {
				zo.putNextEntry(new ZipEntry("shaders/f" + e + ".glsl"));
				for (int i = 0; i < mb; i++) zo.write(zeros);
			}
		}
		return z;
	}

	@Test
	void zipBombsAreRejected() throws Exception {
		var big = assertThrows(java.io.IOException.class, () -> PackSource.open(bomb("one.zip", 1, 129)));
		assertTrue(big.getMessage().contains("larger than 128 MB"), big.getMessage());
		var total = assertThrows(java.io.IOException.class, () -> PackSource.open(bomb("total.zip", 3, 100)));
		assertTrue(total.getMessage().contains("pack is larger than 256 MB"), total.getMessage());
		Path many = tmp.resolve("many.zip");
		try (OutputStream o = Files.newOutputStream(many); ZipOutputStream zo = new ZipOutputStream(o)) {
			for (int i = 0; i <= MemoryPackSource.MAX_FILES; i++) zo.putNextEntry(new ZipEntry("shaders/" + i));
		}
		assertThrows(java.io.IOException.class, () -> PackSource.open(many));
		var dir = assertThrows(java.io.IOException.class, () -> PackSource.open(zip("dir.zip", "shaders/", "hidden data", "shaders/a.fsh", "x")));
		assertTrue(dir.getMessage().contains("directory entry with data"), dir.getMessage());
		try (PackSource ok = PackSource.open(zip("emptydir.zip", "shaders/", "", "shaders/a.fsh", "x"))) {
			assertTrue(ok.read("shaders/a.fsh").isPresent(), "plain directory entries still load");
		}
		try (PackSource ok = PackSource.open(bomb("ok.zip", 1, 20))) {
			assertEquals(20 << 20, ok.read("shaders/f0.glsl").orElseThrow().length, "packs below the limits still load");
		}
	}

	@Test
	void hugeFolderFilesAreRejected() throws Exception {
		Path pack = Files.createDirectories(tmp.resolve("hugepack/shaders"));
		try (var ch = java.nio.channels.FileChannel.open(pack.resolve("big.bin"), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
			ch.write(java.nio.ByteBuffer.wrap(new byte[1]), MemoryPackSource.MAX_FILE_BYTES); // sparse: no real disk use
		}
		assertThrows(java.io.IOException.class, () -> PackSource.open(tmp.resolve("hugepack")));
	}
}
