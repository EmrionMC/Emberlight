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
}
