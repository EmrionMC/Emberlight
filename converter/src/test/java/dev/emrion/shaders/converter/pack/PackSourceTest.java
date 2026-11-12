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
}
