package dev.emrion.shaders.converter.cache;

import dev.emrion.shaders.format.Manifest;
import dev.emrion.shaders.format.ManifestJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheTest {
	@TempDir Path dir;

	String key(Path settings, Map<String, String> macros, List<String> dims) throws Exception {
		return CacheKey.compute("packhash", settings, "26.3", macros, dims);
	}

	@Test
	void keyIgnoresSettingsOrderAndCommentsButNotValues() throws Exception {
		Path a = Files.writeString(dir.resolve("a.txt"), "#comment\nSHADOW_QUALITY=3\nTAA=true\n");
		Path b = Files.writeString(dir.resolve("b.txt"), "TAA = true\n\nSHADOW_QUALITY=3\n");
		Path c = Files.writeString(dir.resolve("c.txt"), "SHADOW_QUALITY=2\nTAA=true\n");
		var macros = Map.of("MC_VERSION", "260300");
		assertEquals(24, key(a, macros, null).length());
		assertEquals(key(a, macros, null), key(b, macros, null));
		assertNotEquals(key(a, macros, null), key(c, macros, null));
		assertNotEquals(key(a, macros, null), key(null, macros, null));
		assertNotEquals(key(a, macros, null), key(a, Map.of("MC_VERSION", "260400"), null));
		assertNotEquals(key(a, macros, null), key(a, macros, List.of("world0")));
		assertEquals(key(a, macros, List.of("world0", "world1")), key(a, macros, List.of("world1", "world0")));
	}

	@Test
	void keyFollowsTheParsedSettings() throws Exception {
		var macros = Map.of("MC_VERSION", "260300");
		// Properties keeps a trailing space: "true " is not a boolean value, so it converts like the default
		Path exact = Files.writeString(dir.resolve("exact.txt"), "FLAG=true\n");
		Path spaced = Files.writeString(dir.resolve("spaced.txt"), "FLAG=true \n");
		assertNotEquals(key(exact, macros, null), key(spaced, macros, null));
		assertEquals(key(exact, macros, null), key(Files.writeString(dir.resolve("colon.txt"), "FLAG:true\n"), macros, null), "':' separates like '='");
		// no two settings maps serialise alike
		Path k1 = Files.writeString(dir.resolve("k1.txt"), "a\\=b=c\n");
		Path k2 = Files.writeString(dir.resolve("k2.txt"), "a=b\\=c\n");
		assertNotEquals(key(k1, macros, null), key(k2, macros, null));
	}

	@Test
	void rtEffectsChangeTheKeyOnlyWhenRequested() throws Exception {
		var macros = Map.of("MC_VERSION", "260300");
		String off = key(null, macros, null);
		assertEquals(off, CacheKey.compute("packhash", null, "26.3", macros, null, java.util.Set.of()));
		String rt = CacheKey.compute("packhash", null, "26.3", macros, null, java.util.Set.of("shadows", "shadows:full"));
		assertNotEquals(off, rt);
		assertEquals(rt, CacheKey.compute("packhash", null, "26.3", macros, null, new java.util.TreeSet<>(java.util.Set.of("shadows:full", "shadows"))));
		assertNotEquals(rt, CacheKey.compute("packhash", null, "26.3", macros, null, java.util.Set.of("shadows")));
	}

	@Test
	void commitIsAtomicAndValidityChecksKey() throws Exception {
		Path tmp = CacheDir.begin(dir, "k1");
		var m = new Manifest(Manifest.FORMAT_VERSION, "0.1.0", "k1", null, null, null, null, null, null, null, null, null);
		ManifestJson.write(tmp.resolve(CacheDir.MANIFEST), m);
		assertNull(CacheDir.readValid(dir.resolve("k1"), "k1"));
		Path out = CacheDir.commit(tmp, dir, "k1");
		assertFalse(Files.exists(tmp));
		assertNotNull(CacheDir.readValid(out, "k1"));
		assertNull(CacheDir.readValid(out, "other"));
		Files.writeString(out.resolve(CacheDir.MANIFEST), "{broken");
		assertNull(CacheDir.readValid(out, "k1"));
	}

	@Test
	void concurrentConversionsOfOneKeyDoNotShareTmpDirs() throws Exception {
		var m = new Manifest(Manifest.FORMAT_VERSION, "0.1.0", "k", null, null, null, null, null, null, null, null, null);
		Path a = CacheDir.begin(dir, "k"), b = CacheDir.begin(dir, "k");
		assertNotEquals(a, b);
		ManifestJson.write(a.resolve(CacheDir.MANIFEST), m);
		Files.writeString(a.resolve("from"), "a");
		ManifestJson.write(b.resolve(CacheDir.MANIFEST), m);
		Files.writeString(b.resolve("from"), "b");
		assertTrue(Files.exists(a), "starting b left a alone");
		Path out = CacheDir.commit(a, dir, "k");
		assertEquals("a", Files.readString(out.resolve("from")));
		assertEquals(out, CacheDir.current(dir, "k"));
		// b becomes the current generation; a reader that resolved a keeps all of a's files
		Path second = CacheDir.commit(b, dir, "k");
		assertNotEquals(out, second);
		assertEquals(second, CacheDir.current(dir, "k"));
		assertEquals("b", Files.readString(second.resolve("from")));
		assertEquals("a", Files.readString(out.resolve("from")));
		assertNotNull(CacheDir.readValid(out, "k"));
		// a day after it was replaced, the next conversion of the key removes a, never the current generation
		Files.setLastModifiedTime(out, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 86_400_000L));
		Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 86_400_000L));
		Path c = CacheDir.begin(dir, "k");
		assertFalse(Files.exists(out));
		assertTrue(Files.exists(second));
		CacheDir.deleteRecursively(c);
		try (var s = Files.list(dir)) {
			assertEquals(java.util.Set.of(second, dir.resolve("k.current")), java.util.Set.copyOf(s.toList()));
		}
	}

	@Test
	void beginRemovesDayOldLegacyKeyDirs() throws Exception {
		var old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 86_400_000L);
		Path stale = Files.createDirectories(dir.resolve("0123456789abcdef01234567")), fresh = Files.createDirectories(dir.resolve("89abcdef0123456789abcdef"));
		Files.writeString(stale.resolve(CacheDir.MANIFEST), "{}");
		Files.setLastModifiedTime(stale, old);
		Path other = Files.createDirectories(dir.resolve("notakey"));
		Files.setLastModifiedTime(other, old);
		Path tmp = CacheDir.begin(dir, "0123456789abcdef0123ffff");
		assertFalse(Files.exists(stale), "day-old legacy dir of another key goes");
		assertTrue(Files.exists(fresh), "a recent legacy dir stays (old code may be using it)");
		assertTrue(Files.exists(other), "only key-shaped names are touched");
		assertTrue(Files.exists(tmp));
	}
}
